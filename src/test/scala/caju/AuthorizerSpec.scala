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
      wait(accountRepo.save(Account("0192837465", 0, 10000, 0, 5000)))
      val replyTo = testKit.createTestProbe[Response]
      val actor = testKit.spawn(Authorizer(accountRepo, NOPMerchantRepo, "0192837465", 10, 100.millis))
      actor ! Authorizer.Authorize(Transaction("0192837465", 100, "5411", merchant = "AAAAAAAA BBBBBBBBB          CCCCCCCCC DD"), replyTo.ref)
      replyTo.expectMessageType[Approved]

      Thread.sleep(3000)
    }
  }

  "When a insufficient CULTURE arrives" - {
    "actor should to decrease from CASH" in {
      wait(accountRepo.save(Account("123456789", meal = 10000, food = 100000, culture = 5000, cash = 30000)))
      val replyTo = testKit.createTestProbe[Response]
      val actor = testKit.spawn(Authorizer(accountRepo, NOPMerchantRepo, "123456789", 10, 100.millis))
      actor ! Authorizer.Authorize(Transaction("123456789", 100, "5815", merchant = "AAAAAAAA BBBBBBBBB          CCCCCCCCC DD"), replyTo.ref)
      replyTo.expectMessageType[Approved]
      wait(accountRepo.get("123456789")) match {
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
      wait(accountRepo.save(Account("987654321", meal = 1000, food = 1000, culture = 1000, cash = 1000)))
      val replyTo = testKit.createTestProbe[Response]
      val actor = testKit.spawn(Authorizer(accountRepo, NOPMerchantRepo, "987654321", 10, 100.millis))
      actor ! Authorizer.Authorize(Transaction("987654321", 10.01, "5411", "AAAAAAAA BBBBBBBBB          CCCCCCCCC DD"), replyTo.ref)
      replyTo.expectMessageType[Rejected]
      wait(accountRepo.get("987654321")) match {
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
      val replyTo = testKit.createTestProbe[Response]
      val actor = testKit.spawn(Authorizer(accountRepo, NOPMerchantRepo, "caju", 10, 1000.millis))
      actor ! Authorizer.Authorize(Transaction("caju", 12.34, "4558", "AAAAAAAA BBBBBBBBB          CCCCCCCCC DD"), replyTo.ref)
      val failed = replyTo.expectMessageType[Failed]
      failed.cause shouldBe a[CajuException.NotFound]
      failed.cause.getMessage shouldBe "caju"
    }
  }

  "When a concurrent transaction occurs" - {
    "actor should to handle too" in {
      wait(accountRepo.save(Account("777", meal = 800, food = 100, culture = 100, cash = 100)))
      val actor = testKit.spawn(Authorizer(accountRepo, NOPMerchantRepo, "777", 10, 100.millis))

      val probes = for (i <- 0 until 10) yield {
        val replyTo = testKit.createTestProbe[Response]
        actor ! Authorizer.Authorize(Transaction("777", 1, "5813", merchant = s"$i-AAAAAA BBBBBBBBB          CCCCCCCCC DD"), replyTo.ref)
        Thread.sleep(5)
        replyTo
      }

      for (probe <- probes.slice(0, 9)) {
        probe.expectMessageType[Approved]
      }

        probes(9).expectMessageType[Rejected]

      wait(accountRepo.get("777")) match {
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
      val accountRepo = mock[AccountRepository]
      (accountRepo.get _).expects("741852963").returns(
        Future.successful(Some(Account("741852963", 1000, 1000, 1000, 1000)))
      )

      (accountRepo.save _).expects(*).returns(
        Future.failed(new IOException("!!!"))
      )

      val replyTo = testKit.createTestProbe[Response]
      val actor = testKit.spawn(Authorizer(accountRepo, NOPMerchantRepo, "741852963", 10, 100.millis))

      actor ! Authorize(Transaction("741852963", 10, "5813", "AAAAAAAA BBBBBBBBB          CCCCCCCCC DD"), replyTo.ref)
      replyTo.expectMessageType[Failed]
    }
  }
}