package caju

import com.whisk.docker.impl.spotify.DockerKitSpotify
import com.whisk.docker.scalatest.DockerTestKit
import com.whisk.docker.{DockerContainer, DockerReadyChecker}
import org.scalatest.Suite

import scala.concurrent.Future

trait MongoService extends DockerTestKit with DockerKitSpotify {
  self: Suite =>

  val MongoPort = 27017

  val mongoContainer: DockerContainer = DockerContainer("mongo:5.0.0")
    .withPorts(MongoPort -> None)
    .withReadyChecker(DockerReadyChecker.LogLineContains("Waiting for connections"))

  lazy val mongo = new Mongo(s"mongodb://localhost:$mongoPort")

  val NOPMerchantRepo: MerchantRepository = _ => Future.successful(None)

  lazy val accountRepo: MongoAccountRepository = mongo.accountRepository

  lazy val merchantRepo: MongoMerchantRepository = mongo.merchantRepository

  abstract override def dockerContainers: List[DockerContainer] = mongoContainer :: super.dockerContainers

  def mongoPort: Int = mongoContainer.getPorts().futureValue.apply(MongoPort)
}
