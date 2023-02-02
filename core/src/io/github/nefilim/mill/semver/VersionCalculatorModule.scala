package io.github.nefilim.mill.semver

import io.github.nefilim.mill.semver.GitTargetBranchVersionCalculator.{GitVersionCalculatorConfig, VersionCalculatorStrategy, flowVersionCalculatorStrategy}
import just.semver.SemVer
import just.semver.SemVer.render
import mill.T
import mill.api.Logger
import mill.define.{Input, Module}
import org.eclipse.jgit.api.Git
import upickle.default._

trait VersionCalculatorModule extends Module {
  val initialVersion: String = "0.0.1"
  val overrideVersion: Option[String] = None

  def calculateVersion: Input[String]
}

trait GitSemVerVersionCalculatorModule extends VersionCalculatorModule {
  private val git: Git = ContextProviderOperations.gitRepo()

  implicit val jsonify: upickle.default.ReadWriter[SemVer] = readwriter[ujson.Value].bimap[SemVer](
    t => ujson.Str(t.render),
    json => SemVer.parse(json(0).str) match {
      case Right(v) => v
      case Left(e) => throw new IllegalArgumentException(s"$e")
    }
  )

  def versionCalculatorStrategy(): VersionCalculatorStrategy = flowVersionCalculatorStrategy()

  def flatVersionCalculatorStrategy() =
    GitTargetBranchVersionCalculator.flatVersionCalculatorStrategy(SemVerVersionModifier.fromID(modifier))

  def flowVersionCalculatorStrategy() =
    GitTargetBranchVersionCalculator.flowVersionCalculatorStrategy(SemVerVersionModifier.fromID(modifier))

  def modifier: String = System.getProperty("semver.modifier", SemVerVersionModifier.IncreasePatch.id)

  val tagPrefix: String = "v"

  override def calculateVersion: Input[String] = T.input {
    implicit val logger: Logger = T.log
    val initialSemVer = SemVer
      .parse(initialVersion)
      .getOrElse(throw new IllegalArgumentException(s"invalid initial SemVer: $initialVersion"))
    val overrideSemVer = overrideVersion.map { ov =>
      SemVer
        .parse(ov)
        .getOrElse(throw new IllegalArgumentException(s"invalid override SemVer: $ov"))
    }
    val config = GitVersionCalculatorConfig(versionCalculatorStrategy(), tagPrefix, initialSemVer, overrideSemVer)
    val ops = ContextProviderOperations.gitContextProviderOperations(git, config)
    val head = git.getRepository.resolve(org.eclipse.jgit.lib.Constants.HEAD)

    ops.currentBranch().map { currentBranch =>
      val calculator = GitTargetBranchVersionCalculator(ops, config, currentBranch)
      calculator.calculateVersion() match {
        case Left(e) =>
          throw new Exception(s"failed to calculate version: $e")
        case Right(v) =>
          v
      }
    } match {
      case None =>
        // we don't fail in this scenario as it's common for new projects that hasn't been git initialized yet
        logger.error(s"unable to determine current branch, defaulting to version ${render(config.initialVersion)}")
        config.initialVersion.render
      case Some(v) =>
        v.render
    }
  }
}
