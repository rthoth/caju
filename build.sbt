
ThisBuild / organization := "com.github.rthoth"
ThisBuild / scalaVersion := "2.13.6"
ThisBuild / version := "1.0.0"

val AkkaVersion = "2.6.8"
val AkkaHttpVersion = "10.2.4"

lazy val root = (project in file("."))
  .settings(
    name := "caju-authorizer",
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-actor-typed" % AkkaVersion,
      "com.typesafe.akka" %% "akka-actor-testkit-typed" % AkkaVersion,
      "com.typesafe.akka" %% "akka-stream" % AkkaVersion,
      "com.typesafe.akka" %% "akka-http" % AkkaHttpVersion,
      "com.typesafe.akka" %% "akka-http-spray-json" % AkkaHttpVersion,

      "org.scalatest" %% "scalatest" % "3.2.9" % "test",

      "com.typesafe.scala-logging" %% "scala-logging" % "3.9.4",
      "ch.qos.logback" % "logback-classic" % "1.2.3"
    )
  )
  .enablePlugins(JavaAppPackaging)
  .enablePlugins(DockerPlugin)
  .settings(
    Docker / maintainer := "ronaldo.asilva@gmail.com",
    Docker / packageName := "rthoth-authorizer",
    dockerBaseImage := "openjdk:11.0.11-jdk",
    dockerUsername := Some("caju"),
    dockerExposedPorts ++= Seq(8888),
    dockerEnvVars ++= Map(
      "CAJU_HTTP_HOSTNAME" -> "0.0.0.0",
      "CAJU_HTTP_PORT" -> "8888",
      "CAJU_HTTP_TIMEOUT" -> "100"
    )
  )