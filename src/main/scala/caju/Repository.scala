package caju

import scala.annotation.tailrec
import scala.collection.immutable.HashMap
import scala.concurrent.Future

object Account {

  type Get = Account => Int

  type Copy = (Int, Account) => Account

  type Category = (Get, Copy)

  val Meal: Category = (_.meal, (total, account) => account.copy(meal = total))

  val Food: Category = (_.food, (total, account) => account.copy(food = total))

  val Culture: Category = (_.culture, (total, account) => account.copy(culture = total))

  val Cash: Category = (_.cash, (total, account) => account.copy(cash = total))

  val Categories: Map[Int, Category] = {
    var map = HashMap.empty[Int, Category]
    map ++= (for (mcc <- 5811 to 5814) yield {
      mcc -> Meal
    })

    map ++ Seq(5411 -> Food, 5815 -> Culture)
  }
}

import caju.Account._

/**
 * Todos os valores aqui são representados em centavos.
 *
 * @param code
 * @param meal
 * @param food
 * @param culture
 * @param cash
 */
case class Account(code: String, meal: Int, food: Int, culture: Int, cash: Int) {

  def minus(amount: Int, mcc: Int): Option[Account] = {
    Categories.get(mcc) match {
      case Some(category) => minus(amount, category, Some(Cash))
      case None => minus(amount, Cash, None)
    }
  }

  @tailrec
  private def minus(amount: Int, category: Category, fallback: Option[Category]): Option[Account] = {
    val (get, copy) = category
    val newTotal = get(this) - amount
    if (newTotal >= 0) {
      Some(copy(newTotal, this))
    } else {
      fallback match {
        case Some(_fallback) => minus(amount, _fallback, None)
        case None => None
      }
    }
  }
}

case class Merchant(name: String, location: String, mcc: Int)

trait MerchantRepository {

  def search(name: String, location: String): Future[Option[Merchant]]
}

trait AccountRepository {

  def get(accountCode: String): Future[Option[Account]]

  def save(account: Account): Future[Account]
}