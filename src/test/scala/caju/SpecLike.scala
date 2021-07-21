package caju

import org.scalatest.freespec.AnyFreeSpecLike
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.{Duration, DurationInt}
import scala.concurrent.{Await, Future}
import scala.language.implicitConversions

trait SpecLike extends AnyFreeSpecLike with Matchers {

  protected implicit def toFiniteDuration(x: Int): DurationInt = DurationInt(x)

  protected def wait[T](future: Future[T]): T = Await.result(future, Duration.Inf)
}
