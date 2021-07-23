package caju

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import caju.protocol.Transaction

import scala.util.{Failure, Success}

object MCCResolver {

  final case class Failed(cause: Throwable, transaction: Transaction) extends Response

  private final case class FailedMerchant(cause: Throwable, resolve: Resolve) extends Message

  private final case class Found(mcc: Int, resolve: Resolve) extends Message

  private final case class NotFoundMerchant(resolve: Resolve) extends Message

  final case class Resolve(transaction: Transaction, replyTo: ActorRef[Response]) extends Message

  final case class Resolved(mcc: Int, transaction: Transaction) extends Response

  def apply(repository: MerchantRepository): Behavior[Message] = Behaviors.setup { ctx =>
    ctx.setLoggerName("caju.MCCResolver")
    ctx.log.debug("Starting...")

    Behaviors.receiveMessage {
      case resolve@Resolve(transaction@Transaction(_, _, _, merchant), replyTo) =>
        if (merchant.length == 40) {
          val name = merchant.substring(0, 25).trim()
          val location = merchant.substring(25).trim()

          ctx.pipeToSelf(repository.search(name, location)) {
            case Success(Some(mcc)) => Found(mcc, resolve)
            case Success(_) => NotFoundMerchant(resolve)
            case Failure(cause) => FailedMerchant(cause, resolve)
          }

          waitRepository(ctx)
        } else {
          replyTo ! Failed(new CajuException.Invalid(s"merchant length = ${merchant.length}!"), transaction)
          ctx.log.debug("Stopped.")
          Behaviors.stopped
        }
    }
  }

  private def waitRepository(ctx: ActorContext[Message]) = Behaviors.receiveMessage[Message] { message =>
    message match {
      case Found(mcc, Resolve(transaction, replyTo)) =>
        replyTo ! Resolved(mcc, transaction)

      case NotFoundMerchant(Resolve(transaction, replyTo)) =>
        val mcc = try {
          transaction.mcc.toInt
        } catch {
          case _: Throwable => -1
        }
        replyTo ! Resolved(mcc, transaction)

      case FailedMerchant(cause, Resolve(transaction, replyTo)) =>
        replyTo ! Failed(cause, transaction)
    }

    ctx.log.debug("Stopped.")
    Behaviors.stopped
  }

  sealed trait Response

  sealed trait Message
}
