package caju

import akka.actor.typed.scaladsl.{AbstractBehavior, ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import caju.protocol.Transaction

object CreditCardManager {

  case class Authorize(transaction: Transaction, replyTo: ActorRef[CreditCard.AuthorizeResponse]) extends Cmd

  def apply(): Behavior[Cmd] = Behaviors.setup(new CreditCardManager(_))

  sealed trait Cmd
}

import caju.CreditCardManager._

class CreditCardManager(ctx: ActorContext[Cmd]) extends AbstractBehavior[Cmd](ctx) {

  override def onMessage(cmd: Cmd): Behavior[Cmd] = cmd match {
    case process: Authorize => processTransaction(process)
  }

  protected def processTransaction(process: Authorize): Behavior[Cmd] = {
    ???
  }
}
