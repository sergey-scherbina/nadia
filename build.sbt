/*
 * nadia — the okay implementation (SPEC.md §0, fourth row; docs/specs/app.md).
 *
 * The whole of `okay` rides as a git SUBMODULE at `okay/` and is referenced as a
 * source dependency, the way ../okay-chat does it: no publishing step, no version
 * skew, a change in the platform is compiled by this build the moment the
 * submodule pointer moves. `okay-telegram` is not in any local ivy anyway.
 *
 * This build owns `app/` only. `scala/` (scala-cli, the bare-JDK implementation)
 * and `src/` (ScalaScript) are not sbt projects and are untouched by it.
 */

ThisBuild / scalaVersion := "3.9.0"
ThisBuild / scalacOptions ++= Seq("-release", "21")
ThisBuild / javacOptions ++= Seq("--release", "21")
ThisBuild / organization := "dev.okay"
ThisBuild / version := "0.1.0-SNAPSHOT"

// the platform, by module — each is what docs/specs/app.md names as "underneath"
lazy val okayAgent = ProjectRef(file("okay"), "okayAgentJVM")       // loop, tools, context, providers
lazy val okayActor = ProjectRef(file("okay"), "okayActorJVM")       // agents as supervised actors
lazy val okayPersist = ProjectRef(file("okay"), "okayPersistJVM")   // the state log
lazy val okaySecurity = ProjectRef(file("okay"), "okaySecurityJVM") // Principal, Policy, the roster
lazy val okayUi = ProjectRef(file("okay"), "okayUiJVM")             // one program, every host
lazy val okayTelegram = ProjectRef(file("okay"), "okayTelegramJVM") // the chat as a host
lazy val okayHttp = ProjectRef(file("okay"), "okayHttpJVM")         // the Bot API's transport

lazy val app = (project in file("app"))
  .dependsOn(okayAgent, okayActor, okayPersist, okaySecurity, okayUi, okayTelegram, okayHttp)
  .settings(
    name := "nadia-app",
    scalacOptions ++= Seq("-Xkind-projector", "-deprecation", "-feature"),
    // the six tools, the sandbox, the prompt and the gate are the Scala 3
    // implementation's, unchanged (SPEC.md §0): its sources compile here too,
    // minus its own entry point and its scala-cli tests
    Compile / unmanagedSourceDirectories ++= Seq(
      (ThisBuild / baseDirectory).value / "scala" / "sdk",
      (ThisBuild / baseDirectory).value / "scala" / "rozum",
      (ThisBuild / baseDirectory).value / "scala" / "cloud"),
    Compile / unmanagedSources / excludeFilter := "*.test.scala",
    libraryDependencies += "com.lihaoyi" %% "upickle" % "4.4.3",
    libraryDependencies += "org.scalameta" %% "munit" % "1.1.1" % Test,
    Test / fork := true,
    run / fork := true,
    run / connectInput := true,
    Compile / run / mainClass := Some("nadia.Main"),
  )

lazy val root = (project in file(".")).aggregate(app)
