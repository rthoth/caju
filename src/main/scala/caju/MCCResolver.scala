package caju

import akka.actor.typed.scaladsl.{ActorContext, Behaviors, TimerScheduler}
import akka.actor.typed.{ActorRef, Behavior}
import caju.protocol.Transaction

import scala.concurrent.duration._
import scala.util.{Failure, Success}

object MCCResolver {

  private object TTD extends Message

  private case class Accepted(original: Authorizer.Accepted.type) extends Message

  private case class FailedMerchant(cause: Throwable, resolve: Resolve) extends Message

  private case class Found(mcc: Int, resolve: Resolve) extends Message

  private case class NotFoundMerchant(resolve: Resolve) extends Message

  final case class Resolve(transaction: Transaction, forwardTo: ActorRef[Authorizer.Message], replyTo: ActorRef[Authorizer.Response]) extends Message

  def apply(repository: MerchantRepository): Behavior[Message] = Behaviors.setup { ctx =>
    ctx.setLoggerName(classOf[MCCResolver])
    ctx.log.debug("Starting...")

    Behaviors.withTimers { scheduler =>
      new MCCResolver(ctx, repository, scheduler, 2.seconds).start()
    }
  }

  sealed trait Message
}

import caju.MCCResolver._

class MCCResolver(ctx: ActorContext[Message], repository: MerchantRepository, scheduler: TimerScheduler[Message], ttl: FiniteDuration) {

  private def forward(forwardTo: ActorRef[Authorizer.Message], mcc: Int, transaction: Transaction, replyTo: ActorRef[Authorizer.Response]): Behavior[Message] = {
    forwardTo ! Authorizer.Authorize(mcc, transaction, ctx.messageAdapter(Accepted), replyTo)
    scheduler.startSingleTimer(TTD, ttl)
    val zero = System.currentTimeMillis()

    Behaviors.receiveMessage {
      case Accepted(_) =>
        ctx.log.debug("Authorizer accepted, it took {}ms.", System.currentTimeMillis() - zero)
        Behaviors.stopped

      case TTD =>
        ctx.log.warn("No confirmation from Authorizer.")
        replyTo ! Authorizer.Failed(new CajuException.Timeout(), transaction)
        Behaviors.stopped
    }
  }

  protected def start(): Behavior[Message] = Behaviors.receiveMessage {
    case resolve@Resolve(transaction@Transaction(_, _, _, merchant), _, replyTo) =>
      if (merchant.length == 40) {
        val name = merchant.substring(0, 25).trim()
        val location = merchant.substring(25).trim()

        ctx.pipeToSelf(repository.search(name, location)) {
          case Success(Some(mcc)) => Found(mcc, resolve)
          case Success(_) => NotFoundMerchant(resolve)
          case Failure(cause) => FailedMerchant(cause, resolve)
        }

        waitRepository()
      } else {
        replyTo ! Authorizer.Failed(new CajuException.Invalid(s"merchant length = ${merchant.length}!"), transaction)
        ctx.log.debug("Stopped.")
        Behaviors.stopped
      }
  }

  protected def waitRepository(): Behavior[Message] = Behaviors.receiveMessage[Message] {
    case Found(mcc, Resolve(transaction, forwardTo, replyTo)) =>
      ctx.log.debug("Found mcc for {}.", transaction.merchant)
      forward(forwardTo, mcc, transaction, replyTo)

    case NotFoundMerchant(Resolve(transaction, forwardTo, replyTo)) =>
      ctx.log.debug("Not found mcc for {}.", transaction.merchant)
      val mcc = try {
        transaction.mcc.toInt
      } catch {
        case _: Throwable => -1
      }
      forward(forwardTo, mcc, transaction, replyTo)

    case FailedMerchant(cause, Resolve(transaction, _, replyTo)) =>
      replyTo ! Authorizer.Failed(cause, transaction)
      ctx.log.debug("Stopped.")
      Behaviors.stopped
  }
}
