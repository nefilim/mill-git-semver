import mill._
import mill.define.Target
import mill.scalalib._
import mill.scalalib.publish._
import $ivy.`de.tototec::de.tobiasroeser.mill.integrationtest::0.6.1`
import de.tobiasroeser.mill.integrationtest._
import $ivy.`io.chris-kipp::mill-ci-release::0.1.5`
import io.kipp.mill.ci.release.CiReleaseModule
import io.kipp.mill.ci.release.SonatypeHost
import os.Path

object Versions {
  val EclipseJGit = "6.4.0.202211300538-r"
  val JustSemVer = "0.6.0"
  val Munit = "0.7.29"
}

trait MillPlatformDependencies {
  def millPlatform: String
  def millVersion: String
  def scalaVersion: String

  val millMain = ivy"com.lihaoyi::mill-main:$millVersion"
  val munit = ivy"org.scalameta::munit::${Versions.Munit}"

  val eclipseJGit =ivy"org.eclipse.jgit:org.eclipse.jgit:${Versions.EclipseJGit}"
  val eclipseJGitSSH =ivy"org.eclipse.jgit:org.eclipse.jgit.ssh.apache:${Versions.EclipseJGit}"
  val justSemVer = ivy"io.kevinlee::just-semver:${Versions.JustSemVer}"
}

object Dependencies {
  object v0_11 extends MillPlatformDependencies {
    override def millPlatform = millVersion // only valid for exact milestones!
    override def millVersion = "0.11.0-M3"
    override def scalaVersion = "2.13.10"
  }

  object v0_10 extends MillPlatformDependencies {
    override def millPlatform = "0.10"
    override def millVersion = "0.10.10" // scala-steward:off
    override def scalaVersion = "2.13.10"
  }
}
val crossCompileDependencies = Seq(Dependencies.v0_10, Dependencies.v0_11)
val millAPIVersions = crossCompileDependencies.groupBy(_.millPlatform)

trait SemverPluginBaseModule extends CrossScalaModule with CiReleaseModule {
  def millAPIVersion: String
  def dependencies: MillPlatformDependencies = millAPIVersions.apply(millAPIVersion).head
  def crossScalaVersion = dependencies.scalaVersion

  override def artifactSuffix: T[String] = s"_mill${dependencies.millPlatform}_${artifactScalaVersion()}"

  override def ivyDeps = T {
    Agg(
      ivy"${scalaOrganization()}:scala-library:${scalaVersion()}",
      dependencies.eclipseJGit,
      dependencies.eclipseJGitSSH,
      dependencies.justSemVer,
    )
  }

  override def versionScheme: T[Option[VersionScheme]] = T(Option(VersionScheme.EarlySemVer))

  override def javacOptions = Seq("-source", "1.8", "-target", "1.8", "-encoding", "UTF-8")
  override def scalacOptions = Seq("-target:jvm-1.8", "-encoding", "UTF-8")

  override def sonatypeHost = Some(SonatypeHost.s01)
  def pomSettings = T {
    PomSettings(
      description = "Mill plugin to derive a version from (last) git tag and edit state",
      organization = "io.github.nefilim.mill",
      url = "https://github.com/nefilim/mill-git-semver",
      licenses = Seq(License.`Apache-2.0`),
      versionControl = VersionControl.github("nefilim", "mill-git-semver"),
      developers = Seq(Developer("nefilim", "Peter vR", "https.//github.com/nefilim"))
    )
  }
}

object core extends Cross[CoreCross](millAPIVersions.keys.toSeq: _*)
class CoreCross(override val millAPIVersion: String) extends SemverPluginBaseModule {
  override def artifactName = "mill-git-semver"

  // don't generate IntelliJ configuration if ...
  override def skipIdea: Boolean = dependencies != crossCompileDependencies.head

  override def compileIvyDeps = Agg(
    dependencies.millMain,
  )

  object test extends Tests with TestModule.Munit {
    override def ivyDeps = Agg(
      dependencies.munit,
      dependencies.millMain
    )
  }
}

val millIntegrationTestVersions = Seq("0.10.10")
object integrationTest extends Cross[IntegrationTestCross](millIntegrationTestVersions: _*)
class IntegrationTestCross(millIntegrationTestVersion: String) extends MillIntegrationTestModule {
  val millApiVersion = millIntegrationTestVersion.split("[.]").take(2).mkString(".")

  override def millSourcePath: Path = super.millSourcePath / os.up // otherwise it expects a separate source tree per version
  override def millTestVersion = millIntegrationTestVersion
  override def pluginsUnderTest = Seq(core(millApiVersion))

  val testcaseCount = testCases.map(s => s.length)

  override def testInvocations: Target[Seq[(PathRef, Seq[TestInvocation.Targets])]] = T {
    testCases().map { pathref =>
      pathref.path.last match {
        case "establishedProject" =>
          pathref -> Seq(TestInvocation.Targets(Seq("-d", "-D", "semver.modifier=minor", "verify")))
        case _ =>
          pathref -> Seq(TestInvocation.Targets(Seq("-d", "verify")))
      }
    }
  }
}
