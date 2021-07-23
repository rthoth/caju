package caju

import spray.json.DefaultJsonProtocol._
import spray.json.RootJsonFormat

package object protocol {

  implicit val transactionJsonFormat: RootJsonFormat[Transaction] = jsonFormat4(Transaction)

  implicit val transactionApprovedFormat: RootJsonFormat[TransactionResponse] = jsonFormat1(TransactionResponse)

  implicit val accountFormat: RootJsonFormat[Account] = jsonFormat5(Account.apply)

  val ApprovedTransaction: TransactionResponse = TransactionResponse("00")

  val RejectedTransaction: TransactionResponse = TransactionResponse("51")

  val FailedTransaction: TransactionResponse = TransactionResponse("07")
}
