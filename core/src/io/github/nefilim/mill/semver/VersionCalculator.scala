package io.github.nefilim.mill.semver

trait VersionCalculatorError
object VersionCalculatorError {
  case class Unexpected(reason: String) extends VersionCalculatorError
}

trait VersionCalculator[V, C <: VersionCalculatorConfig[V]] {
  val config: C

  def calculateVersion(): Either[VersionCalculatorError, V]
}

trait ContextualVersionCalculator[V, CF <: VersionCalculatorConfig[V], CT] extends VersionCalculator[V, CF] {
  def context(): CT
}

trait VersionModifier[V] {
  def modifyVersion(version: V): V
}

trait VersionQualifier[V, C] {
  def qualifyVersion(version: V, context: C): Either[VersionCalculatorError, V]
}

trait VersionCalculatorConfig[V] {
  val overrideVersion: Option[V] = None
  val initialVersion: V
}

// calculate the new version based on the previous version
trait BasedOnPreviousVersionCalculator[V, Config <: VersionCalculatorConfig[V], Context]
  extends ContextualVersionCalculator[V, Config, Context]
    with VersionModifier[V]
    with VersionQualifier[V, Context] {

  def previousVersion(): Either[VersionCalculatorError, V]

  def context(): Context

  override def calculateVersion(): Either[VersionCalculatorError, V] = {
    config.overrideVersion match {
      case Some(v) => Right(v)
      case _ =>
        previousVersion().flatMap { v =>
          qualifyVersion(modifyVersion(v), context())
        }
    }
  }
}
