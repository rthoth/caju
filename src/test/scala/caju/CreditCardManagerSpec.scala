package caju

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import caju.CreditCardManager.Authorize
import caju.protocol.Transaction
import org.scalamock.scalatest.MockFactory

abstract class CreditCardManagerSpec extends ScalaTestWithActorTestKit with MongoService with SpecLike with MockFactory

class BasicCreditCardManagerSpec extends CreditCardManagerSpec {

  lazy val actor = {
    val authorizer = CreditCard(accountRepository, _, 10, 100.millis)
    val resolver = testKit.spawn(MCCResolver.pool(merchantRepository, 32), "mcc-resolver")

    testKit.spawn(CreditCardManager(authorizer, resolver))
  }

  "When a valid transaction" - {

    "it should to do fine" in {
      val probe = testKit.createTestProbe[CreditCard.Response]
      for (i <- 0 until 10) {
        wait(accountRepository.save(Account(s"741852963${i}", 10000, 10000, 10000, 10000)))
      }
      for (i <- 0 until 200) {
        actor ! Authorize(Transaction(s"741852963${i % 10}", 1, "5411", "Cafe & Cultura                 MANAUS AM"), probe.ref)
      }

      for (_ <- 0 until 200) {
        probe.expectMessageType[CreditCard.Approved]
      }
    }
  }
}