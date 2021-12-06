import $ivy.`com.goyeau::mill-scalafix:0.2.5`
import com.goyeau.mill.scalafix.ScalafixModule
import mill._, scalalib._, scalafmt._

val SpinalVersion = "1.6.0"

trait CommonSpinalModule extends ScalaModule with ScalafmtModule with ScalafixModule {
  def scalaVersion = "2.13.6"
  override def scalacOptions = Seq("-unchecked", "-deprecation", "-feature", "-Wunused")
  // def scalaVersion = "2.12.14"
  // override def scalacOptions = Seq("-unchecked", "-deprecation", "-feature")

  override def ivyDeps = Agg(
    ivy"com.github.spinalhdl::spinalhdl-core:$SpinalVersion",
    ivy"com.github.spinalhdl::spinalhdl-lib:$SpinalVersion",
    ivy"com.github.spinalhdl::spinalhdl-sim:$SpinalVersion"
  )

  override def scalacPluginIvyDeps = Agg(ivy"com.github.spinalhdl::spinalhdl-idsl-plugin:$SpinalVersion")

  override def scalafixIvyDeps = Agg(ivy"com.github.liancheng::organize-imports:0.5.0")
}

object rocev2 extends CommonSpinalModule {
  object test extends Tests with TestModule.ScalaTest {
    override def ivyDeps = Agg(ivy"org.scalatest::scalatest:3.2.10")
    // override def testFrameworks = Seq("org.scalatest.tools.Framework")
    override def testFramework = "org.scalatest.tools.Framework"
    def testOnly(args: String*) = T.command {
      super.runMain("org.scalatest.run", args: _*)
    }
  }
}
