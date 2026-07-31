package nadia.rozum

import agent.Tool
import java.nio.file.Files

class SandboxSuite extends munit.FunSuite:

  private def workspace(): Sandbox =
    val dir = Files.createTempDirectory("nadia-test")
    Files.createDirectories(dir.resolve("src"))
    Files.writeString(dir.resolve("src/main.rs"), "fn main() {}")
    Sandbox(dir.toRealPath(), confinement = Confinement.Open)

  test("resolves paths inside the workspace, including ones that do not exist yet") {
    val sb = workspace()
    assert(sb.resolve("src/main.rs").isRight)
    // write_file creates files, so a path that does not exist must still resolve.
    assert(sb.resolve("src/new_module.rs").isRight)
    assert(sb.resolve("deeply/nested/new.txt").isRight)
  }

  test("refuses escapes rather than clamping them") {
    val sb = workspace()
    // The failure this guards: a jail that strips `..` would turn `../secrets` into
    // <root>/secrets and write to a path nobody asked for.
    List("../outside.txt", "src/../../outside.txt", "/etc/passwd", "  ").foreach { bad =>
      assert(sb.resolve(bad).isLeft, s"should have refused $bad")
    }
  }

  test("exec runs in the workspace and reports the exit code") {
    val sb = workspace()
    val ok = sb.exec("pwd && echo marker")
    assertEquals(ok.exitCode, 0)
    assert(ok.stdout.contains("marker"), ok.stdout)
    assertEquals(sb.exec("exit 3").exitCode, 3)
  }

  test("exec kills at the deadline and reports it as timeout(1) does") {
    val sb = workspace()
    val r = sb.exec("sleep 30", Some(java.time.Duration.ofMillis(300)))
    assert(r.timedOut)
    assertEquals(r.exitCode, 124)
  }

  test("exec survives output larger than a pipe buffer") {
    // Without draining the streams on their own threads, a child that fills the pipe
    // blocks forever — and the timeout cannot save it, because it is waiting on the same
    // process. 200k of output is comfortably past the 64k buffer.
    val sb = workspace()
    val r = sb.exec("head -c 200000 /dev/zero | tr '\\0' 'x'")
    assertEquals(r.exitCode, 0)
    assertEquals(r.stdout.length, 200000)
  }

class GatewaySuite extends munit.FunSuite:

  test("one model, several spellings") {
    // rozum launches with `org:repo`; the Hub writes `org/repo`; both name one set of
    // weights. Comparing them as strings is the defect this mirrors from the gateway's own
    // warm cache, where it warmed a second resident copy of the model already loaded.
    assert(Gateway.sameModel("mlx-community:Qwen3.5-4B-MLX-4bit", "mlx-community/Qwen3.5-4B-MLX-4bit"))
    assert(Gateway.sameModel("hf:org/model", "org/model"))
    assert(Gateway.sameModel("org/Model", "org/model"), "repository names compare case-insensitively")
    assert(Gateway.sameModel("local", "local"))

    assert(!Gateway.sameModel("mlx-community/A", "mlx-community/B"))
    assert(!Gateway.sameModel("local", "mlx-community/A"))
    // Not every spec is an HF repository, and those must not be coerced into one.
    assert(!Gateway.sameModel("ollama:qwen3:8b", "ollama/qwen3:8b"))
    assert(!Gateway.sameModel("/abs/path/model", "abs/path"))
  }

  test("the launch command a warning suggests is a spec rozum accepts") {
    assertEquals(Gateway.rozumSpec("mlx-community/Qwen3.5-4B-MLX-4bit"), "mlx-community:Qwen3.5-4B-MLX-4bit")
    assertEquals(Gateway.rozumSpec("mlx-community:Qwen3.5-4B-MLX-4bit"), "mlx-community:Qwen3.5-4B-MLX-4bit")
    // Nothing to convert, and nothing invented.
    assertEquals(Gateway.rozumSpec("local"), "local")
  }

  test("an unreachable gateway produces no warning, only a failed request") {
    // The check is an aid, not a gate: a gateway that cannot be probed must not stop a run,
    // because the request itself reports that far better a moment later.
    assertEquals(Gateway.residentModel("http://127.0.0.1:1"), None)
    assertEquals(Gateway.residentWarning("http://127.0.0.1:1", "mlx-community/Qwen3.5-4B-MLX-4bit"), None)
  }

class ConfinementSuite extends munit.FunSuite:

  test("--no-confine is taken literally, and named as such") {
    assertEquals(Confinement.select(requested = false), Confinement.Open)
    assert(Confinement.Open.describe(false).contains("NOT confined"))
  }

  test("the mechanism reported is one that exists on this machine") {
    // The defect this pins: a boolean `confine` that reads as a guarantee on Linux, where
    // sandbox-exec does not exist and nothing was ever wrapped.
    val chosen = Confinement.select(requested = true)
    chosen match
      case Confinement.Seatbelt => assert(Confinement.seatbeltAvailable)
      case Confinement.Runtime  => assert(Confinement.inContainer)
      case Confinement.Open     => assert(!Confinement.seatbeltAvailable && !Confinement.inContainer)
  }

  test("inside a container, --allow-net is described as the no-op it is") {
    // It cannot switch on a network the runtime withheld, nor switch off one it granted.
    List(true, false).foreach { net =>
      assert(Confinement.Runtime.describe(net).contains("--allow-net has no effect"))
    }
  }

  test("the seatbelt still refuses a write outside the workspace") {
    assume(Confinement.seatbeltAvailable, "macOS only")
    val dir = Files.createTempDirectory("nadia-jail").toRealPath()
    val sb = Sandbox(dir, confinement = Confinement.Seatbelt)
    assertEquals(sb.exec("echo in > inside.txt").exitCode, 0)
    // /tmp is a symlink to /private/tmp on macOS, which is why allowing either allows both,
    // and why neither is on the profile's allowlist.
    val out = sb.exec("echo out > /tmp/nadia-should-not-exist")
    assert(out.exitCode != 0, s"the seatbelt let a write out of the workspace: $out")
    assert(!Files.exists(java.nio.file.Paths.get("/tmp/nadia-should-not-exist")))
  }

class ToolsSuite extends munit.FunSuite:

  private def fixture(): (Sandbox, Map[String, Tool]) =
    val dir = Files.createTempDirectory("nadia-tools")
    Files.writeString(dir.resolve("a.txt"), "alpha\nbeta\nalpha\n")
    val sb = Sandbox(dir.toRealPath(), confinement = Confinement.Open)
    (sb, Tools.all(sb).map(t => t.name -> t).toMap)

  test("exposes exactly six tools") {
    val (sb, _) = fixture()
    val names = Tools.all(sb).map(_.name)
    assertEquals(names.length, 6, s"tool set grew without a spec change: $names")
    List("read_file", "write_file", "edit_file", "list_dir", "grep", "bash").foreach { n =>
      assert(names.contains(n), s"missing $n")
    }
  }

  test("edit refuses an ambiguous match and a missing one") {
    val (_, tools) = fixture()
    val edit = tools("edit_file")

    // "alpha" appears twice — replacing the first would silently do the wrong thing.
    val ambiguous = edit.run(ujson.Obj("path" -> "a.txt", "old_string" -> "alpha", "new_string" -> "x"))
    assert(ambiguous.left.exists(_.contains("matched 2 times")), ambiguous.toString)

    val missing = edit.run(ujson.Obj("path" -> "a.txt", "old_string" -> "nope", "new_string" -> "x"))
    assert(missing.left.exists(_.contains("matched 0 times")), missing.toString)

    assert(edit.run(ujson.Obj("path" -> "a.txt", "old_string" -> "beta", "new_string" -> "BETA")).isRight)
  }

  test("edit treats its arguments as text, not as a pattern") {
    // replaceFirst takes a REGEX and a replacement with $-group syntax; handing it raw
    // model output would either throw or silently splice a capture group.
    val dir = Files.createTempDirectory("nadia-regex")
    Files.writeString(dir.resolve("f.txt"), "value = $1 (a.b)\n")
    val tools = Tools.all(Sandbox(dir.toRealPath(), confinement = Confinement.Open)).map(t => t.name -> t).toMap
    val r = tools("edit_file").run(
      ujson.Obj("path" -> "f.txt", "old_string" -> "$1 (a.b)", "new_string" -> "$2 [c]")
    )
    assert(r.isRight, r.toString)
    assertEquals(Files.readString(dir.resolve("f.txt")), "value = $2 [c]\n")
  }

  test("path arguments cannot escape the workspace") {
    val (_, tools) = fixture()
    List(
      "read_file" -> ujson.Obj("path" -> "../../etc/passwd"),
      "write_file" -> ujson.Obj("path" -> "/tmp/nadia-escape", "content" -> "x"),
      "list_dir" -> ujson.Obj("path" -> "..")
    ).foreach { (name, args) =>
      val r = tools(name).run(args)
      assert(r.left.exists(m => m.contains("outside") || m.contains("empty")), s"$name: $r")
    }
  }

  test("a missing argument names the argument") {
    val (_, tools) = fixture()
    val r = tools("read_file").run(ujson.Obj())
    assert(r.left.exists(_.contains("`path`")), s"the model must be told which argument: $r")
    // null is missing, not empty — reading it as "" writes an empty file over a real one.
    val nulled = tools("write_file").run(ujson.Obj("path" -> "n.txt", "content" -> ujson.Null))
    assert(nulled.left.exists(_.contains("`content`")), nulled.toString)
  }

  test("a number where a string was asked for is accepted, not called missing") {
    // Observed against Qwen3.5-4B in a container: asked to write the line count into a file,
    // it sent {"content": 4}. The old reader answered "missing required string argument
    // `content`" — which is false, so the model re-sent the identical call until the
    // repetition guard killed the run. A JSON scalar has one obvious textual form.
    val dir = Files.createTempDirectory("nadia-coerce")
    val tools = Tools.all(Sandbox(dir.toRealPath(), confinement = Confinement.Open))
      .map(t => t.name -> t).toMap
    assert(tools("write_file").run(ujson.Obj("path" -> "count.txt", "content" -> 4)).isRight)
    assertEquals(Files.readString(dir.resolve("count.txt")), "4")
    assert(tools("write_file").run(ujson.Obj("path" -> "b.txt", "content" -> true)).isRight)
    assertEquals(Files.readString(dir.resolve("b.txt")), "true")

    // An object or an array has no single right rendering, and guessing one would put
    // invented content into a file. Refused, and told why.
    val obj = tools("write_file").run(ujson.Obj("path" -> "c.txt", "content" -> ujson.Obj("a" -> 1)))
    assert(obj.left.exists(m => m.contains("must be a string") && m.contains("JSON object")), obj.toString)
  }

  test("an invalid regex comes back as the regex error, not as zero matches") {
    val (_, tools) = fixture()
    val r = tools("grep").run(ujson.Obj("pattern" -> "("))
    assert(r.left.exists(_.contains("not a valid regular expression")), r.toString)
  }

  test("occurrences counts non-overlapping matches") {
    assertEquals(Tools.occurrences("aaa", "a"), 3)
    assertEquals(Tools.occurrences("abab", "ab"), 2)
    assertEquals(Tools.occurrences("abc", "z"), 0)
    assertEquals(Tools.occurrences("abc", ""), 0)
  }
