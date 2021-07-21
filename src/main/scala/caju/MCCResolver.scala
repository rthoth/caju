package caju

import akka.actor.typed.scaladsl.{Behaviors, PoolRouter, Routers}
import akka.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import caju.protocol.Transaction

import scala.util.{Failure, Success}

object MCCResolver {

  final case class Failed(cause: Throwable, transaction: Transaction) extends Response

  private final case class FailedMerchant(cause: Throwable, resolve: Resolve) extends Message

  private final case class FoundMerchant(merchant: Merchant, resolve: Resolve) extends Message

  private final case class NotFoundMerchant(resolve: Resolve) extends Message

  final case class Resolve(transaction: Transaction, replyTo: ActorRef[Response]) extends Message

  final case class Resolved(mcc: Int, transaction: Transaction) extends Response

  def apply(repository: MerchantRepository): Behavior[Message] = Behaviors.setup { ctx =>

    Behaviors.receiveMessage {
      case resolve@Resolve(transaction@Transaction(_, _, _, merchant), replyTo) =>
        if (merchant.length == 40) {
          val name = merchant.substring(0, 25).trim()
          val location = merchant.substring(25).trim()

          ctx.pipeToSelf(repository.search(name, location)) {
            case Success(Some(merchant)) => FoundMerchant(merchant, resolve)
            case Success(_) => NotFoundMerchant(resolve)
            case Failure(cause) => FailedMerchant(cause, resolve)
          }

          Behaviors.same
        } else {
          replyTo ! Failed(new CajuException.Invalid(s"merchant length = ${merchant.length}!"), transaction)
          Behaviors.same
        }

      case FoundMerchant(merchant, Resolve(transaction, replyTo)) =>
        replyTo ! Resolved(merchant.mcc, transaction)
        Behaviors.same

      case NotFoundMerchant(Resolve(transaction, replyTo)) =>
        val mcc = try {
          transaction.mcc.toInt
        } catch {
          case _: Throwable => -1
        }
        replyTo ! Resolved(mcc, transaction)
        Behaviors.same

      case FailedMerchant(cause, Resolve(transaction, replyTo)) =>
        replyTo ! Failed(cause, transaction)
        Behaviors.same
    }
  }

  def pool(repository: MerchantRepository, poolSize: Int = Runtime.getRuntime.availableProcessors()): PoolRouter[Message] = {
    Routers.pool(poolSize) {
      Behaviors.supervise(apply(repository)).onFailure(SupervisorStrategy.restart)
    }
  }

  sealed trait Response

  sealed trait Message
}
