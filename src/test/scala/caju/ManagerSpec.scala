package caju

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.ActorRef
import caju.Authorizer.Approved
import caju.protocol.Transaction
import com.typesafe.scalalogging.StrictLogging
import org.scalamock.scalatest.MockFactory

import scala.concurrent.{ExecutionContext, Future}

abstract class ManagerSpec extends ScalaTestWithActorTestKit with MongoService with SpecLike with MockFactory

class BasicManagerSpec extends ManagerSpec with StrictLogging {

  lazy val actor: ActorRef[Manager.Message] = {
    val authorizer = Authorizer(accountRepository, _, 1000, 100.millis)
    val resolver = () => MCCResolver(merchantRepository)

    testKit.spawn(Manager(authorizer, resolver))
  }

  "When a authorizer hit ttl" - {
    "a manager should to remove it" ignore {
      wait(accountRepository.save(Account("0001", 5000, 5000, 5000, 5000)))
      val probe = createTestProbe[Authorizer.Response]
      actor ! Manager.Authorize(Transaction("0001", 10, "5815", "PADARIA 3BROTHERS           SAO PAULO SP"), probe.ref)
      probe.expectMessageType[Authorizer.Approved]
      Thread.sleep(1000)
    }
  }

  "When a valid transaction" - {
    "it should to do fine" in {
      for (i <- 0 until 20) {
        wait(accountRepository.save(Account(s"000000000000$i", 5000, 5000, 5000, 15000)))
      }

      val merchants = Seq(
        "CINECLUB del Toro           SAO PAULO SP",
        "SUPERM MAX                      BELEM PA",
        "PADARIA 2BROTHERS           SAO PAULO SP"
      )

      logger.debug("Starting transactions...")
      val futures = for (x <- 0 until 100; merchant <- merchants; i <- 0 until 20) yield {
        val probe = testKit.createTestProbe[Authorizer.Response]
        actor ! Manager.Authorize(Transaction(s"000000000000$i", 1, "0000", merchant), probe.ref)

        Future({
          probe.expectMessageType[Approved](if (x < 10) 30.seconds else 100.millis)
        })(ExecutionContext.global)
      }

      wait(Future.sequence(futures))

      for (i <- 0 until 20) {
        val Some(Account(_, meal, food, culture, cash)) = wait(accountRepository.get(s"000000000000$i"))
        meal should be(0)
        food should be(0)
        culture should be(0)
        cash should be(0)
      }
    }
  }
}