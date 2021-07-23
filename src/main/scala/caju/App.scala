package caju

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import com.typesafe.scalalogging.Logger

import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

object App extends scala.App {

  val system = Try(ActorSystem(Behaviors.setup[Any] { ctx =>

    ctx.log.debug("Starting Caju Authorizer...")
    val system = ctx.system
    val config = system.settings.config

    val accountRepo = Mongo(ctx.system).accountRepository
    val merchanRepo = Mongo(ctx.system).merchantRepository
    val authorizer = Authorizer(accountRepo, merchanRepo, _, 100, 200.millis)

    val manager = ctx.spawn(Manager(authorizer), "manager")

    val httpReplyTo = ctx.spawnAnonymous(Behaviors.receive[HttpService.Message] { (ctx, message) =>
      message match {
        case Success(value) =>
          ctx.log.info("\uD83D\uDCB3 Http Service @ http://{}:{}", value.localAddress.getHostName, value.localAddress.getPort)
          Behaviors.same

        case Failure(cause) =>
          ctx.log.error("\uD83D\uDD25 Http Service failed!", cause)
          system.terminate()
          Behaviors.stopped
      }
    })

    ctx.spawn(HttpService(config.getString("caju.http.hostname"), config.getInt("caju.http.port"), manager, httpReplyTo), "http-service")
    Behaviors.empty
  }, "caju-authorizer"))

  system match {
    case Failure(cause) =>
      Logger(getClass).error("\uD83D\uDD25 Actor System has failed!", cause)

    case _ =>
  }
}
