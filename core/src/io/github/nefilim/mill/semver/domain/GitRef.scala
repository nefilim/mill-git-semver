package io.github.nefilim.mill.semver.domain

import org.eclipse.jgit.lib.{Constants, ObjectId, Repository}

sealed trait GitRef
object GitRef {
  val RefHead = Constants.R_HEADS.stripSuffix("/")
  val RefRemote = Constants.R_REMOTES.stripSuffix("/")
  val RefTags = Constants.R_TAGS.stripSuffix("/")
  val RemoteOrigin = s"$RefRemote/${Constants.DEFAULT_REMOTE_NAME}"
  val ValidCharacters = """[^0-9A-Za-z\-_.]+"""
  val ValidCharactersRegex = ValidCharacters.r

  case class Branch(
    name: String,
    refName: String,
  ) extends GitRef {
    def headCommitID(repo: Repository, refName: String): ObjectId = repo.findRef(refName).getObjectId

    def sanitizedName(): String =
      name.trim().toLowerCase().replaceAll(ValidCharacters, "-")

    def sanitizedNameWithoutPrefix(): String = {
      val n = sanitizedName()
      n.drop(n.indexOf("/"))
    }
  }


  object Branch {
    def apply(name: String) = new Branch(name, s"$RefHead/$name")

    val Main = Branch("main", s"$RemoteOrigin/main")
    val Master = Branch("master", s"$RemoteOrigin/master")
    val Develop = Branch("develop", s"$RemoteOrigin/develop")

  }
}
