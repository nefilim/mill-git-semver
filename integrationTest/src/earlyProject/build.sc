// mill plugins under test

import $exec.plugins

import mill._
import mill.define.Command
import io.github.nefilim.mill.semver._

val InitialVersion = "0.1.2"

object versionCalculator extends GitSemVerVersionCalculatorModule {
  override def versionCalculatorStrategy = flatVersionCalculatorStrategy()

  override val initialVersion: String = InitialVersion
}

val baseDir = build.millSourcePath

def initGit: T[Unit] = T {
  if (!os.exists(baseDir / ".git")) {
    T.log.info("Initializing git repo...")
      Seq(
        os.proc("git", "config", "--global", "init.defaultBranch", "main"),
        os.proc("git", "init"),
        os.proc("git", "config", "user.email", "test@test.com"),
        os.proc("git", "config", "user.name", "Mill CI"),
        os.proc("git", "add", "build.sc"),
        os.proc("git", "commit", "-m", "first commit"),
      ).foreach(_.call(cwd = baseDir))
  }
  ()
}

def verify(): Command[Unit] = T.command {
  initGit()
  val version = versionCalculator.calculateVersion()
  assert(version == "0.1.3")
  ()
}
