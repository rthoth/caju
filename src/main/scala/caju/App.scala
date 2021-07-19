package caju

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import com.typesafe.scalalogging.StrictLogging

import scala.util.{Failure, Success, Try}

object App extends scala.App with StrictLogging {


  val system = Try(ActorSystem(Behaviors.setup[Any] { ctx =>

    logger.info("Starting Caju Authorizer...")
    val system = ctx.system
    val config = system.settings.config

    val manager = ctx.spawn(CreditCardManager(), "credit-card-manager")

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
      logger.error("\uD83D\uDD25 Actor System has failed!", cause)

    case _ =>
  }
}
