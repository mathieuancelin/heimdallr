enablePlugins(JavaAppPackaging)
enablePlugins(JavaAgent)

name := """heimdallr"""
organization := "io.heimdallr"
version := "1.0.0"
scalaVersion := "2.12.6"

libraryDependencies ++= {
  lazy val akkaHttpVersion  = "10.1.1"
  lazy val akkaVersion      = "2.5.12"
  lazy val circeVersion     = "0.9.3"
  lazy val bcVersion        = "1.59"
  lazy val scalaTestVersion = "3.0.5"
  Seq(
    "com.typesafe.akka"      %% "akka-http"          % akkaHttpVersion,
    "com.typesafe.akka"      %% "akka-http2-support" % akkaHttpVersion,
    "com.typesafe.akka"      %% "akka-stream"        % akkaVersion,
    "com.typesafe.akka"      %% "akka-actor"         % akkaVersion,
    "com.typesafe.akka"      %% "akka-stream-kafka"  % "0.20",
    "org.gnieh"              %% "diffson-circe"      % "2.2.6",
    "ch.qos.logback"         % "logback-classic"     % "1.2.3",
    "io.circe"               %% "circe-core"         % circeVersion,
    "io.circe"               %% "circe-generic"      % circeVersion,
    "io.circe"               %% "circe-parser"       % circeVersion,
    "io.circe"               %% "circe-optics"       % circeVersion,
    "org.mortbay.jetty.alpn" % "jetty-alpn-agent"    % "2.0.7",
    "com.auth0"              % "java-jwt"            % "3.3.0",
    "com.github.gphat"       %% "censorinus"         % "2.1.13",
    "org.bouncycastle"       % "bcprov-jdk15on"      % bcVersion,
    "org.bouncycastle"       % "bcpkix-jdk15on"      % bcVersion,
    "org.bouncycastle"       % "bctls-jdk15on"       % bcVersion,
    "com.google.guava"       % "guava"               % "23.0",
    "commons-io"             % "commons-io"          % "2.6",
    "org.scalatest"          %% "scalatest"          % scalaTestVersion % Test
  )
}

javaAgents += "org.mortbay.jetty.alpn" % "jetty-alpn-agent" % "2.0.7" % "runtime"

mainClass in Compile := Some("io.heimdallr.Main")
mainClass in reStart := Some("io.heimdallr.Main")
mainClass in assembly := Some("io.heimdallr.Main")

assemblyJarName in assembly := "heimdallr.jar"
test in assembly := {}

scalacOptions ++= Seq(
  "-feature",
  "-language:higherKinds",
  "-language:implicitConversions",
  "-language:existentials",
  "-language:postfixOps"
)

sources in (Compile, doc) := Seq.empty
publishArtifact in (Compile, packageDoc) := false

scalafmtVersion in ThisBuild := "1.2.0"
