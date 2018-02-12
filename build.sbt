enablePlugins(JavaAppPackaging)
enablePlugins(JavaAgent)

name := """heimdallr"""
organization := "io.heimdallr"
version := "1.0.0"
scalaVersion := "2.12.4"

libraryDependencies ++= {
  lazy val akkaHttpVersion = "10.1.0-RC2"
  lazy val akkaVersion     = "2.5.9"
  Seq(
    "com.typesafe.akka"      %% "akka-http"          % akkaHttpVersion,
    "com.typesafe.akka"      %% "akka-http2-support" % akkaHttpVersion,
    "com.typesafe.akka"      %% "akka-stream"        % akkaVersion,
    "com.typesafe.akka"      %% "akka-actor"         % akkaVersion,
    "com.typesafe.akka"      %% "akka-stream-kafka"  % "0.18",
    "org.gnieh"              %% "diffson-circe"      % "2.2.4",
    "ch.qos.logback"         % "logback-classic"     % "1.2.3",
    "io.dropwizard.metrics"  % "metrics-core"        % "4.0.2",
    "io.dropwizard.metrics"  % "metrics-jmx"         % "4.0.2",
    "io.circe"               %% "circe-core"         % "0.9.0",
    "io.circe"               %% "circe-generic"      % "0.9.0",
    "io.circe"               %% "circe-parser"       % "0.9.0",
    "io.circe"               %% "circe-optics"       % "0.9.0",
    "org.mortbay.jetty.alpn" % "jetty-alpn-agent"    % "2.0.6",
    "com.auth0"              % "java-jwt"            % "3.3.0",
    "com.github.gphat"       %% "censorinus"         % "2.1.8",
    "org.bouncycastle"       % "bcprov-jdk15on"      % "1.59",
    "org.bouncycastle"       % "bcpkix-jdk15on"      % "1.59",
    "org.bouncycastle"       % "bctls-jdk15on"       % "1.59",
    "com.google.guava"       % "guava"               % "23.0",
    "commons-io"             % "commons-io"          % "2.6"
  )
}

javaAgents += "org.mortbay.jetty.alpn" % "jetty-alpn-agent" % "2.0.6" % "runtime"

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
