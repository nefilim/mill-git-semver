package io.github.nefilim.mill.semver

import io.github.nefilim.mill.semver.domain.GitRef
import just.semver.SemVer

class GitRefSuite extends munit.FunSuite {
  test("branch") {
    assertEquals(GitRef.Branch("main", "remotes/origin/main").sanitizedName(), "main")
    assertEquals(GitRef.Branch("test_branch", "remotes/origin/test_branch").sanitizedName(), "test-branch")
    assertEquals(GitRef.Branch("feature/configurable_loginserver", "remotes/origin/main").sanitizedNameWithoutPrefix(), "feature-configurable-loginserver")
    SemVer.parse(GitRef.Branch("feature/configurable_loginserver", "remotes/origin/main").sanitizedNameWithoutPrefix())
  }

}
