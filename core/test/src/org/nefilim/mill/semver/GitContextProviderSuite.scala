package org.nefilim.mill.semver

import org.nefilim.mill.semver.domain.GitRef

class GitContextProviderSuite extends munit.FunSuite {
  test("shortname") {
    println(GitRef.RemoteOrigin)
    assertEquals(GitRef.RefHead, "refs/heads")
    assertEquals(GitRef.RefRemote, "refs/remotes")
    assertEquals(GitRef.RefTags, "refs/tags")
    assertEquals(GitContextProvider.shortName(Some("refs/heads/main")), Right("main"))
    assertEquals(GitContextProvider.shortName(Some("refs/remotes/origin/main")), Right("main"))
  }
}
