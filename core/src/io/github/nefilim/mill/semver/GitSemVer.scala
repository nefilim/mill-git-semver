package io.github.nefilim.mill.semver

import io.github.nefilim.mill.semver.GitTargetBranchVersionCalculator.Error.SemVerParse
import io.github.nefilim.mill.semver.GitTargetBranchVersionCalculator.{Context, preReleaseWithCommitCount}
import just.semver.SemVer._
import just.semver.{AdditionalInfo, SemVer}

sealed abstract class SemVerVersionModifier(val id: String) extends VersionModifier[SemVer]

object SemVerVersionModifier {
  case object IncreaseMajor extends SemVerVersionModifier("major") {
    override def modifyVersion(version: SemVer): SemVer = increaseMajor(version).copy(minor = minor0, patch = patch0)
  }
  case object IncreaseMinor extends SemVerVersionModifier("minor") {
    override def modifyVersion(version: SemVer): SemVer = increaseMinor(version).copy(patch = patch0)
  }
  case object IncreasePatch extends SemVerVersionModifier("patch") {
    override def modifyVersion(version: SemVer): SemVer = increasePatch(version)
  }
  def fromID(id: String) = id.trim.toLowerCase match {
    case IncreaseMajor.id => IncreaseMajor
    case IncreaseMinor.id => IncreaseMinor
    case IncreasePatch.id => IncreasePatch
    case _ => throw new IllegalArgumentException(s"unknown version modifier: [${id}]")
  }
}

object GitSemVer {
  val DefaultVersion = SemVer(SemVer.major0, SemVer.Minor(1), SemVer.patch0, None, None)

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
