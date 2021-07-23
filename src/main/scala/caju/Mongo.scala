package caju

import akka.actor.typed.{ActorSystem, Extension, ExtensionId}
import caju.Merchant.Query
import com.typesafe.scalalogging.StrictLogging
import org.bson.codecs.configuration.CodecRegistries.{fromProviders, fromRegistries}
import org.mongodb.scala.MongoClient.DEFAULT_CODEC_REGISTRY
import org.mongodb.scala._
import org.mongodb.scala.bson.codecs.Macros._
import org.mongodb.scala.model.{Filters, FindOneAndReplaceOptions, Indexes}

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}
import scala.io.Source
import scala.concurrent.ExecutionContext.Implicits._

object Mongo extends ExtensionId[Mongo] {

  override def createExtension(system: ActorSystem[_]): Mongo = {
    new Mongo(system.settings.config.getString("caju.mongo.uri"))
  }
}

class Mongo(uri: String) extends Extension {

  private val codecRegistry = fromRegistries(fromProviders(classOf[Account], classOf[Merchant]), DEFAULT_CODEC_REGISTRY)

  private val client = MongoClient(uri)

  private val database = client.getDatabase("caju")

  lazy val accountRepository: MongoAccountRepository = {
    new MongoAccountRepository(
      database.getCollection[Account]("account")
        .withCodecRegistry(codecRegistry)
    )
  }

  lazy val merchantRepository: MongoMerchantRepository = {
    new MongoMerchantRepository(
      database.getCollection[Merchant]("merchant")
        .withCodecRegistry(codecRegistry)
    )
  }
}

class MongoMerchantRepository(collection: MongoCollection[Merchant]) extends MerchantRepository {

  Await.result(collection.createIndex(Indexes.ascending("name")).head(), Duration.Inf)

  if (Await.result(collection.countDocuments().head(), Duration.Inf) == 0) {
    val result = Await.result(collection.insertMany(
      for (line <- Source.fromInputStream(getClass.getClassLoader.getResourceAsStream("merchants")).getLines().toSeq) yield {
        Merchant(line.substring(0, 25).trim, line.substring(25, 40).trim, line.substring(41, 45).toInt)
      }
    ).head(), Duration.Inf)

    require(result.getInsertedIds.size() != 0)
  }

  override def search(query: Query): Future[Option[Int]] = try {
    collection.find(Filters.equal("name", query.name)).map(_.mcc).headOption()
  } catch {
    case cause: Throwable => Future.failed(cause)
  }
}

class MongoAccountRepository(collection: MongoCollection[Account]) extends AccountRepository with StrictLogging {

  Await.result(collection.createIndex(Indexes.hashed("code")).head(), Duration.Inf)

  private val replaceOptions = new FindOneAndReplaceOptions().upsert(true)

  override def get(code: String): Future[Option[Account]] = try {
    collection.find(Filters.equal("code", code)).headOption()
  } catch {
    case cause: Throwable => Future.failed(cause)
  }

  override def save(account: Account): Future[Account] = try {
    collection.findOneAndReplace(Filters.equal("code", account.code), account, replaceOptions).map(_ => account).head()
  } catch {
    case cause: Throwable => Future.failed(cause)
  }
}
