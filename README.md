# Mill SemVer Plugin

* [Overview](#overview)
* [Version Calculation](#version-calculation)
* [Branch Matching Strategy](#branch-matching-strategy)
* [Usage](#usage)
* [Plugin Extension Properties](#plugin-extension-properties)
* [Plugin Tasks](#plugin-tasks)
* [Using with CI/CD](#using-with-cicd)

## Overview

A Mill plugin with a flexible approach to generating versions, typically for use within a Mill project. It supports running under Github Actions and Jenkins*.

It comes bundled with a single Version Calculator that implements a Git target branch calculator: the version of the current branch is based on the latest version of the branch it targets, eg `develop` is branched from `main`, thus the version of `develop` is based on the current version of `main`.

The `GitTargetBranchVersionCalculator` includes two `VersionCalculatorStrategy`s:
* [Flow](https://github.com/nefilim/mill-git-semver/blob/main/core/src/io/github/nefilim/mill/semver/GitTargetBranchVersionCalculator.scala#L128) - broadly based on a [Git Flow workflow](https://nvie.com/posts/a-successful-git-branching-model/) **without** release branches, the following branches are supported (configurable):

  |branch| pre release label               |target branch| calculated version     |
  |---------------------------------|-----------------|------------------------|-------|
  |`main`|                                 |main| 1.2.3                  |
  |`develop`| beta                            |main| 1.2.4-beta.13          |
  |`feature/mycool_feature`| [derived from the branch name] |develop| 1.2.5-mycool_feature.1 |
  |`hotfix/badthings`| rc                              |main| 1.2.4-rc.2             |

* [Flat](https://github.com/nefilim/mill-git-semver/blob/main/core/src/io/github/nefilim/mill/semver/GitTargetBranchVersionCalculator.scala#L157) - ideal for simpler projects without an integration branch such as `develop`:
  
  |branch|pre release label|target branch|example|
  |------|-----------------|-------------|-------|
  |`main`| |main|1.2.3|
  |`xxx`|[derived from the branch name]|main|1.2.4-xxx.13|

TODO: Custom `VersionCalculatorStrategy` can be configured eg. to match your specific branch naming convention. 
TODO: The `Flow` strategy is automatically selected if a `develop` branch is present, otherwise the `Flat` strategy is selected.

The Calculator can be selected by overriding the `versionCalculatorStrategy` function in your `GitSemVerVersionCalculatorModule`, see [Usage](#usage).

## Version Calculation

The semver is calculated primarily based on:
* the version of the target branch
* the current branch
* the branchMatching strategy

### Branch Matching Strategy

A Strategy contains a list of `BranchMatchingConfiguration` instances which are applied in order until the first match (regex applied to branch name) is reached, it contains the following properties:
* branch name regex
* target branch
* version modifier: modifies the major, minor or patch components of the semver
* version qualifier: optionally qualifies the semver with a prerelease label and build metadata

The `VersionModifier` can be set for every `BranchMatchingConfiguration` instance in the strategy with a property, see [Plugin Extension Properties](#plugin-extension-properties).
## Usage

```scala
object versionCalculator extends GitSemVerVersionCalculatorModule {
  override def versionCalculatorStrategy = flowVersionCalculatorStrategy()

  override val initialVersion: String = "0.0.1"

  // cached calculated version
  def calculatedVersion: Target[String] = T { calculateVersion() }
}
trait MyPublishModule extends ArtifactoryPublishModule {
  def publishVersion = versionCalculator.calculatedVersion()
}
```
Using a custom Strategy:

_TODO_

## Plugin Extension Properties
* `initialVersion: String` specify an initial version for new repositories
* `overrideVersion: Option[String]` specifies a version to use and override the dynamic calculation, defaults to None, use `-D semver.override=1.2.3`
* `versionFileName: String` specifies the filename for writing the version to, defaults to `release.txt`
* `tagPrefix: String` specifies the prefix to apply to the version to construct a tag name, defaults to `v`
* `modifier: String` specifies which component of the SemVer to rev, defaults to `patch`, use `-D semver.modifier=major|minor|patch`

## Plugin Commands
* `calculateVersion()` calculate the current version
* `writeVersionFile()` will generate `./release.txt` containing the raw version, useful for integrating with other GHA steps

## Using with CI/CD

### GitHub Actions

Make sure to check out all branches & tags, eg. with the checkout GHA action by specifying the `fetch-depth: 0`:

      - name: Checkout
        uses: actions/checkout@v3
        with:
          fetch-depth: 0

### Jenkins

Jenkins is a bit more involved and context specific but broadly one needs to:

* git fetch --tags
* git fetch "https://github.com/org/repo" +refs/heads/main:refs/remotes/origin/main (or whatever other missing target branches you might need)
