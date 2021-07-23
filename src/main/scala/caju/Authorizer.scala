package caju

import akka.actor.typed.scaladsl.{ActorContext, Behaviors, StashBuffer, TimerScheduler}
import akka.actor.typed.{ActorRef, Behavior}
import caju.protocol.Transaction

import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success}

object Authorizer {

  private object NotFound extends Message

  /** Time To Die */
  private object TTD extends Message

  private case class AccountWrapper(account: Account) extends Message

  final case class Approved(mcc: Int, transaction: Transaction) extends Response

  final case class Authorize(mcc: Int, transaction: Transaction, replyTo: ActorRef[Response]) extends Message {

    def account: String = transaction.account
  }

  final case class Failed(cause: Throwable, transaction: Transaction) extends Response

  final case class Rejected(mcc: Int, transaction: Transaction) extends Response

  private case class RepositoryFailure(cause: Throwable) extends Message

  private case class UpdateFailure(cause: Throwable, authorize: Authorize) extends Message

  private case class UpdateSuccess(authorize: Authorize) extends Message

  def apply(repository: AccountRepository, account: String, bufferLimit: Int, ttl: FiniteDuration): Behavior[Message] = Behaviors.withStash(bufferLimit) { buffer =>
    Behaviors.setup { ctx =>
      Behaviors.withTimers { timers =>
        new Authorizer(repository, account, ctx, buffer, timers, ttl).start()
      }
    }
  }

  sealed trait Response

  sealed trait Message

}

import caju.Authorizer._

class Authorizer(
  repository: AccountRepository,
  code: String,
  ctx: ActorContext[Message],
  buffer: StashBuffer[Message],
  scheduler: TimerScheduler[Message],
  ttl: FiniteDuration
) {

  private var shouldToDie = false

  def start(): Behavior[Message] = {
    ctx.log.debug("Starting authorizer for {}...", code)
    waitNextAuthorize()
  }

  private def execute(account: Account): Behavior[Message] = {
    stopTTL()

    Behaviors.receiveMessage {
      case authorize@Authorize(mcc, transaction, replyTo) =>
        account.minus((transaction.totalAmount * 100).toInt, mcc) match {
          case Some(newAccount) =>

            ctx.pipeToSelf(repository.save(newAccount)) {
              case Success(_) => UpdateSuccess(authorize)
              case Failure(cause) => UpdateFailure(cause, authorize)
            }

            waitUpdate()

          case None =>
            replyTo ! Rejected(mcc, transaction)
            log.info("Rejected: {} {} {} {}.", code, transaction.totalAmount, mcc, transaction.merchant)
            if (buffer.isEmpty)
              waitNextAuthorize()
            else
              unstash(execute(account))
        }
    }
  }

  private def fail(authorize: Authorize, cause: Throwable, behavior: Behavior[Message]): Behavior[Message] = {
    authorize.replyTo ! Failed(cause, authorize.transaction)
    behavior
  }

  private def fetchAccount(): Behavior[Message] = {
    ctx.pipeToSelf(repository.get(code)) {
      case Success(Some(account)) => AccountWrapper(account)
      case Success(_) => NotFound
      case Failure(cause) => RepositoryFailure(cause)
    }

    waitAccount()
  }

  @inline
  private def log = ctx.log

  private def reportRepositoryFailure(cause: Throwable): Behavior[Message] = Behaviors.receiveMessage {
    case authorize: Authorize =>
      fail(authorize, cause, waitNextAuthorize())
    case _ =>
      Behaviors.same
  }

  private def startTTL(): Unit = {
    shouldToDie = true
    scheduler.startSingleTimer(TTD, ttl)
  }

  private def stash(authorize: Authorize, behavior: Behavior[Message]): Behavior[Message] = {
    if (authorize.account == code) {
      if (!buffer.isFull) {
        buffer.stash(authorize)
        behavior
      } else {
        fail(authorize, new CajuException.Full("There is no space!"), behavior)
      }
    } else {
      fail(authorize, new CajuException.Invalid(authorize.account), Behaviors.same)
    }
  }

  private def stopTTL(): Unit = {
    shouldToDie = false
    scheduler.cancel(TTD)
  }

  private def timeToDie(cause: Throwable): Behavior[Message] = {
    if (shouldToDie) {
      ctx.log.debug("I'm gonna die!")
      if (buffer.nonEmpty) {
        unstash(Behaviors.receiveMessage {
          case authorize: Authorize =>
            fail(authorize, cause, if (buffer.nonEmpty)
              unstash(Behaviors.same)
            else
              Behaviors.stopped
            )

          case _ =>
            Behaviors.same
        })
      } else {
        Behaviors.stopped
      }
    } else {
      ctx.log.debug("I should to die, but it isn't my time!")
      Behaviors.same
    }
  }

  private def unstash(behavior: Behavior[Message]): Behavior[Message] = {
    buffer.unstash(behavior, 1, identity)
  }

  private def waitAccount(): Behavior[Message] = {
    startTTL()
    Behaviors.receiveMessage {
      case authorize: Authorize =>
        ctx.log.debug("Stashing transaction.")
        stash(authorize, Behaviors.same)

      case AccountWrapper(account) =>
        ctx.log.debug("Account arrived.")
        unstash(execute(account))

      case RepositoryFailure(cause) =>
        ctx.log.error("Repository failed!", cause)
        unstash(reportRepositoryFailure(cause))

      case TTD =>
        timeToDie(new CajuException.Timeout())

      case NotFound =>
        timeToDie(new CajuException.NotFound(code))
    }
  }

  private def waitNextAuthorize(): Behavior[Message] = {
    startTTL()
    Behaviors.receiveMessage {
      case authorize: Authorize =>
        stash(authorize, fetchAccount())

      case TTD =>
        timeToDie(new CajuException.Timeout())
    }
  }

  private def waitUpdate(): Behavior[Message] = Behaviors.receiveMessage {
    case UpdateSuccess(Authorize(mcc, transaction, replyTo)) =>
      replyTo ! Approved(mcc, transaction)
      log.info("Approved: {} {} {} {}.", code, transaction.totalAmount, mcc, transaction.merchant)
      unstash(waitNextAuthorize())

    case UpdateFailure(cause, authorize) =>
      fail(authorize, cause, unstash(waitNextAuthorize()))

    case authorize: Authorize =>
      stash(authorize, Behaviors.same)
  }
}
