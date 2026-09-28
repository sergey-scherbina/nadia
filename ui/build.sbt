// nadia/ui — the workspace: ONE program over rozum + nadia, drawn by any okay host
// (rozum:docs/specs/okay-workspace-ui.md). Its own build on purpose: nadia/scala is
// the one-dependency statement, this is the opposite one — it stands on okay-ui.
//
// okay is not published: `cd ../../okay && sbt okayUiJVM/publishLocal` (and its
// closure: okay okayOptics okayAsync okayPlatform okayStream okayLex okayParse
// okayCodec okayJs okayDirect okayWorkflow okayPersist), see okay:docs/building-a-chat-app.md §1.
ThisBuild / scalaVersion := "3.9.0"
ThisBuild / version := "0.1.0-SNAPSHOT"

lazy val okayVersion = "0.2.0-SNAPSHOT"

lazy val ui = (project in file("."))
  .settings(
    name := "nadia-ui",
    libraryDependencies ++= Seq(
      "dev.okay" %% "okay-ui" % okayVersion,
      "org.scalameta" %% "munit" % "1.1.1" % Test),
    scalacOptions ++= Seq("-deprecation", "-feature"),
    // the terminal host owns stdin and paints stdout: the app must run in its own
    // process with the console attached, not inside sbt's
    run / fork := true,
    run / connectInput := true,
    outputStrategy := Some(StdoutOutput),
    Test / fork := true,
    // the forked JVMs print the tree's glyphs (▶ ↻ ✎ …) — UTF-8 whatever the container's locale
    javaOptions ++= Seq("-Dstdout.encoding=UTF-8", "-Dfile.encoding=UTF-8"),
  )
