val scala3Version = "3.2.1"

lazy val root = project
  .in(file("."))
  .settings(
    name := "road-simulation",
    version := "0.1.0-SNAPSHOT",

    scalaVersion := scala3Version,

    libraryDependencies += "dev.zio" %% "zio" % "2.0.2",
    libraryDependencies += "com.github.tototoshi" %% "scala-csv" % "1.3.10",
    libraryDependencies += "org.scalameta" %% "munit" % "0.7.29" % Test
  )
