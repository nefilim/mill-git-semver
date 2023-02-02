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

def verify(): Command[Unit] = T.command {
    val version = versionCalculator.calculateVersion()
    assert(version == InitialVersion)
    ()
}
