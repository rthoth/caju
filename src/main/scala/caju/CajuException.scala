package caju

object CajuException {

  class Full(message: String = null) extends RuntimeException(message)

  class Invalid(message: String) extends RuntimeException(message)

  class NotFound(message: String) extends RuntimeException(message)

  class Timeout(message: String = null) extends RuntimeException(message)
}
