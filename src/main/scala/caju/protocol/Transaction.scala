package caju.protocol

case class Transaction(account: String, totalAmount: Double, mcc: String, merchant: String)

case class TransactionResponse(code: String)