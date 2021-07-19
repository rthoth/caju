package caju

import spray.json.DefaultJsonProtocol._
import spray.json.RootJsonFormat

package object protocol {

  implicit val transactionJsonFormat: RootJsonFormat[Transaction] = jsonFormat4(Transaction)

  implicit val transactionApprovedFormat: RootJsonFormat[TransactionResponse] = jsonFormat1(TransactionResponse)

  val ApprovedTranscation: TransactionResponse = TransactionResponse("00")

  val RejectedTransaction: TransactionResponse = TransactionResponse("51")

  val FailedTranscation: TransactionResponse = TransactionResponse("07")
}
