package caju.protocol

import caju.Merchant

case class Transaction(account: String, totalAmount: Double, mcc: String, merchant: String) {

  require(merchant.length == 40, "Invalid merchant!")

  def merchantQuery: Merchant.Query = Merchant.Query(merchant.substring(0, 25).trim, merchant.substring(25).trim)

}

case class TransactionResponse(code: String)