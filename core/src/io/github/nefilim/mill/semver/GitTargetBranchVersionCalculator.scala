package io.github.nefilim.mill.semver

import io.github.nefilim.mill.semver.domain.GitRef
import just.semver.{ParseError, SemVer}
import mill.api.Logger

import scala.util.matching.Regex

object GitTargetBranchVersionCalculator {
  case class Context(
    ops: ContextProviderOperations,
    currentBranch: GitRef.Branch,
    branchMatchingConfiguration: BranchMatchingConfiguration,
    logger: Logger,
  ) {
    override def toString: String =
      s"""
        |Context
        | ops: $ops
        | current branch: $currentBranch
        | branchMatchingConfiguration: $branchMatchingConfiguration
        |""".stripMargin
  }

  sealed trait Error extends VersionCalculatorError
  object Error {
    case class Git(t: Throwable) extends Error
    case object NoCurrentBranch extends Error
    case class MissingBranchMatchingConfiguration(currentBranch: GitRef.Branch) extends Error
    case class SemVerParse(e: ParseError) extends Error
  }

  def apply(
    contextProviderOperations: ContextProviderOperations,
    gitConfig: GitVersionCalculatorConfig,
    currentBranch: GitRef.Branch,
  )(implicit logger: Logger): VersionCalculator[SemVer, GitVersionCalculatorConfig] =
    new BasedOnPreviousVersionCalculator[SemVer, GitVersionCalculatorConfig, Context] {
      import Error._

      override val config: GitVersionCalculatorConfig = gitConfig

      override lazy val context: Context = {
        val c = (for {
          bmc <- config.branchConfiguration(currentBranch.name).toRight[Error](MissingBranchMatchingConfiguration(currentBranch))
        } yield Context(contextProviderOperations, currentBranch, bmc, logger))
          .fold[Context](e => throw new IllegalArgumentException(e.toString), c => c)
        logger.debug(s"GitTargetBranchVersionCalculator.Context: $c")
        c
      }

      override def previousVersion(): Either[VersionCalculatorError, SemVer] = {
        val bmc = context.branchMatchingConfiguration
        contextProviderOperations.branchVersion(currentBranch, bmc.targetBranch).map { v =>
          logger.debug(s"previousVersion() | branch version for current $currentBranch and target ${bmc.targetBranch}: $v")
          v.getOrElse {
            logger.info(s"previousVersion() | no version found for target branch ${bmc.targetBranch}, using initial version")
            config.initialVersion
          }
        }
      }

      override def modifyVersion(current: SemVer): SemVer = {
        config.branchConfiguration(currentBranch.name)
          .fold {
            logger.info(s"no match found for $currentBranch in ${config.strategy}, using initial version as modified version")
            config.initialVersion
          } { bmc =>
            logger.debug(s"modifyVersion() | modifying $current")
            bmc.versionModifier.modifyVersion(current)
          }
      }

      override def qualifyVersion(current: SemVer, context: Context = context): Either[VersionCalculatorError, SemVer] = {
        for {
          bc <- config.branchConfiguration(currentBranch.name)
            .toRight[VersionCalculatorError](MissingBranchMatchingConfiguration(currentBranch))
          _ = logger.debug(s"qualifyVersion() | qualifying $current")
          qualifiedVersion <-  bc.versionQualifier.qualifyVersion(current, context)
        } yield qualifiedVersion
      }
  }

  def preReleaseWithCommitCount(
    context: Context,
    label: String,
  ): String = {
    val targetBranch = context.branchMatchingConfiguration.targetBranch
    context.ops.commitsSinceBranchPoint(context.currentBranch, targetBranch)(context.logger).fold[String]({ _ =>
      context.logger.info(s"Unable to calculate commits since branch point on current ${context.currentBranch}")
      label
    }, { v =>
      s"$label.$v"
    })
  }

  case class BranchMatchingConfiguration(
    regex: Regex,
    targetBranch: GitRef.Branch,
    versionQualifier: VersionQualifier[SemVer, Context],
    versionModifier: VersionModifier[SemVer],
  ) {
    override def toString: String =
      s"""
        |BranchMatchinConfiguration:
        | regex: $regex,
        | target branch: $targetBranch,
        | version qualifier: $versionQualifier,
        | version modifier: $versionModifier
        |""".stripMargin
  }

  type VersionCalculatorStrategy = List[BranchMatchingConfiguration]

  case class GitVersionCalculatorConfig(
    strategy: VersionCalculatorStrategy,
    tagPrefix: String,
    initialVersion: SemVer,
    override val overrideVersion: Option[SemVer] = None,
  ) extends VersionCalculatorConfig[SemVer] {
    def withBranchMatchingConfig(strategy: VersionCalculatorStrategy): VersionCalculatorConfig[SemVer] =
      this.copy(strategy = strategy)

    def branchConfiguration(branchName: String): Option[BranchMatchingConfiguration] =
      strategy.find(_.regex.matches(branchName))
  }

  def flowVersionCalculatorStrategy(
    versionModifier: VersionModifier[SemVer]
  ): VersionCalculatorStrategy = List(
    BranchMatchingConfiguration(
      """^main$""".r,
      GitRef.Branch.Main,
      GitSemVer.Qualifiers.Empty,
      versionModifier
    ),
    BranchMatchingConfiguration(
      """^develop$""".r,
      GitRef.Branch.Main,
      GitSemVer.Qualifiers.PreReleaseWithCommitCount(_ => "beta"),
      versionModifier
    ),
    BranchMatchingConfiguration(
      """^feature/.*""".r,
      GitRef.Branch.Develop,
      GitSemVer.Qualifiers.PreReleaseWithCommitCount(c => c.currentBranch.sanitizedNameWithoutPrefix()),
      versionModifier
    ),
    BranchMatchingConfiguration(
      """^hotfix/.*""".r,
      GitRef.Branch.Main,
      GitSemVer.Qualifiers.PreReleaseWithCommitCount(_ => "rc"),
      versionModifier
    ),
  )

  def flatVersionCalculatorStrategy(
    versionModifier: VersionModifier[SemVer]
  ): VersionCalculatorStrategy = List(
    BranchMatchingConfiguration(
      """^main$""".r,
      GitRef.Branch.Main,
      GitSemVer.Qualifiers.Empty,
      versionModifier
    ),
    BranchMatchingConfiguration(
      """.*""".r,
      GitRef.Branch.Main,
      GitSemVer.Qualifiers.PreReleaseWithCommitCount(c => c.currentBranch.sanitizedNameWithoutPrefix()),
      versionModifier
    ),
  )
}
