import Dependencies._

ThisBuild / scalaVersion     := "2.12.13"
ThisBuild / version          := "1.0.0-dev"
ThisBuild / organization     := "fr.maif.otoroshi.plugins"
ThisBuild / organizationName := "MAIF"

lazy val root = (project in file("."))
  .settings(
    name := "otorshi-wasmer-plugin",
    fork := true,
    libraryDependencies ++= Seq(
      "org.wasmer" % "wasmer-java" % "0.3.0" from "https://github.com/wasmerio/wasmer-java/releases/download/0.3.0/wasmer-jni-amd64-darwin-0.3.0.jar",
      "fr.maif" %% "otoroshi" % "1.5.0-alpha.14",
      scalaTest % Test
    )
  )