package org.nefilim.mill.semver

object GitHubActions {
  def githubActionsBuild(): Boolean = Option(System.getenv("GITHUB_ACTIONS")).contains("true")

  def pullRequestEvent(): Boolean = Option(System.getenv("GITHUB_EVENT_NAME")).contains("pull_request")

  def pullRequestHeadRef(): Option[String] = Option(System.getenv("GITHUB_HEAD_REF"))
}
