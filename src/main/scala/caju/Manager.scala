package caju

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior, DispatcherSelector}
import akka.util.Timeout
import caju.protocol.Transaction

import scala.collection.immutable.HashMap
import scala.concurrent.duration._
import scala.util.{Failure, Success}

object Manager {

  type AuthorizerFactory = String => Behavior[Authorizer.Message]

  type ResolverFactory = () => Behavior[MCCResolver.Message]

  final case class Authorize(transaction: Transaction, replyTo: ActorRef[Authorizer.Response]) extends Message

  final case class MCCFailed(cause: Throwable, transaction: Transaction, replyTo: ActorRef[Authorizer.Response]) extends Message

  final case class MCCResolved(mcc: Int, transaction: Transaction, replyTo: ActorRef[Authorizer.Response]) extends Message

  private case class Remove(account: String) extends Message

  def apply(factory: AuthorizerFactory, resolver: ResolverFactory): Behavior[Message] = Behaviors.setup { ctx =>
    new Manager(factory, resolver, ctx).start()
  }

  sealed trait Message
}

import caju.Manager._

class Manager(authorizer: AuthorizerFactory, resolver: ResolverFactory, ctx: ActorContext[Message]) {

  ctx.log.debug("Starting...")

  private val resolveMCCTimeout: Timeout = 25.seconds

  private var authorizers = HashMap.empty[String, ActorRef[Authorizer.Message]]

  //noinspection TypeAnnotation
  def start(): Behavior[Message] = Behaviors.receiveMessage {
    case authorize: Authorize =>
      resolveMCC(authorize)(resolveMCCTimeout)
      Behaviors.same

    case MCCResolved(mcc, transaction, replyTo) =>
      authorize(mcc, transaction, replyTo)
      Behaviors.same

    case MCCFailed(cause, transaction, replyTo) =>
      replyTo ! Authorizer.Failed(cause, transaction)
      Behaviors.same

    case Remove(account) =>
      authorizers -= account
      ctx.log.debug("Removing {}.", account)
      Behaviors.same
  }

  private def authorize(mcc: Int, transaction: Transaction, replyTo: ActorRef[Authorizer.Response]): Unit = try {
    getAuthorizer(transaction.account) ! Authorizer.Authorize(mcc, transaction, replyTo)
  } catch {
    case cause: Throwable =>
      replyTo ! Authorizer.Failed(cause, transaction)
  }

  private def getAuthorizer(account: String): ActorRef[Authorizer.Message] = {
    authorizers.get(account) match {
      case None =>
        val ref = ctx.spawnAnonymous(authorizer(account), DispatcherSelector.fromConfig("caju.dispatcher.authorizer"))
        ctx.watchWith(ref, Remove(account))
        authorizers += account -> ref
        ref
      case Some(ref) =>
        ref
    }
  }

  private def resolveMCC(authorize: Authorize)(implicit timeout: Timeout): Unit = {
    ctx.ask(ctx.spawnAnonymous(resolver(), DispatcherSelector.fromConfig("caju.dispatcher.resolver")), MCCResolver.Resolve(authorize.transaction, _)) {
      case Success(response) => response match {
        case MCCResolver.Resolved(mcc, transaction) => MCCResolved(mcc, transaction, authorize.replyTo)
        case MCCResolver.Failed(cause, _) => MCCFailed(cause, authorize.transaction, authorize.replyTo)
      }
      case Failure(cause) => MCCFailed(cause, authorize.transaction, authorize.replyTo)
    }
  }
}
