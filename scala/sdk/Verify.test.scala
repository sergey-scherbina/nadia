package agent

import java.nio.file.{Files, Path}

/** The parts of the gate that are CONTRACT (`SPEC.md` §3.1) rather than taste. Every assertion
  * here has a twin in the Rust implementation; where they disagree, one of them is a bug.
  */
class VerifySuite extends munit.FunSuite:

  private def tmp(): Path = Files.createTempDirectory("verify")

  test("an expectation the task never states is not checked"):
    // The measured case: `wordcount` says what the program must DO and never what it prints — the
    // answer lives in a data file — and the model answered with three lines that appear nowhere.
    // A correct program fails that check forever. Twin of the Rust assertion.
    val wordcount = "create a Rust binary that reads a text file, counts words case-insensitively " +
      "and prints the top 3 as `word count`. Verify with `cargo run -- input.txt`."
    assert(!Verify.taskStates(wordcount, "a 3\nc 2\nd 2"))
    val reverse = "fix the bug: `cargo run -- hello` must print exactly `olleh`"
    assert(Verify.taskStates(reverse, "olleh"))
    assert(Verify.taskStates(reverse, " OLLEH "))
    assert(!Verify.taskStates(reverse, "hello world"))
    assert(!Verify.taskStates(reverse, ""))

  test("arity comes from the task, not from the model"):
    // Both directions, both measured end-to-end on 2026-08-04 with the same 4B model: asked for
    // one string it merged two arguments, asked for a list it split a quoted one into five. The
    // task said which it was, in its own punctuation, in both cases.
    val two = "print the sum of two arguments: cargo run -- 3 4 must print 7"
    assertEquals(Verify.taskArgvFor(two, "3 4"), Some(List("3", "4")))
    val one = """an RPN calculator: cargo run -- "3 4 + 2 *" must print 14"""
    assertEquals(Verify.taskArgvFor(one, "3 4 + 2 *"), Some(List("3 4 + 2 *")))
    // A value the task never states leaves the model's grouping alone rather than inventing one.
    assertEquals(Verify.taskArgvFor(two, "5 6"), None)
    assertEquals(Verify.shellLex(""" "3 4 + 2 *" must print 14"""), List("3 4 + 2 *", "must", "print", "14"))
    assertEquals(Verify.shellLex("""a "" b"""), List("a", "", "b"))

  test("arity is part of the criterion"):
    // Found END TO END on 2026-08-04 by this implementation, not by review: for "cargo run -- 3 4
    // must print 7" the model answered with both numbers in one string, the check ran
    // `cargo run -q -- '3 4'` against a correct two-argument program, and the gate reported FAILED
    // after both repair rounds. The twin of this assertion lives in rozum-agent's verify.rs.
    val two = Verify.cargoRunFragmentArgs(List("3", "4"), "7")
    assert(two.contains("cargo run -q -- '3' '4'"), two)
    // The other half, and why arity cannot be guessed from whitespace: this one really is single.
    val one = Verify.cargoRunFragmentArgs(List("3 4 + 2 *"), "14")
    assert(one.contains("cargo run -q -- '3 4 + 2 *'"), one)
    // And the message names the whole command line, which is what the repair round reads.
    assert(two.split("printf")(1).contains("'3 4'"), two)

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
        // The task has to STATE what it expects, or the expectation is the model's invention and
        // is dropped (see "an expectation the task never states"). A stub task text used to pass
        // here, which is exactly the hole that fix closed.
        """rpn calculator: `cargo run -- "3 4 + 2 *"` must print 14"""
      )
      .getOrElse(fail("a checkable task must yield a command"))
    assert(derived.startsWith("cargo build -q"), derived)
    assert(derived.contains("cargo test -q"), derived)
    assert(derived.contains("'3 4 + 2 *'"), s"the quotes were the task's, not the value's: $derived")
    // A model that cannot be reached has no opinion — it does not fabricate one.
    val dead = new ModelClient:
      def chat(m: List[ujson.Value], t: List[Tool], s: Sampling): Either[String, Turn] = Left("boom")
    assertEquals(Verify.deriveCheck(dead, """rpn calculator: `cargo run -- "3 4 + 2 *"` must print 14"""), None)

/** The same rules, read from `contract/gate-cases.json` instead of retyped here.
  *
  * The suite above is hand-written, and its header says every assertion has a twin in Rust "where
  * they disagree, one of them is a bug" — which was true and was maintained by hand, one port at a
  * time. This class reads the corpus all three implementations read, so a rule added once is
  * checked three times and a leg that drifts fails instead of quietly disagreeing (SPEC.md §3.1).
  */
class ContractCorpusSuite extends munit.FunSuite:
  import upickle.default.*

  private def corpus: ujson.Value =
    // From the repository root, whichever directory the runner started in.
    val here = Path.of(".").toAbsolutePath
    val candidates = LazyList
      .iterate(here)(_.getParent)
      .takeWhile(_ != null)
      .map(_.resolve("contract/gate-cases.json"))
    candidates.find(Files.exists(_)) match
      case Some(p) => ujson.read(Files.readString(p))
      case None    => fail("contract/gate-cases.json not found — the corpus IS the contract")

  private def strs(v: ujson.Value): List[String] = v.arr.map(_.str).toList

  test("shell_lex — the corpus"):
    for c <- corpus("shell_lex").arr do
      assertEquals(Verify.shellLex(c("in").str), strs(c("out")), c("why").str)

  test("task_states — the corpus"):
    for c <- corpus("task_states").arr do
      assertEquals(Verify.taskStates(c("task").str, c("expect").str), c("out").bool, c("why").str)

  test("task_argv_for — the corpus"):
    for c <- corpus("task_argv_for").arr do
      val want = c("out") match
        case ujson.Null => None
        case v          => Some(strs(v))
      assertEquals(Verify.taskArgvFor(c("task").str, c("joined").str), want, c("why").str)
