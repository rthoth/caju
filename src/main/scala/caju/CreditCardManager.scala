package caju

import akka.actor.typed.scaladsl.{AbstractBehavior, ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import caju.protocol.Transaction

object CreditCardManager {

  case object ApprovedT extends TransactionResponse

  case object RejectedT extends TransactionResponse

  final case class FailedT(cause: Throwable) extends TransactionResponse

  case class ProcessTransaction(transaction: Transaction, replyTo: ActorRef[TransactionResponse]) extends Cmd

  def apply(): Behavior[Cmd] = Behaviors.setup(new CreditCardManager(_))

  sealed trait TransactionResponse

  sealed trait Cmd
}

import caju.CreditCardManager._

class CreditCardManager(ctx: ActorContext[Cmd]) extends AbstractBehavior[Cmd](ctx) {

  override def onMessage(cmd: Cmd): Behavior[Cmd] = cmd match {
    case process: ProcessTransaction => processTransaction(process)
  }

  protected def processTransaction(process: ProcessTransaction): Behavior[Cmd] = {
    ???
  }
}
