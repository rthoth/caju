package caju

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import caju.Authorizer._
import caju.protocol.Transaction
import org.scalamock.scalatest.MockFactory

import java.io.IOException
import scala.concurrent.Future

abstract class AuthorizerSpec extends ScalaTestWithActorTestKit with SpecLike with MongoService with MockFactory

class BasicAuthorizerSpec extends AuthorizerSpec {

  "When a valid FOOD transaction arrives" - {
    "actor should to return ApprovedT" in {
      wait(accountRepository.save(Account("0192837465", 0, 10000, 0, 5000)))
      val probe = testKit.createTestProbe[Response]
      val actor = testKit.spawn(Authorizer(accountRepository, "0192837465", 10, 100.millis))
      actor ! Authorizer.Authorize(5411, Transaction("0192837465", 100, "5411", merchant = "XXX"), probe.ref)
      probe.expectMessageType[Approved]

      Thread.sleep(3000)
    }
  }

  "When a insufficient CULTURE arrives" - {
    "actor should to decrease from CASH" in {
      wait(accountRepository.save(Account("123456789", meal = 10000, food = 100000, culture = 5000, cash = 30000)))
      val probe = testKit.createTestProbe[Response]
      val actor = testKit.spawn(Authorizer(accountRepository, "123456789", 10, 100.millis))
      actor ! Authorizer.Authorize(5815, Transaction("123456789", 100, "5815", merchant = "CINECLUB Stanley Kubrick BELEM PA"), probe.ref)
      probe.expectMessageType[Approved]
      wait(accountRepository.get("123456789")) match {
        case Some(Account(_, meal, food, culture, cash)) =>
          meal should be(10000)
          food should be(100000)
          culture should be(5000)
          cash should be(20000)
      }
    }
  }

  "When a totally insufficient FOOD arrives" - {
    "actor should to return Rejected" in {
      wait(accountRepository.save(Account("987654321", meal = 1000, food = 1000, culture = 1000, cash = 1000)))
      val probe = testKit.createTestProbe[Response]
      val actor = testKit.spawn(Authorizer(accountRepository, "987654321", 10, 100.millis))
      actor ! Authorizer.Authorize(5411, Transaction("987654321", 10.01, "5411", "SUPERMKT YOYO TOKYO JP"), probe.ref)
      probe.expectMessageType[Rejected]
      wait(accountRepository.get("987654321")) match {
        case Some(Account(_, meal, food, culture, cash)) =>
          meal should be(1000)
          food should be(1000)
          culture should be(1000)
          cash should be(1000)
      }
    }
  }

  "When a credit card was not found" - {
    "actor should to return FailedT" in {
      val probe = testKit.createTestProbe[Response]
      val actor = testKit.spawn(Authorizer(accountRepository, "caju", 10, 1000.millis))
      actor ! Authorizer.Authorize(455, Transaction("caju", 12.34, "4558", "PADARIA DO SEU João   CARAGUATATUBA SP"), probe.ref)
      val failed = probe.expectMessageType[Failed]
      failed.cause shouldBe a[CajuException.NotFound]
      failed.cause.getMessage shouldBe "caju"
    }
  }

  "When a concurrent transaction occurs" - {
    "actor should handle too" in {
      wait(accountRepository.save(Account("777", meal = 1000, food = 100, culture = 100, cash = 100)))
      val actor = testKit.spawn(Authorizer(accountRepository, "777", 10, 100.millis))

      val probes = for (i <- 0 until 12) yield {
        val probe = testKit.createTestProbe[Response]
        actor ! Authorizer.Authorize(5813, Transaction("777", 1, "5813", merchant = s"SUPERMKT $i C$i SP"), probe.ref)
        Thread.sleep(5)
        probe
      }

      for (probe <- probes.slice(0, 10)) {
        probe.expectMessageType[Approved]
      }

      probes(10).expectMessageType[Approved]
      probes(11).expectMessageType[Rejected]

      wait(accountRepository.get("777")) match {
        case Some(Account(_, meal, food, culture, cash)) =>
          meal should be(0)
          food should be(100)
          culture should be(100)
          cash should be(0)
      }
    }
  }

  "When an update error occurs" - {
    "actor should to report it fails" in {
      val repository = mock[AccountRepository]
      (repository.get _).expects("741852963").returns(
        Future.successful(Some(Account("741852963", 1000, 1000, 1000, 1000)))
      )

      (repository.save _).expects(*).returns(
        Future.failed(new IOException("!!!"))
      )

      val probe = testKit.createTestProbe[Response]
      val actor = testKit.spawn(Authorizer(repository, "741852963", 10, 100.millis))

      actor ! Authorize(5813, Transaction("741852963", 10, "5813", "YOUTUBE   SP SP"), probe.ref)
      probe.expectMessageType[Failed]
    }
  }
}