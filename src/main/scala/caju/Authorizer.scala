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

  final case class Approved(mcc: Int, transaction: Transaction) extends Response

  final case class Authorize(transaction: Transaction, replyTo: ActorRef[Response]) extends Message {

    def account: String = transaction.account

    def merchant: String = transaction.merchant

    def merchantQuery: Merchant.Query = transaction.merchantQuery
  }

  final case class Failed(cause: Throwable, transaction: Transaction) extends Response

  private case class MCCFailure(cause: Throwable) extends Message

  private case class MCCSuccess(mcc: Option[Int]) extends Message

  final case class Rejected(mcc: Int, transaction: Transaction) extends Response

  private case class RepositoryFailure(cause: Throwable) extends Message

  private case class RepositorySuccess(account: Account) extends Message

  private case class UpdateFailure(cause: Throwable, authorize: Authorize) extends Message

  private case class UpdateSuccess(authorize: Authorize, mcc: Int, account: Account) extends Message

  def apply(accountRepo: AccountRepository, merchantRepo: MerchantRepository, account: String, bufferLimit: Int, ttl: FiniteDuration): Behavior[Message] = Behaviors.withStash(bufferLimit) { buffer =>
    Behaviors.setup { ctx =>
      Behaviors.withTimers { timers =>
        ctx.setLoggerName(s"caju.Authorizer($account)")
        new Authorizer(accountRepo, merchantRepo, account, ctx, buffer, timers, ttl).start()
      }
    }
  }

  sealed trait Response

  sealed trait Message

}

import caju.Authorizer._

class Authorizer(
  accountRepo: AccountRepository,
  merchantRepo: MerchantRepository,
  code: String,
  ctx: ActorContext[Message],
  buffer: StashBuffer[Message],
  scheduler: TimerScheduler[Message],
  ttl: FiniteDuration
) {

  private var shouldToDie = false

  def start(): Behavior[Message] = {
    ctx.log.debug("Started.")
    waitNextAuthorize()
  }

  private def execute(authorize: Authorize, account: Account, optMCC: Option[Int]): Behavior[Message] = {

    ctx.log.debug("Executing authorize process, account: meal={}, food={}, culture={}, cash={}.", account.meal / 100D, account.food / 100D, account.culture / 100D, account.cash / 100D)
    val Authorize(transaction, replyTo) = authorize
    val mcc = optMCC match {
      case None => try {
        transaction.mcc.toInt
      } catch {
        case _: Throwable => 0
      }

      case Some(value) =>
        value
    }

    account.minus((transaction.totalAmount * 100).toInt, mcc) match {
      case Some(newAccount) =>

        ctx.pipeToSelf(accountRepo.save(newAccount)) {
          case Success(updatedAccount) => UpdateSuccess(authorize, mcc, updatedAccount)
          case Failure(cause) => UpdateFailure(cause, authorize)
        }

        waitUpdate()

      case None =>
        log.info("Rejected: {} {} {}.", transaction.totalAmount, mcc, transaction.merchant)
        replyTo ! Rejected(mcc, transaction)
        if (buffer.isEmpty)
          waitNextAuthorize()
        else
          unstash(execute(account))
    }
  }

  private def execute(account: Account): Behavior[Message] = {
    stopTTL()
    Behaviors.receiveMessage {
      case authorize: Authorize =>
        resolveMCC(authorize, account)
    }
  }

  private def fail(authorize: Authorize, cause: Throwable, behavior: Behavior[Message]): Behavior[Message] = {
    authorize.replyTo ! Failed(cause, authorize.transaction)
    behavior
  }

  private def fetchAccount(): Behavior[Message] = {
    ctx.pipeToSelf(accountRepo.get(code)) {
      case Success(Some(account)) => RepositorySuccess(account)
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

  private def resolveMCC(authorize: Authorize, account: Account): Behavior[Message] = {

    ctx.pipeToSelf(merchantRepo.search(authorize.merchantQuery)) {
      case Success(mcc) => MCCSuccess(mcc)
      case Failure(cause) => MCCFailure(cause)
    }

    Behaviors.receiveMessage {
      case MCCSuccess(mcc) =>
        execute(authorize, account, mcc)

      case MCCFailure(cause) =>
        fail(authorize, cause, unstash(waitNextAuthorize()))

      case other: Authorize =>
        stash(other, shouldLog = true, Behaviors.same)
    }
  }

  private def startTTL(): Unit = {
    shouldToDie = true
    scheduler.startSingleTimer(TTD, ttl)
  }

  private def stash(authorize: Authorize, shouldLog: Boolean, behavior: Behavior[Message]): Behavior[Message] = {
    if (shouldLog)
      ctx.log.debug("Stashing transaction, merchant {}.", authorize.merchant)

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
    if (shouldToDie) {
      shouldToDie = false
      scheduler.cancel(TTD)
    }
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
    ctx.log.debug("Waiting account.")
    startTTL()
    Behaviors.receiveMessage {
      case authorize: Authorize =>
        stash(authorize, shouldLog = true, Behaviors.same)

      case RepositorySuccess(account) =>
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
        ctx.log.debug("New process, merchant {}.", authorize.merchant)
        stash(authorize, shouldLog = false, fetchAccount())

      case TTD =>
        timeToDie(new CajuException.Timeout())
    }
  }

  private def waitUpdate(): Behavior[Message] = {
    ctx.log.debug("Updating repository...")
    Behaviors.receiveMessage {
      case UpdateSuccess(Authorize(transaction, replyTo), mcc, account) =>
        replyTo ! Approved(mcc, transaction)
        log.info("Approved: {} {} {}.", transaction.totalAmount, mcc, transaction.merchant)
        log.debug("Account updated: meal={}, food={}, culture={}, cash={}", account.meal / 100D, account.food / 100D, account.culture / 100D, account.cash / 100D)

        if (buffer.isEmpty)
          waitNextAuthorize()
        else
          unstash(execute(account))

      case UpdateFailure(cause, authorize) =>
        fail(authorize, cause, unstash(waitNextAuthorize()))

      case authorize: Authorize =>
        stash(authorize, shouldLog = true, Behaviors.same)
    }
  }
}
