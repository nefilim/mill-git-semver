package io.github.nefilim.mill.semver

import io.github.nefilim.mill.semver.GitTargetBranchVersionCalculator.Error.SemVerParse
import io.github.nefilim.mill.semver.GitTargetBranchVersionCalculator.{Context, preReleaseWithCommitCount}
import just.semver.SemVer._
import just.semver.{AdditionalInfo, SemVer}

object GitSemVer {
  val DefaultVersion = SemVer(SemVer.major0, SemVer.Minor(1), SemVer.patch0, None, None)

  object IncreaseMajor extends VersionModifier[SemVer]  {
    override def modifyVersion(version: SemVer): SemVer = increaseMajor(version)
  }

  object IncreaseMinor extends VersionModifier[SemVer] {
    override def modifyVersion(version: SemVer): SemVer = increaseMinor(version)
  }

  object IncreasePatch extends VersionModifier[SemVer] {
    override def modifyVersion(version: SemVer): SemVer = increasePatch(version)
  }

  object Qualifiers {
    object Empty extends VersionQualifier[SemVer, Context] {
      override def qualifyVersion(version: SemVer, context: Context): Either[VersionCalculatorError, SemVer] =
        Right(version.copy(pre = None, buildMetadata = None))
    }

    case class PreReleaseWithCommitCount(
      label: Context => String,
    ) extends VersionQualifier[SemVer, Context] {
      override def qualifyVersion(version: SemVer, context: Context): Either[VersionCalculatorError, SemVer] = {
        val preReleaseLabel = preReleaseWithCommitCount(context, label(context))
        (for {
          pr <- AdditionalInfo.parsePreRelease(preReleaseLabel)

        } yield {
          version.copy(
            pre = pr,
            buildMetadata = None
          )
        }) match {
          case Left(e) => Left(SemVerParse(e))
          case Right(v) => Right(v)
        }
      }
    }
  }
}
