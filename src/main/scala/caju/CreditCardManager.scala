package caju

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import akka.util.Timeout
import caju.protocol.Transaction

import scala.collection.immutable.HashMap
import scala.concurrent.duration._
import scala.util.{Failure, Success}

object CreditCardManager {

  type CreditCardFactory = String => Behavior[CreditCard.Message]

  final case class Authorize(transaction: Transaction, replyTo: ActorRef[CreditCard.Response]) extends Message

  final case class MCCFailed(cause: Throwable, transaction: Transaction, replyTo: ActorRef[CreditCard.Response]) extends Message

  final case class MCCResolved(mcc: Int, transaction: Transaction, replyTo: ActorRef[CreditCard.Response]) extends Message

  private case class Remove(account: String) extends Message

  def apply(creditCard: CreditCardFactory, resolver: ActorRef[MCCResolver.Resolve]): Behavior[Message] = Behaviors.setup { ctx =>
    new CreditCardManager(creditCard, resolver, ctx).start()
  }

  sealed trait Message
}

import caju.CreditCardManager._

class CreditCardManager(creditCard: CreditCardFactory, resolver: ActorRef[MCCResolver.Resolve], ctx: ActorContext[Message]) {

  private val timeoutResolveMCC: Timeout = 25.millis

  private var authorizers = HashMap.empty[String, ActorRef[CreditCard.Message]]

  //noinspection TypeAnnotation
  def start(): Behavior[Message] = Behaviors.receiveMessage {
    case authorize: Authorize =>
      resolveMCC(authorize)(timeoutResolveMCC)
      Behaviors.same

    case MCCResolved(mcc, transaction, replyTo) =>
      authorize(mcc, transaction, replyTo)
      Behaviors.same

    case MCCFailed(cause, transaction, replyTo) =>
      replyTo ! CreditCard.Failed(cause, transaction)
      Behaviors.same

    case Remove(account) =>
      authorizers -= account
      Behaviors.same
  }

  private def authorize(mcc: Int, transaction: Transaction, replyTo: ActorRef[CreditCard.Response]): Unit = try {
    getAuthorizer(transaction.account) ! CreditCard.Authorize(mcc, transaction, replyTo)
  } catch {
    case cause: Throwable =>
      replyTo ! CreditCard.Failed(cause, transaction)
  }

  private def getAuthorizer(account: String): ActorRef[CreditCard.Message] = {
    authorizers.get(account) match {
      case None =>
        val ref = ctx.spawnAnonymous(creditCard(account))
        ctx.watchWith(ref, Remove(account))
        authorizers += account -> ref
        ref
      case Some(ref) =>
        ref
    }
  }

  private def resolveMCC(authorize: Authorize)(implicit timeout: Timeout): Unit = {
    ctx.ask(resolver, MCCResolver.Resolve(authorize.transaction, _)) {
      case Success(response) => response match {
        case MCCResolver.Resolved(mcc, transaction) => MCCResolved(mcc, transaction, authorize.replyTo)
        case MCCResolver.Failed(cause, _) => MCCFailed(cause, authorize.transaction, authorize.replyTo)
      }
      case Failure(cause) => MCCFailed(cause, authorize.transaction, authorize.replyTo)
    }
  }
}
