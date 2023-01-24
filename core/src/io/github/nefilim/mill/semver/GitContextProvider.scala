package io.github.nefilim.mill.semver

import just.semver.SemVer
import mill.api.Logger
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.{ObjectId, Ref}
import org.eclipse.jgit.revwalk.{RevCommit, RevWalk}
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import io.github.nefilim.mill.semver.GitHubActions.{githubActionsBuild, pullRequestEvent, pullRequestHeadRef}
import io.github.nefilim.mill.semver.domain.GitRef

import java.io.File
import scala.jdk.CollectionConverters.IterableHasAsScala
import scala.util.{Failure, Success, Try, Using}

object GitContextProvider {
  type Tags = Map[ObjectId, SemVer]

  def gitRepo(path: String): Git = {
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

  def gitContextProviderOperations(git: Git, config: VersionCalculatorConfig): ContextProviderOperations = {
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
          branchPoint <- headRevInBranch(git, currentBranch)
          commits <- gitCommitsSinceBranchPoint(git, branchPoint, targetBranch, tags)
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

  private[semver] def currentBranchRef(git: Git): Option[String] = {
    if (Jenkins.jenkinsBuild())
      Jenkins.jenkinsBranchShortName()
    else if (githubActionsBuild() && pullRequestEvent()) {
      pullRequestHeadRef().map { r =>
        s"${GitRef.RemoteOrigin}/$r"
      } // why does GITHUB_HEAD_REF not refer to a ref like GITHUB_REF???
    } else
      Option(git.getRepository.getFullBranch)
  }
  
  private[semver] def shortName(name: Option[String]): Either[VersionCalculatorError, String] = {
    name match {
      case Some(n) if n.startsWith(GitRef.RefHead) =>
        Try(n.stripPrefix(GitRef.RefHead + "/")) match {
          case Success(v) => Right(v)
          case Failure(e) => Left(VersionCalculatorError.Git(e))
        }
      case Some(n) if n.startsWith(GitRef.RemoteOrigin) =>
        Try(n.stripPrefix(GitRef.RemoteOrigin + "/")) match {
          case Success(v) => Right(v)
          case Failure(e) => Left(VersionCalculatorError.Git(e))
        }
      case _ => Left(VersionCalculatorError.Unexpected("unable to parse branch Ref: [$it]"))
    } 
  }
  
  private[semver] def gitCommitsSinceBranchPoint(
    git: Git,
    branchPoint: RevCommit,
    branch: GitRef.Branch,
    tags: Tags,
  )(implicit logger: Logger): Either[VersionCalculatorError, Int] = {
    val commits = git.log().call().asScala // can this blow up for large repos?
    val newCommits: List[RevCommit] = commits.takeWhile { c =>
      c.toObjectId != branchPoint.toObjectId && c.getCommitTime > branchPoint.getCommitTime
    }.toList
    if (newCommits.map(_.toObjectId).contains(branchPoint.toObjectId))
      Right(newCommits.size)
    else if (newCommits.size != commits.size) {
      // find latest tag on this branch
      logger.info("Unable to find the branch point [${branchPoint.id.name}: ${branchPoint.shortMessage}] typically happens when commits were squashed & merged and this branch [$branch] has not been rebased yet, using nearest commit with a semver tag, this is just a version estimation")
      findYoungestTagCommitOnBranch(git, branch, tags) match {
        case None =>
          logger.info("failed to find any semver tags on branch [$branch], does main have any version tags? using 0 as commit count since branch point")
          Right(0)
        case Some(youngestTag) =>
          logger.info("youngest tag on this branch is at ${youngestTag.id.name} => ${tags[youngestTag.id]}")
          Right(commits.takeWhile(_.getId != youngestTag.getId).size)
      }
    } else {
        Left(VersionCalculatorError.Unexpected(s"the branch ${branch.refName} did not contain the branch point [${branchPoint.toObjectId}: ${branchPoint.getShortMessage}], have you rebased your current branch?"))
    }
  }

  private def findYoungestTagCommitOnBranch(
    git: Git,
    branch: GitRef.Branch,
    tags: Tags
  )(implicit logger: Logger): Option[RevCommit] = {
    Option(git.getRepository.exactRef(branch.refName)) match {
      case None => 
        logger.info(s"failed to find exact git ref for branch [$branch], aborting...")
        None
      case Some(branchRef) => 
        logger.info(s"pulling log for $branch refName, exactRef: $branchRef")
        git.log()
          .add(branchRef.getObjectId)
          .call()
          .asScala
          .find(t => tags.contains(t.toObjectId))
    }
  }
  
  private[semver] def calculateBaseBranchVersion(
    git: Git,
    targetBranch: GitRef.Branch,
    currentBranch: GitRef.Branch, 
    tags: Tags
  ): Either[VersionCalculatorError, Option[SemVer]] = {
    headRevInBranch(git, currentBranch).map { head =>
      findYoungestTagOnBranchOlderThanTarget(git, targetBranch, head, tags)
    }  
  }
  
  private[semver] def headRevInBranch(git: Git, branch: GitRef.Branch): Either[VersionCalculatorError, RevCommit] = {
    Using(new RevWalk(git.getRepository)) { walk =>
      val revCommit = walk.parseCommit(git.getRepository.findRef(branch.refName).getObjectId)
      walk.dispose()
      revCommit
    } match {
      case Success(v) => Right(v)
      case Failure(e) => Left(VersionCalculatorError.Git(e))
    }
  }

  private[semver] def findYoungestTagOnBranchOlderThanTarget(
    git: Git,
    branch: GitRef.Branch,
    target: RevCommit,
    tags: Tags
  ): Option[SemVer] = {
    Option(git.getRepository.exactRef(branch.refName)) match {
      case None =>
        println(s"failed to find exact git ref for branch [$branch], aborting...")
        None
      case Some(branchRef) =>
        println(s"pulling log for $branch refName, exactRef: ${branchRef}, target: $target")
        git.log()
          .add(branchRef.getObjectId)
          .call()
          .asScala
          .find(t => t.getCommitTime <= target.getCommitTime && tags.contains(t.toObjectId))
          .flatMap(t => tags.get(t.getId))
    }
  }
}
