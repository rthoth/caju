package caju

import akka.actor.typed.scaladsl.{ActorContext, Behaviors, StashBuffer, TimerScheduler}
import akka.actor.typed.{ActorRef, Behavior}
import caju.protocol.Transaction

import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success}

object CreditCard {

  private object NotFound extends Cmd

  /** Time To Die */
  private object TTD extends Cmd

  private case class AccountWrapper(account: Account) extends Cmd

  final case class Approved(mcc: Int, transaction: Transaction) extends AuthorizeResponse

  final case class Authorize(mcc: Int, transaction: Transaction, replyTo: ActorRef[AuthorizeResponse]) extends Cmd {

    def account: String = transaction.account
  }

  final case class Failed(cause: Throwable, mcc: Int, transaction: Transaction) extends AuthorizeResponse

  final case class Rejected(mcc: Int, transaction: Transaction) extends AuthorizeResponse

  private case class RepositoryFailure(cause: Throwable) extends Cmd

  private case class UpdateFailure(cause: Throwable, authorize: Authorize) extends Cmd

  private case class UpdateSuccess(authorize: Authorize) extends Cmd

  def apply(repository: AccountRepository, account: String, bufferLimit: Int, ttl: FiniteDuration): Behavior[Cmd] = Behaviors.withStash(bufferLimit) { buffer =>
    Behaviors.setup { ctx =>
      Behaviors.withTimers { timers =>
        new CreditCard(repository, account, ctx, buffer, timers, ttl).start()
      }
    }
  }

  sealed trait AuthorizeResponse

  sealed trait Cmd

}

import caju.CreditCard._

class CreditCard(
  repository: AccountRepository,
  code: String,
  ctx: ActorContext[Cmd],
  buffer: StashBuffer[Cmd],
  scheduler: TimerScheduler[Cmd],
  ttl: FiniteDuration
) {

  private var shouldToDie = false

  def start(): Behavior[Cmd] = {
    waitNextAuthorize()
  }

  private def execute(account: Account): Behavior[Cmd] = {
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

  private def fail(authorize: Authorize, cause: Throwable, behavior: Behavior[Cmd]): Behavior[Cmd] = {
    authorize.replyTo ! Failed(cause, authorize.mcc, authorize.transaction)
    behavior
  }

  private def fetchAccount(): Behavior[Cmd] = {
    startTTL()
    ctx.pipeToSelf(repository.get(code)) {
      case Success(Some(account)) => AccountWrapper(account)
      case Success(_) => NotFound
      case Failure(cause) => RepositoryFailure(cause)
    }

    waitAccount()
  }

  @inline
  private def log = ctx.log

  private def reportRepositoryFailure(cause: Throwable): Behavior[Cmd] = Behaviors.receiveMessage {
    case authorize: Authorize =>
      fail(authorize, cause, waitNextAuthorize())
    case _ =>
      Behaviors.same
  }

  private def startTTL(): Unit = {
    shouldToDie = true
    scheduler.startSingleTimer(TTD, ttl)
  }

  private def stash(authorize: Authorize, behavior: Behavior[Cmd]): Behavior[Cmd] = {
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

  private def timeToDie(cause: Throwable): Behavior[Cmd] = {
    if (shouldToDie) {
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
      Behaviors.same
    }
  }

  private def unstash(behavior: Behavior[Cmd]): Behavior[Cmd] = {
    buffer.unstash(behavior, 1, identity)
  }

  private def waitAccount(): Behavior[Cmd] = Behaviors.receiveMessage {
    case authorize: Authorize =>
      stash(authorize, Behaviors.same)

    case AccountWrapper(account) =>
      unstash(execute(account))

    case RepositoryFailure(cause) =>
      unstash(reportRepositoryFailure(cause))

    case TTD =>
      timeToDie(new CajuException.Timeout())

    case NotFound =>
      timeToDie(new CajuException.NotFound(code))
  }

  private def waitNextAuthorize(): Behavior[Cmd] = {
    startTTL()
    Behaviors.receiveMessage {
      case authorize: Authorize =>
        stash(authorize, fetchAccount())

      case TTD =>
        timeToDie(new CajuException.Timeout())
    }
  }

  private def waitUpdate(): Behavior[Cmd] = Behaviors.receiveMessage {
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
