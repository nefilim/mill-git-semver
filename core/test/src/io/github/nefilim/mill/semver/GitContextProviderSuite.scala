package io.github.nefilim.mill.semver

import io.github.nefilim.mill.semver.domain.GitRef

class GitContextProviderSuite extends munit.FunSuite {
  test("shortname") {
    assertEquals(GitRef.RefHead, "refs/heads")
    assertEquals(GitRef.RefRemote, "refs/remotes")
    assertEquals(GitRef.RefTags, "refs/tags")
    assertEquals(GitContextProvider.shortName(Some("refs/heads/main")), Right("main"))
    assertEquals(GitContextProvider.shortName(Some("refs/remotes/origin/main")), Right("main"))
  }
}
