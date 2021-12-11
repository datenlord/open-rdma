import $ivy.`com.goyeau::mill-scalafix:0.2.5`
import com.goyeau.mill.scalafix.ScalafixModule
import mill._, scalalib._, scalafmt._

val spinalVersion = "1.6.0"
val scalaTestVersion = "3.2.10"

trait CommonSpinalModule extends ScalaModule with ScalafmtModule with ScalafixModule {
  def scalaVersion = "2.13.6"
  override def scalacOptions = Seq(
    "-feature",
    "-deprecation",
    "-unchecked",
    "-Werror",
    "-Wunused:imports,patvars,privates,locals,explicits,implicits,params,linted",
    "-Xlint:adapted-args",
    "-Xlint:nullary-unit",
    "-Xlint:inaccessible",
    // "-Xlint:nullary-override", not a valid choice for -Xlint
    "-Xlint:infer-any",
    "-Xlint:missing-interpolator",
    "-Xlint:doc-detached",
    "-Xlint:private-shadow",
    "-Xlint:type-parameter-shadow",
    "-Xlint:poly-implicit-overload",
    "-Xlint:option-implicit",
    "-Xlint:delayedinit-select",
    "-Xlint:package-object-classes",
    "-Xlint:stars-align",
    "-Xlint:constant",
    "-Xlint:unused",
    "-Xlint:nonlocal-return",
    "-Xlint:implicit-not-found",
    "-Xlint:serial",
    "-Xlint:valpattern",
    "-Xlint:eta-zero",
    "-Xlint:eta-sam",
    "-Xlint:deprecation",
    "-Xfatal-warnings"
    //"-Ycache-plugin-class-loader:last-modified",
    //"-Ycache-macro-class-loader:last-modified",
    //"-Xexperimental",
    //"-P:semanticdb:synthetics:on",
    //"-P:semanticdb:failures:warning",
    //"-P:semanticdb:sourceroot:/Users/twer/workspace/Binding.scala",
    //"-Yrangepos",
    //"-Xplugin-require:semanticdb",
    //"-Xpluginsdir:~/.cache/coursier/v1/https/repo1.maven.org/maven2/",
    //"-Xplugin-list"
  )

  override def ivyDeps = Agg(
    ivy"com.github.spinalhdl::spinalhdl-core:$spinalVersion",
    ivy"com.github.spinalhdl::spinalhdl-lib:$spinalVersion",
    ivy"com.github.spinalhdl::spinalhdl-sim:$spinalVersion",
    // ivy"org.scalameta:::semanticdb-scalac:4.4.30"
  )
  // val semanticdbScalac = ivy"org.scalameta:::semanticdb-scalac:4.4.30"

  override def scalacPluginIvyDeps = Agg(ivy"com.github.spinalhdl::spinalhdl-idsl-plugin:$spinalVersion")
  // override def scalafixIvyDeps = Agg(
  //   ivy"org.scala-lang.modules::scala-collection-migrations:2.2.0",
  //   ivy"com.github.liancheng::organize-imports:0.5.0"
  // )
}

object rocev2 extends CommonSpinalModule {
  object test extends Tests with TestModule.ScalaTest {
    override def ivyDeps = Agg(ivy"org.scalatest::scalatest:$scalaTestVersion")
    // override def testFrameworks = Seq("org.scalatest.tools.Framework")
    override def testFramework = "org.scalatest.tools.Framework"
    def testOnly(args: String*) = T.command {
      super.runMain("org.scalatest.run", args: _*)
    }
  }
}
