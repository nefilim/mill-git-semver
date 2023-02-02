package io.github.nefilim.mill.semver

import io.github.nefilim.mill.semver.GitHubActions.{githubActionsBuild, pullRequestEvent, pullRequestHeadRef}
import io.github.nefilim.mill.semver.GitTargetBranchVersionCalculator.GitVersionCalculatorConfig
import io.github.nefilim.mill.semver.domain.GitRef
import just.semver.SemVer
import mill.api.Logger
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.{ObjectId, Ref}
import org.eclipse.jgit.revwalk.{RevCommit, RevWalk}
import org.eclipse.jgit.storage.file.FileRepositoryBuilder

import java.io.File
import scala.jdk.CollectionConverters.IterableHasAsScala
import scala.util.{Failure, Success, Try, Using}


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

object ContextProviderOperations {
  type Tags = Map[ObjectId, SemVer]

  def gitRepo(): Git = {
    val currentDir = new File("./.git")
    println(currentDir.getCanonicalPath)
    new Git(
      new FileRepositoryBuilder()
        .setGitDir(currentDir)
        .readEnvironment()
        .findGitDir()
        .build()
    )
  }

  def gitContextProviderOperations(
    git: Git,
    config: GitVersionCalculatorConfig,
  )(implicit logger: Logger): ContextProviderOperations = {
    new ContextProviderOperations {
      private val tags = tagMap(git, config.tagPrefix)

      override def currentBranch()(implicit logger: Logger): Option[GitRef.Branch] = {
        currentBranchRef(git).flatMap { ref =>
          shortName(Option(ref)).map(GitRef.Branch(_, ref)).toOption
        }
      }

      override def branchVersion(
        currentBranch: GitRef.Branch,
        targetBranch: GitRef.Branch
      )(implicit logger: Logger): Either[VersionCalculatorError, Option[SemVer]] =
        calculateBaseBranchVersion(git, targetBranch, currentBranch, tags)

      override def commitsSinceBranchPoint(
        currentBranch: GitRef.Branch,
        targetBranch: GitRef.Branch
      )(implicit logger: Logger): Either[VersionCalculatorError, Int] = {
        for {
          branchPoint <- headRevInBranch(git, targetBranch)
          commits <- gitCommitsSinceBranchPoint(git, branchPoint, currentBranch, tags)
        } yield commits
      }
    }
  }

  private[semver] def tagMap(git: Git, prefix: String): Tags = {
    val versionTags = git.tagList().call().asScala.flatMap { ref =>
        semverTag(ref, prefix).flatMap { sv =>
          // have to unpeel annotated tags
          Option(git.getRepository.getRefDatabase.peel(ref).getPeeledObjectId)
            .orElse(Option(ref.getObjectId))
            .map(o => (o, sv))
        }
    }
    versionTags.toMap
  }

  private[semver] def semverTag(ref: Ref, prefix: String): Option[SemVer] = {
    for {
      t <- Option(ref.getName)
      t2 <- Try(t.stripPrefix(s"${GitRef.RefTags}/$prefix")).toOption
      sv <- if (!t2.isBlank && t2.count(_ == '.') == 2) SemVer.parse(t2).toOption else None
    } yield sv
  }

  private[semver] def currentBranchRef(git: Git)(implicit logger: Logger): Option[String] = {
    if (Jenkins.jenkinsBuild()) {
      val cb = Jenkins.jenkinsBranchShortName()
      logger.info(s"Found Jenkins CI environment, current branch [$cb]")
      cb
    } else if (githubActionsBuild() && pullRequestEvent()) {
      val cb = pullRequestHeadRef().map { r =>
        s"${GitRef.RemoteOrigin}/$r"
      } // why does GITHUB_HEAD_REF not refer to a ref like GITHUB_REF???
      logger.info(s"Found GitHub Actions CI environment, current branch [$cb]")
      cb
    } else {
      logger.info(s"No CI environment found, using full branch as current branch: [${git.getRepository.getFullBranch}]")
      Option(git.getRepository.getFullBranch)
    }
  }
  
  private[semver] def shortName(name: Option[String]): Either[VersionCalculatorError, String] = {
    name match {
      case Some(n) if n.startsWith(GitRef.RefHead) =>
        Try(n.stripPrefix(GitRef.RefHead + "/")) match {
          case Success(v) => Right(v)
          case Failure(e) => Left(GitTargetBranchVersionCalculator.Error.Git(e))
        }
      case Some(n) if n.startsWith(GitRef.RemoteOrigin) =>
        Try(n.stripPrefix(GitRef.RemoteOrigin + "/")) match {
          case Success(v) => Right(v)
          case Failure(e) => Left(GitTargetBranchVersionCalculator.Error.Git(e))
        }
      case Some(n) => Right(n)
      case _ => Left(VersionCalculatorError.Unexpected("unable to parse branch Ref: [$it]"))
    }
  }
  
  private[semver] def gitCommitsSinceBranchPoint(
    git: Git,
    branchPoint: RevCommit,
    branch: GitRef.Branch,
    tags: Tags,
  )(implicit logger: Logger): Either[VersionCalculatorError, Int] = {
    logger.debug(s"finding commits on branch [$branch] since branch point [${branchPoint}]")
    // can this blow up for large repos?
    val allCommitsOnThisBranch = git.log().call().asScala.toList.sortBy(-1 * _.getCommitTime) // .toList is CRITICAL here otherwise a mutable structure is returned that is emptied before you know it!
    logger.debug(s"all commits: ${allCommitsOnThisBranch.mkString("\n")}")
    val newCommitsSinceBranchPoint: List[RevCommit] = allCommitsOnThisBranch.takeWhile { c =>
      c.toObjectId != branchPoint.toObjectId && c.getCommitTime > branchPoint.getCommitTime
    }
    logger.debug(s"new commits since branchPoint: [${newCommitsSinceBranchPoint.mkString("\n")}]")

    // we use the allCommits since we stopped building the newCommits list when we get the branchPoint potentially
    if (allCommitsOnThisBranch.map(_.toObjectId).contains(branchPoint.toObjectId))
      Right(newCommitsSinceBranchPoint.size)
    else if (newCommitsSinceBranchPoint.size != allCommitsOnThisBranch.size) {
      // find latest tag on this branch
      logger.info(s"Unable to find the branch point [${branchPoint.getId.name}: ${branchPoint.getShortMessage}] typically happens when commits were squashed & merged and this branch [$branch] has not been rebased yet, using nearest commit with a semver tag, this is just a version estimation")
      mostRecentTagRevCommitOnBranch(git, branch, tags) match {
        case None =>
          logger.info(s"failed to find any semver tags on branch [$branch], does main have any version tags? using 0 as commit count since branch point")
          Right(0)
        case Some(mostRecentTag) =>
          Right(allCommitsOnThisBranch.takeWhile(_.getId != mostRecentTag.getId).size)
      }
    } else {
        Left(VersionCalculatorError.Unexpected(s"the branch ${branch.refName} did not contain the branch point [${branchPoint.toObjectId}: ${branchPoint.getShortMessage}], have you rebased your current branch?"))
    }
  }

  private def mostRecentTagRevCommitOnBranch(
    git: Git,
    branch: GitRef.Branch,
    tags: Tags
  )(implicit logger: Logger): Option[RevCommit] = {
    Option(git.getRepository.exactRef(branch.refName)) match {
      case None => None
      case Some(branchRef) => 
        logger.debug(s"pulling log for $branch refName, exactRef: $branchRef")
        git.log()
          .add(branchRef.getObjectId)
          .call()
          .asScala
          .toList
          .find(t => tags.contains(t.toObjectId))
    }
  }
  
  private[semver] def calculateBaseBranchVersion(
    git: Git,
    targetBranch: GitRef.Branch,
    currentBranch: GitRef.Branch, 
    tags: Tags
  )(implicit logger: Logger): Either[VersionCalculatorError, Option[SemVer]] = {
    headRevInBranch(git, currentBranch).map { head =>
      findYoungestTagOnBranchOlderThanTarget(git, targetBranch, head, tags)
    }  
  }
  
  private[semver] def headRevInBranch(git: Git, branch: GitRef.Branch): Either[VersionCalculatorError, RevCommit] = {
    if (git.getRepository.findRef(branch.refName) == null) { // probably in detached head state in jenkins
      Try(git.log().setMaxCount(1).call().asScala.toList.head) match {
        case Success(v) => Right(v)
        case Failure(e) => Left(GitTargetBranchVersionCalculator.Error.Git(e))
      }
    } else
      Using(new RevWalk(git.getRepository)) { walk =>
        val revCommit = walk.parseCommit(git.getRepository.findRef(branch.refName).getObjectId)
        walk.dispose()
        revCommit
      } match {
        case Success(v) => Right(v)
        case Failure(e) => Left(GitTargetBranchVersionCalculator.Error.Git(e))
      }
  }

  private[semver] def findYoungestTagOnBranchOlderThanTarget(
    git: Git,
    branch: GitRef.Branch,
    target: RevCommit,
    tags: Tags
  )(implicit logger: Logger): Option[SemVer] = {
    Option(git.getRepository.exactRef(branch.refName)) match {
      case None =>
        logger.info(s"failed to find exact git ref for branch [$branch], returning None...")
        None
      case Some(branchRef) =>
        logger.debug(s"pulling log for $branch refName, exactRef: ${branchRef}, target: $target")
        git.log()
          .add(branchRef.getObjectId)
          .call()
          .asScala
          .toList
          .find(t => t.getCommitTime <= target.getCommitTime && tags.contains(t.toObjectId))
          .flatMap(t => tags.get(t.getId))
    }
  }
}
