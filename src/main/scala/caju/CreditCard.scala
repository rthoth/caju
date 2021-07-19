package caju

import akka.actor.typed.scaladsl.{ActorContext, Behaviors, StashBuffer, TimerScheduler}
import akka.actor.typed.{ActorRef, Behavior}
import caju.CreditCardManager.{RejectedT, TransactionResponse}
import caju.protocol.Transaction

import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success}

object CreditCard {

  final case object NotFound extends Cmd

  /** Time To Die */
  private case object TTD extends Cmd

  private case class AccountInto(account: Account) extends Cmd

  final case class Authorize(mcc: Int, transaction: Transaction, replyTo: ActorRef[TransactionResponse]) extends Cmd

  final case class RepositoryFailure(cause: Throwable) extends Cmd

  private case class UpdateFailure(cause: Throwable, authorize: Authorize) extends Cmd

  private case class UpdateSuccess(authorize: Authorize) extends Cmd

  def apply(repository: AccountRepository, account: String, limit: Int, ttl: FiniteDuration): Behavior[Cmd] = Behaviors.withStash(limit) { buffer =>
    Behaviors.setup { ctx =>
      Behaviors.withTimers { timers =>
        new CreditCard(repository, account, ctx, buffer, timers, ttl).start()
      }
    }
  }

  sealed trait Cmd

}

import caju.CreditCard._

class CreditCard(
  repository: AccountRepository,
  accountCode: String,
  ctx: ActorContext[Cmd],
  buffer: StashBuffer[Cmd],
  timers: TimerScheduler[Cmd],
  ttl: FiniteDuration
) {

  private val waitAuthorization: Behavior[Cmd] = Behaviors.receiveMessage {
    case authorize: Authorize =>
      buffer.stash(authorize)
      load()
      waitInfo

    case _ =>
      Behaviors.same
  }

  private val waitUpdate: Behavior[Cmd] = Behaviors.receiveMessage {
    case UpdateSuccess(Authorize(_, _, replyTo)) =>
      replyTo ! CreditCardManager.ApprovedT
      waitAuthorization

    case UpdateFailure(cause, Authorize(_, _, replyTo)) =>
      ctx.log.error("Transaction has failed!", cause)
      replyTo ! CreditCardManager.FailedT(cause)
      start()

    case authorize: Authorize =>
      buffer.stash(authorize)
      Behaviors.same
  }

  private val timeToDie: Behavior[Cmd] = Behaviors.receiveMessage {
    case Authorize(_, _, replyTo) =>
      replyTo ! RejectedT
      if (buffer.nonEmpty)
        Behaviors.same
      else
        Behaviors.stopped

    case _ =>
      Behaviors.stopped
  }

  private val waitInfo: Behavior[Cmd] = Behaviors.receiveMessage {
    case authorize: Authorize =>
      buffer.stash(authorize)
      Behaviors.same

    case AccountInto(info) =>
      buffer.unstash(authorize(info), 1, identity)

    case NotFound =>
      ctx.log.error("Account {} not found!", accountCode)

      buffer.unstashAll(Behaviors.receiveMessage {
        case Authorize(_, _, replyTo) =>
          replyTo ! RejectedT
          Behaviors.same
      })

    case TTD =>
      timeToDie

    case _ =>
      Behaviors.same
  }

  private def authorize(account: Account): Behavior[Cmd] = {

    Behaviors.receiveMessage {
      case authorize@Authorize(mcc, transaction, replyTo) =>

        account.minus((transaction.totalAmount * 100).toInt, mcc) match {
          case Some(newAccount) =>
            ctx.pipeToSelf(repository.save(newAccount)) {
              case Success(_) => UpdateSuccess(authorize)
              case Failure(cause) => UpdateFailure(cause, authorize)
            }

            waitUpdate

          case None =>
            replyTo ! RejectedT
            Behaviors.same
        }

      case _ =>
        Behaviors.same
    }
  }

  private def load(): Unit = {
    ctx.pipeToSelf(repository.get(accountCode)) {
      case Success(opt) => opt match {
        case Some(info) => AccountInto(info)
        case None => NotFound
      }
      case Failure(cause) => RepositoryFailure(cause)
    }
  }

  private def start(): Behavior[Cmd] = {
    load()
    timers.startSingleTimer(TTD, ttl)
    waitInfo
  }
}
