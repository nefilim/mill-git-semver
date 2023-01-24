package io.github.nefilim.mill.semver.domain

import just.semver.AdditionalInfo
import just.semver.AdditionalInfo.{BuildMetaInfo, PreRelease}
import io.github.nefilim.mill.semver.VersionCalculatorError

object Version {
  case class PreReleaseLabel(label: String) extends AnyVal {
    def toSemVerPreRelease(): Either[VersionCalculatorError, Option[PreRelease]] = {
      if (label.isBlank)
        Right(None)
      else
        AdditionalInfo.parsePreRelease(label).fold(e => Left(VersionCalculatorError.SemVerParse(e)), pr => Right(pr))
    }
  }
  object PreReleaseLabel {
    val empty = PreReleaseLabel("")
  }
  case class BuildMetadataLabel(label: String) extends AnyVal {
    def toSemVerBuildMetadataLabel(): Either[VersionCalculatorError, Option[BuildMetaInfo]] = {
      if (label.isBlank)
        Right(None)
      else
        AdditionalInfo.parseBuildMetaInfo(label).fold(e => Left(VersionCalculatorError.SemVerParse(e)), pr => Right(pr))
    }
  }
  object BuildMetadataLabel {
    val empty = BuildMetadataLabel("")
  }
}
