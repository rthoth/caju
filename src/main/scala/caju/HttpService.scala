package caju

import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{ExceptionHandler, RejectionHandler}
import akka.util.Timeout
import caju.Authorizer.{Approved, Failed, Rejected}
import caju.Manager.Authorize
import caju.protocol._
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

object HttpService {

  type Message = Try[ServerBinding]

  def apply(
    hostname: String,
    port: Int,
    manager: ActorRef[Manager.Message],
    replyTo: ActorRef[Message]
  ): Behavior[Message] = Behaviors.setup { ctx =>

    implicit val system: ActorSystem[_] = ctx.system
    implicit val timeout: Timeout = system.settings.config.getInt("caju.http.timeout").milliseconds
    val accountRepo = Mongo(ctx.system).accountRepository

    val rejectionHandler = RejectionHandler.newBuilder()
      .handle {
        case rejection =>
          extractLog { logger =>
            logger.error("{}", rejection)
            complete(FailedTransaction)
          }
      }
      .result()

    val binding = try {
      Http()
        .newServerAt(hostname, port)
        .bind(
          path("authorize") {
            post {
              handleRejections(rejectionHandler) {
                entity(as[Transaction]) { transaction =>
                  onComplete(manager.ask(Authorize(transaction, _))) {
                    case Success(response) => response match {
                      case _: Approved => complete(ApprovedTransaction)
                      case _: Rejected => complete(RejectedTransaction)
                      case Failed(cause, _) =>
                        extractLog { log =>
                          log.error(cause, "Transaction has failed!")
                          complete(FailedTransaction)
                        }
                    }

                    case Failure(cause) =>
                      extractLog { logger =>
                        logger.error(cause, "Transaction has failed!")
                        complete(FailedTransaction)
                      }
                  }
                }
              }
            }
          } ~ path("account") {
            post {
              entity(as[Account]) { account =>
                onComplete(accountRepo.save(account)) {
                  case Success(savedAccount) => complete(savedAccount)
                  case Failure(cause) =>
                    extractLog { log =>
                      log.error("It was impossible save/update!", cause)
                      complete(StatusCodes.InternalServerError, account)
                    }
                }
              }
            }
          })
    } catch {
      case cause: Throwable => Future.failed(cause)
    }

    ctx.pipeToSelf(binding)(identity)

    Behaviors.receiveMessage { status =>
      replyTo ! status
      if (status.isSuccess) Behaviors.same else Behaviors.stopped
    }
  }
}
