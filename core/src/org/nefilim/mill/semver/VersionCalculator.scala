package org.nefilim.mill.semver

import just.semver.{ParseError, SemVer}
import mill.api.Logger
import org.nefilim.mill.semver.VersionCalculatorConfig.{VersionCalculatorStrategy, VersionModifier, VersionQualifier, increasePatch}
import org.nefilim.mill.semver.domain.GitRef
import org.nefilim.mill.semver.domain.Version.{BuildMetadataLabel, PreReleaseLabel}

import scala.util.matching.Regex

sealed trait VersionCalculatorError
object VersionCalculatorError {
  case class Git(t: Throwable) extends VersionCalculatorError
  case class MissingBranchMatchingConfiguration(currentBranch: GitRef.Branch) extends VersionCalculatorError
  case class SemVerParse(e: ParseError) extends VersionCalculatorError
  case class Unexpected(reason: String) extends VersionCalculatorError
}

trait VersionCalculator {
  def calculateVersion(): Either[VersionCalculatorError, SemVer]
}
object VersionCalculator {

  def getTargetBranchVersionCalculator(
    contextProviderOperations: ContextProviderOperations,
    config: VersionCalculatorConfig,
    currentBranch: GitRef.Branch,
  )(implicit logger: Logger): VersionCalculator = new VersionCalculator {

    private def previousVersion(): Either[VersionCalculatorError, SemVer] = {
      config.branchConfiguration(currentBranch.name)
        .fold[Either[VersionCalculatorError, SemVer]] {
          logger.info(s"no match found for $currentBranch in ${config.strategy}, using initial version as previous version")
          Left(VersionCalculatorError.MissingBranchMatchingConfiguration(currentBranch))
        } { bmc =>
          logger.info(s"using BranchMatchingConfiguration: $bmc for previousVersion() with currentBranch $currentBranch")
          contextProviderOperations.branchVersion(currentBranch, bmc.targetBranch).map { v =>
            logger.info(s"branch version for current $currentBranch and target ${bmc.targetBranch}: $v")
            v.getOrElse {
              logger.info(s"no version found for target branch ${bmc.targetBranch}, using initial version")
              config.initialVersion
            }
          }
        }
    }

    private def versionModifier(current: SemVer): SemVer = {
      config.branchConfiguration(currentBranch.name)
        .fold {
          logger.info(s"no match found for $currentBranch in ${config.strategy}, using initial version as modified version")
          config.initialVersion
        } { bmc =>
            logger.info(s"using BranchMatchingConfiguration: $bmc for versionModifier() with currentBranch $currentBranch on current version ${current.render}")
            bmc.versionModifier(current)
        }
    }

    private def versionQualifier(current: SemVer): Either[VersionCalculatorError, SemVer] = {
      config.branchConfiguration(currentBranch.name)
        .fold[Either[VersionCalculatorError, SemVer]] {
          logger.info(s"no match found for $currentBranch in ${config.strategy}")
          Right(current)
        } { bmc =>
          logger.info(s"using BranchMatchingConfiguration: $bmc for versionQualifier() with currentBranch $currentBranch on current version ${current.render}")
          val r = bmc.versionQualifier(contextProviderOperations, currentBranch)
          for {
            pre <- r._1.toSemVerPreRelease()
            meta <- r._2.toSemVerBuildMetadataLabel()
          } yield current.copy(pre = pre, buildMetadata = meta)
        }
    }

    override def calculateVersion(): Either[VersionCalculatorError, SemVer] = {
      previousVersion().flatMap { v =>
        versionQualifier(versionModifier(v))
      }
    }
  }
}

trait ContextProviderOperations {
  def currentBranch()(implicit logger: Logger): Option[GitRef.Branch]

  def branchVersion(
    currentBranch: GitRef.Branch,
    targetBranch: GitRef.Branch
  )(implicit logger: Logger): Either[VersionCalculatorError, Option[SemVer]]

  def commitsSinceBranchPoint(
    currentBranch: GitRef.Branch,
    targetBranch: GitRef.Branch
  )(implicit logger: Logger): Either[VersionCalculatorError, Int]
}

case class BranchMatchingConfiguration(
  regex: Regex,
  targetBranch: GitRef.Branch,
  versionQualifier: VersionQualifier,
  versionModifier: VersionModifier = increasePatch()
)

case class VersionCalculatorConfig(
  strategy: VersionCalculatorStrategy,
  tagPrefix: String = "v",
  initialVersion: SemVer = SemVer(SemVer.major0, SemVer.minor0, SemVer.Patch(1), None, None),
  overrideVersion: Option[SemVer] = None,
) {
  def withBranchMatchingConfig(strategy: VersionCalculatorStrategy): VersionCalculatorConfig =
    this.copy(strategy = strategy)
  def branchConfiguration(branchName: String): Option[BranchMatchingConfiguration] =
    strategy.find(_.regex.matches(branchName))
}
object VersionCalculatorConfig {
  type VersionModifier = SemVer => SemVer
  type VersionQualifier = (ContextProviderOperations, GitRef.Branch) => (PreReleaseLabel, BuildMetadataLabel)
  type VersionCalculatorStrategy = List[BranchMatchingConfiguration]

  val DefaultVersion = SemVer(SemVer.major0, SemVer.Minor(1), SemVer.patch0, None, None)
  val DefaultTagPrefix = "v"

  def increaseMajor(): VersionModifier = { v => SemVer.increaseMajor(v)}
  def increaseMinor(): VersionModifier = { v => SemVer.increaseMinor(v)}
  def increasePatch(): VersionModifier = { v => SemVer.increasePatch(v)}

  def preReleaseWithCommitCount(
    ops: ContextProviderOperations
  )(
    currentBranch: GitRef.Branch,
    targetBranch: GitRef.Branch,
    label: String
  )(implicit logger: Logger): String = {
    ops.commitsSinceBranchPoint(currentBranch, targetBranch).fold[String]({ _ =>
      logger.info(s"Unable to calculate commits since branch point on current $currentBranch")
      label
    }, { v =>
      s"$label.$v"
    })
  }

  def flowVersionCalculatorStrategy(
    versionModifier: VersionModifier = increasePatch()
  )(implicit logger: Logger) = List(
    BranchMatchingConfiguration(
      """^main$""".r,
      GitRef.Branch.Main,
      { (c, b) => (PreReleaseLabel.empty, BuildMetadataLabel.empty) },
      versionModifier
    ),
    BranchMatchingConfiguration(
      """^develop$""".r,
      GitRef.Branch.Main,
      { (ops, b) => (PreReleaseLabel(preReleaseWithCommitCount(ops)(b, GitRef.Branch.Main, "beta")), BuildMetadataLabel.empty) },
      versionModifier
    ),
    BranchMatchingConfiguration(
      """^feature/.*""".r,
      GitRef.Branch.Develop,
      { (ops, b) => (PreReleaseLabel(preReleaseWithCommitCount(ops)(b, GitRef.Branch.Main, b.sanitizedNameWithoutPrefix())), BuildMetadataLabel.empty) },
      versionModifier
    ),
    BranchMatchingConfiguration(
      """^hotfix/.*""".r,
      GitRef.Branch.Main,
      { (ops, b) => (PreReleaseLabel(preReleaseWithCommitCount(ops)(b, GitRef.Branch.Main, "rc")), BuildMetadataLabel.empty) },
      versionModifier
    ),
  )

}
