val scala3Version = "3.2.1"
val zioVersion = "2.0.6"

lazy val root = project
  .in(file("."))
  .settings(
    name := "road-simulation",
    version := "0.1.0-SNAPSHOT",

    scalaVersion := scala3Version,

    libraryDependencies += "dev.zio" %% "zio" % zioVersion,
    libraryDependencies += "com.github.tototoshi" %% "scala-csv" % "1.3.10",
    libraryDependencies ++= Seq(
      "org.scalatest" %% "scalatest" % "3.2.17" % Test,
      "dev.zio" %% "zio-test" % zioVersion % Test,
      "dev.zio" %% "zio-test-sbt" % zioVersion % Test,
      "dev.zio" %% "zio-test-magnolia" % zioVersion % Test,
      "dev.zio" %% "zio-test-junit" % zioVersion % Test
    )
  )
