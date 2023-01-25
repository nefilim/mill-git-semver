package io.github.nefilim.mill.semver

import io.github.nefilim.mill.semver.VersionCalculatorConfig.VersionCalculatorStrategy
import just.semver.SemVer
import just.semver.SemVer.render
import mill.T
import mill.api.Logger
import mill.define.{Input, Module}
import upickle.default._

trait VersionCalculatorModule extends Module {
  val git = GitContextProvider.gitRepo(".")

  implicit val jsonify: upickle.default.ReadWriter[SemVer] = readwriter[ujson.Value].bimap[SemVer](
    t => ujson.Str(t.render),
    json => SemVer.parse(json(0).str) match {
      case Right(v) => v
      case Left(e) => throw new IllegalArgumentException(s"$e")
    }
  )

  def versionCalculatorStrategy(): VersionCalculatorStrategy = VersionCalculatorConfig.flatVersionCalculatorStrategy()

  def calculateVersion: Input[String] = T.input {
    implicit val logger: Logger = T.log
    val config = VersionCalculatorConfig(versionCalculatorStrategy())
    val ops = GitContextProvider.gitContextProviderOperations(git, config)
    val head = git.getRepository.resolve(org.eclipse.jgit.lib.Constants.HEAD)
    // TODO add override version support
    ops.currentBranch().map { currentBranch =>
      val calculator = VersionCalculator.getTargetBranchVersionCalculator(ops, config, currentBranch)
      calculator.calculateVersion() match {
        case Left(e) =>
          throw new Exception(s"failed to calculate version: $e")
        case Right(v) =>
          v
      }
    } match {
      case None =>
        logger.error(s"unable to determine current branch, defaulting to version ${render(VersionCalculatorConfig.DefaultVersion)}")
        VersionCalculatorConfig.DefaultVersion.render
      case Some(v) =>
        v.render
    }
  }
}
