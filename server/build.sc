import mill._, scalalib._

object app extends ScalaModule {
  def scalaVersion = "2.13.10"

  def scalacOptions: T[Seq[String]] = Seq("-deprecation", "-Xfatal-warnings")

  def ivyDeps = Agg(
    ivy"com.lihaoyi::cask:0.9.1",
    // ivy"com.lihaoyi::scalatags:0.8.2",
    // ivy"com.lihaoyi::mainargs:0.4.0"
    ivy"de.unruh::scala-isabelle::0.4.1",
    // ivy"de.unruh::scala-isabelle:master-SNAPSHOT",
    // resolvers ++= Resolver.sonatypeOssRepos("snapshots"),
  )

  object test extends ScalaTests {
    def testFramework = "utest.runner.Framework"
    def ivyDeps = Agg(
      ivy"com.lihaoyi::utest::0.7.10",
      ivy"com.lihaoyi::requests::0.6.9",
    )
  }
}
