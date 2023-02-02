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

    def calculatedVersion = T { calculateVersion() }
}

def verify(): Command[Unit] = T.command {
    val version = versionCalculator.calculatedVersion()
    assert(version == InitialVersion)
    ()
}
