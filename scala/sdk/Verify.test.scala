package agent

import java.nio.file.{Files, Path}

/** The parts of the gate that are CONTRACT (`SPEC.md` §3.1) rather than taste. Every assertion
  * here has a twin in the Rust implementation; where they disagree, one of them is a bug.
  */
class VerifySuite extends munit.FunSuite:

  private def tmp(): Path = Files.createTempDirectory("verify")

  test("a delimiting quote is not part of the argument"):
    // The measured case: the task wrote `cargo run -- "3 4 + 2 *"`, the model returned the
    // argument the way the task spelled it, and the check then demanded a program that accepts a
    // quoted argument. One symmetric pair is delimiting and comes off.
    val frag = Verify.cargoRunFragment("\"3 4 + 2 *\"", "\"14\"")
    assert(frag.contains("'3 4 + 2 *'"), frag)
    assert(frag.contains("'14'"), frag)
    // What must NOT be stripped, because there the quotes are data.
    assertEquals(Verify.unquote("hello"), "hello")
    assertEquals(Verify.unquote("\"unbalanced"), "\"unbalanced")
    assertEquals(Verify.unquote("he said \"hi\""), "he said \"hi\"")
    assertEquals(Verify.unquote("\"a\" + \"b\""), "\"a\" + \"b\"")
    // Exactly one pair; both quote characters; an empty expectation is a real thing to check for.
    assertEquals(Verify.unquote("'\"x\"'"), "\"x\"")
    assertEquals(Verify.unquote("'y'"), "y")
    assertEquals(Verify.unquote("\"\""), "")

  test("a model-supplied string cannot execute anything"):
    // Proven by RUNNING it, not by looking at the escaping — a test that watches the spelling
    // breaks when the quoting changes while the fragment is still inert.
    val dir = tmp()
    val sentinel = dir.resolve("pwned")
    List("'; touch pwned; echo '", "\"; touch pwned; echo \"", "$(touch pwned)", "`touch pwned`", "x'; touch pwned; #")
      .foreach { payload =>
        Verify.runCheck(Verify.cargoRunFragment(payload, "expected"), dir)
        assert(!Files.exists(sentinel), s"payload executed: $payload")
      }

  test("an invented cargo check is recognized"):
    val dir = tmp()
    assert(Verify.isHallucinatedCargoCheck("cargo run -q -- pong", dir, "reply with pong"))
    assert(!Verify.isHallucinatedCargoCheck("cargo build -q", dir, "write a Rust program that prints pong"))
    Files.writeString(dir.resolve("Cargo.toml"), "[package]")
    assert(!Verify.isHallucinatedCargoCheck("cargo build -q", dir, "reply with pong"))

  test("the floor is build plus tests only when there are tests"):
    val dir = tmp()
    assertEquals(Verify.cargoFloor(dir), None)
    Files.writeString(dir.resolve("Cargo.toml"), "[package]\nname='x'")
    assertEquals(Verify.cargoFloor(dir), Some("cargo build -q"))
    Files.createDirectories(dir.resolve("src"))
    Files.writeString(dir.resolve("src/lib.rs"), "#[test]\nfn t() {}")
    assertEquals(Verify.cargoFloor(dir), Some("cargo build -q && cargo test -q"))

  test("a verdict without evidence is unknown, not a pass"):
    assertEquals(Verify.parseVerdict("""{"pass": true}"""), Verify.Verdict.Pass)
    assert(Verify.parseVerdict("""{"pass": false, "reason": "no test"}""").isInstanceOf[Verify.Verdict.Fail])
    // The three shapes that must never read as success.
    assert(Verify.parseVerdict("looks good to me!").isInstanceOf[Verify.Verdict.Unknown])
    assert(Verify.parseVerdict("""{"verdict": "ok"}""").isInstanceOf[Verify.Verdict.Unknown])
    assert(Verify.parseVerdict("").isInstanceOf[Verify.Verdict.Unknown])

  test("a failing check comes back with the output"):
    val dir = tmp()
    val (ok, out) = Verify.runCheck("echo hello; exit 1", dir)
    assert(!ok)
    assert(out.contains("hello"), s"the repair round needs the real output: $out")
    assert(Verify.runCheck("true", dir)._1)

  test("a project one level down is named rather than accommodated"):
    val dir = tmp()
    assertEquals(Verify.misplacedProject(dir), None)
    Files.createDirectories(dir.resolve("rpn"))
    Files.writeString(dir.resolve("rpn/Cargo.toml"), "[package]")
    assertEquals(Verify.misplacedProject(dir), Some("rpn"))
    val p = Verify.repairPrompt("cargo build -q", "error: could not find Cargo.toml", dir)
    assert(p.contains("`rpn/`"), s"the hint must NAME the directory: $p")
    assert(p.contains("cargo init"), s"and say how to fix it: $p")
    // Two candidates: a hint naming the wrong one is worse than none.
    Files.createDirectories(dir.resolve("other"))
    Files.writeString(dir.resolve("other/Cargo.toml"), "[package]")
    assertEquals(Verify.misplacedProject(dir), None)
    // A project IN the root is not misplaced.
    Files.writeString(dir.resolve("Cargo.toml"), "[package]")
    assertEquals(Verify.misplacedProject(dir), None)
    assert(!Verify.repairPrompt("cargo build -q", "error[E0308]", dir).contains("NOTE:"))

  test("the repair prompt carries the command and the output"):
    val p = Verify.repairPrompt("cargo test -q", "assertion failed: 4 + 4 = 7", tmp())
    assert(p.contains("cargo test -q") && p.contains("4 + 4 = 7"), p)
    assert(p.contains("Do not explain"), s"it must ask for a fix, not an explanation: $p")

  test("checkable=false is an answer, and a scripted model proves the whole derivation"):
    // A scripted client is what makes this testable without a model — the same reason
    // `ModelClient` is an interface at all.
    def scripted(reply: String) = new ModelClient:
      def chat(m: List[ujson.Value], t: List[Tool], s: Sampling): Either[String, Turn] =
        Right(Turn(reply, Nil))

    assertEquals(Verify.deriveCheck(scripted("""{"checkable": false}"""), "explain how X works"), None)

    val derived = Verify
      .deriveCheck(
        scripted("""here you go: {"checkable": true, "cargo_test": true, "run": [{"arg": "\"3 4 + 2 *\"", "expect": "14"}]}"""),
        "rpn calculator"
      )
      .getOrElse(fail("a checkable task must yield a command"))
    assert(derived.startsWith("cargo build -q"), derived)
    assert(derived.contains("cargo test -q"), derived)
    assert(derived.contains("'3 4 + 2 *'"), s"the quotes were the task's, not the value's: $derived")
    // A model that cannot be reached has no opinion — it does not fabricate one.
    val dead = new ModelClient:
      def chat(m: List[ujson.Value], t: List[Tool], s: Sampling): Either[String, Turn] = Left("boom")
    assertEquals(Verify.deriveCheck(dead, "rpn calculator"), None)
