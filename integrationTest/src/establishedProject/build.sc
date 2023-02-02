// mill plugins under test

import $exec.plugins

import mill._
import mill.define.Command
import io.github.nefilim.mill.semver._

val InitialVersion = "0.1.2"

object versionCalculator extends GitSemVerVersionCalculatorModule {
  override def versionCalculatorStrategy =
    GitTargetBranchVersionCalculator.flatVersionCalculatorStrategy()

  override val initialVersion: String = InitialVersion

  def calculatedVersion = T {
    calculateVersion()
  }
}

val baseDir = build.millSourcePath

def initGit: T[Unit] = T {
  if (!os.exists(baseDir / ".git")) {
    T.log.info("Initializing git repo...")
      Seq(
        os.proc("git", "init"),
        os.proc("git", "config", "user.email", "test@test.com"),
        os.proc("git", "config", "user.name", "Mill CI"),
        os.proc("git", "add", "build.sc"),
        os.proc("git", "commit", "-m", "first commit"),
        os.proc("git", "tag", "v0.2.1"),
        os.proc("touch", "file.txt"),
        os.proc("git", "add", "file.txt"),
        os.proc("git", "commit", "-m", "second commit"),
      ).foreach(_.call(cwd = baseDir))
  }
  ()
}

def verify(): Command[Unit] = T.command {
  initGit()
  val version = versionCalculator.calculatedVersion()
  assert(version == "0.2.2")
  ()
}
