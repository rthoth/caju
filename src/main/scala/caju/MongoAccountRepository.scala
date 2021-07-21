package caju

import org.bson.codecs.configuration.CodecRegistries.{fromProviders, fromRegistries}
import org.mongodb.scala.MongoClient.DEFAULT_CODEC_REGISTRY
import org.mongodb.scala._
import org.mongodb.scala.bson.codecs.Macros._
import org.mongodb.scala.model.{Filters, FindOneAndReplaceOptions}

import scala.concurrent.Future

class MongoAccountRepository(uri: String) extends AccountRepository {

  private val codecRegistry = fromRegistries(fromProviders(classOf[Account]), DEFAULT_CODEC_REGISTRY)

  private val replaceOptions = new FindOneAndReplaceOptions().upsert(true)

  private val client = MongoClient(uri)

  private val database = client.getDatabase("caju")

  private val collection = database.getCollection[Account]("account").withCodecRegistry(codecRegistry)

  override def get(code: String): Future[Option[Account]] = try {
    collection.find(Filters.equal("code", code)).headOption()
  } catch {
    case cause: Throwable => Future.failed(cause)
  }

  override def save(account: Account): Future[Account] = try {
    collection.findOneAndReplace(Filters.equal("code", account.code), account, replaceOptions).head()
  } catch {
    case cause: Throwable => Future.failed(cause)
  }
}
