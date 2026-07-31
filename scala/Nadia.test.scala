package nadia

import java.nio.file.{Files, Path}

class SandboxSuite extends munit.FunSuite:

  private def workspace(): Sandbox =
    val dir = Files.createTempDirectory("nadia-test")
    Files.createDirectories(dir.resolve("src"))
    Files.writeString(dir.resolve("src/main.rs"), "fn main() {}")
    Sandbox(dir.toRealPath(), confine = false)

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

class ToolsSuite extends munit.FunSuite:

  private def fixture(): (Sandbox, Map[String, Tool]) =
    val dir = Files.createTempDirectory("nadia-tools")
    Files.writeString(dir.resolve("a.txt"), "alpha\nbeta\nalpha\n")
    val sb = Sandbox(dir.toRealPath(), confine = false)
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
    val tools = Tools.all(Sandbox(dir.toRealPath(), confine = false)).map(t => t.name -> t).toMap
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

class EndpointSuite extends munit.FunSuite:
  test("the gateway URL is normalized to one spelling") {
    // rozum launch exports ROZUM_GATEWAY_URL without /v1 and OPENAI_BASE_URL with it;
    // both must land on the same endpoint or the agent talks to a 404.
    assertEquals(Endpoint.withV1("http://127.0.0.1:8080"), "http://127.0.0.1:8080/v1")
    assertEquals(Endpoint.withV1("http://127.0.0.1:8080/"), "http://127.0.0.1:8080/v1")
    assertEquals(Endpoint.withV1("http://127.0.0.1:8080/v1"), "http://127.0.0.1:8080/v1")
    assertEquals(Endpoint("http://h:1", "m").url, "http://h:1/v1/chat/completions")
  }
