package caju

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior, DispatcherSelector}
import caju.protocol.Transaction

import scala.collection.immutable.HashMap

object Manager {

  type AuthorizerFactory = String => Behavior[Authorizer.Message]

  type ResolverFactory = () => Behavior[MCCResolver.Message]

  final case class Authorize(transaction: Transaction, replyTo: ActorRef[Authorizer.Response]) extends Message {

    def account: String = transaction.account

  }

  private case class RemoveAuthorizer(account: String) extends Message

  def apply(factory: AuthorizerFactory, resolver: ResolverFactory): Behavior[Message] = Behaviors.setup { ctx =>
    new Manager(factory, resolver, ctx).start()
  }

  sealed trait Message
}

import caju.Manager._

class Manager(authorizer: AuthorizerFactory, resolver: ResolverFactory, ctx: ActorContext[Message]) {

  ctx.log.debug("Starting...")

  private var authorizers = HashMap.empty[String, ActorRef[Authorizer.Message]]

  //noinspection TypeAnnotation
  def start(): Behavior[Message] = Behaviors.receiveMessage {
    case authorize: Authorize =>
      execute(authorize)
      Behaviors.same

    case RemoveAuthorizer(account) =>
      authorizers -= account
      ctx.log.debug("Removing {}.", account)
      Behaviors.same
  }

  private def execute(authorize: Authorize): Unit = {
    val resolver = ctx.spawnAnonymous(this.resolver(), DispatcherSelector.fromConfig("caju.dispatcher.resolver"))
    val authorizer = getAuthorizer(authorize.account)

    resolver ! MCCResolver.Resolve(authorize.transaction, authorizer, authorize.replyTo)
  }

  private def getAuthorizer(account: String): ActorRef[Authorizer.Message] = {
    authorizers.get(account) match {
      case None =>
        val ref = ctx.spawnAnonymous(authorizer(account), DispatcherSelector.fromConfig("caju.dispatcher.authorizer"))
        ctx.watchWith(ref, RemoveAuthorizer(account))
        authorizers += account -> ref
        ref
      case Some(ref) =>
        ref
    }
  }
}
