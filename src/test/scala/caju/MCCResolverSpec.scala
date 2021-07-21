package caju

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import caju.MCCResolver.{Failed, Resolve, Resolved, Response}
import caju.protocol.Transaction
import org.scalamock.scalatest.MockFactory

abstract class MCCResolverSpec extends ScalaTestWithActorTestKit with SpecLike with MongoService with MockFactory

class BasicMCCResolverSpec extends MCCResolverSpec {

  "When actor receives a valid transaction" - {
    "it should to resolve" in {
      val probe = testKit.createTestProbe[Response]
      val actor = testKit.spawn(MCCResolver(merchantRepository))

      actor ! Resolve(Transaction("852", 10, "0110", "CINECLUB del Toro           RIB PRETO SP"), probe.ref)
      probe.expectMessageType[Resolved].mcc should be(5815)
    }
  }

  "When actor receives an invalid transaction" - {
    "it should to report" in {
      val probe = testKit.createTestProbe[Response]
      val actor = testKit.spawn(MCCResolver(merchantRepository))
      actor ! Resolve(Transaction("852", 10, "0110", "CINECLUB del Toro         RIB PRETO SP"), probe.ref)
      val response = probe.expectMessageType[Failed]
      response.cause shouldBe a[CajuException.Invalid]
    }
  }

  "When actor receives a valid transaction, but Merchant is unknown" - {
    "it should to keep unsolved" in {
      val probe = testKit.createTestProbe[Response]
      val actor = testKit.spawn(MCCResolver(merchantRepository))
      actor ! Resolve(Transaction("852", 10, "5411", "CINECLUB del Toro           TAC PRETO SP"), probe.ref)
      probe.expectMessageType[Resolved].mcc should be(5411)
    }
  }
}
