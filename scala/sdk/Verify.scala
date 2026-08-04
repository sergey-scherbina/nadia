package agent

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** The verify-repair gate's primitives — `SPEC.md` §3.1.
  *
  * Success is not the model's to declare. The prompt already tells an agent to run the build and
  * read the output, and a small model does exactly that and no more: it verifies that the program
  * *builds and runs*, because nobody wrote down what the right answer is. The gate writes it down
  * — before the run, so the run cannot influence it — and lets a command decide.
  *
  * Generic on purpose: nothing here names nadia, a tool, or a gateway. It takes a [[ModelClient]]
  * and a directory. The policy — how many repair rounds, what the operator is shown — belongs to
  * the application (`nadia.rozum.Gate`), exactly as the loop's budget does.
  */
object Verify:

  /** A semantic verdict. `Unknown` is deliberately not `Fail`: "I could not tell" and "it is
    * wrong" call for different decisions, and collapsing them either invents failures or hides
    * them.
    */
  enum Verdict:
    case Pass
    case Fail(reason: String)
    case Unknown(reason: String)

  private val DerivePrompt =
    """Set up the acceptance CHECK for this coding task. Reply with ONLY a JSON object, no prose:
      |{"checkable": <true|false>, "cargo_test": <true|false>, "run": [{"arg": "<argument VALUE only>", "expect": "<exact stdout>"}]}
      |- "run": one entry per concrete example the task states as `cargo run -- X` printing `Y`.
      |  `arg` is JUST the argument value X — e.g. for "cargo run -- hello prints olleh", arg is
      |  "hello" (NOT "cargo run -- hello"); `expect` is JUST Y, e.g. "olleh". Omit if no example.
      |- Quotes that DELIMIT a value in the task are not part of it: for `cargo run -- "3 4 + 2 *"`
      |  printing 14, arg is `3 4 + 2 *` and expect is `14` — no surrounding quotes in either.
      |- cargo_test=true ONLY if the task explicitly requires a unit test to pass.
      |- checkable=false if the task has no machine-checkable build/run/test criterion. In
      |  particular, if the task is NOT about a Rust program — it only asks for a chat reply, an
      |  explanation, or plain text (e.g. "reply with the word pong") — return checkable=false. The
      |  acceptance is the reply itself, which is NOT a build/run/test; do NOT invent a `cargo run`
      |  or an argument for it.
      |
      |Task:
      |""".stripMargin

  /** One plain completion, no tools. `None` when the model could not be reached — every caller
    * here treats that as "no opinion" rather than as an answer.
    */
  private def ask(client: ModelClient, prompt: String, maxTokens: Int): Option[String] =
    client
      .chat(List(Wire.user(prompt)), Nil, Sampling(temperature = 0.0, maxTokens = maxTokens))
      .toOption
      .map(_.text)
      .filter(_.trim.nonEmpty)

  /** The first JSON object in a reply. Models wrap JSON in prose however they like. */
  private def firstJson(text: String): Option[ujson.Value] =
    val (a, b) = (text.indexOf('{'), text.lastIndexOf('}'))
    if a < 0 || b < a then None else scala.util.Try(ujson.read(text.substring(a, b + 1))).toOption

  /** Shell-quote so a model-supplied string is inert inside the derived command. */
  private def shquote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

  /** Strip one symmetric pair of delimiting quotes.
    *
    * A task writes `cargo run -- "3 4 + 2 *"` and the model returns the argument the way the task
    * spelled it. The check would then demand a program that accepts a quoted argument, which
    * nobody asked for; measured, it cost a run both of its repair rounds. Exactly one pair, only
    * when symmetric, and only when the same quote does not also appear inside — `he said "hi"` and
    * `"a" + "b"` are data and stay whole.
    */
  def unquote(s: String): String =
    val t = s.trim
    List('"', '\'')
      .collectFirst {
        case q if t.length >= 2 && t.head == q && t.last == q && !t.substring(1, t.length - 1).contains(q) =>
          t.substring(1, t.length - 1)
      }
      .getOrElse(t)

  /** A `cargo run` check that SAYS what it saw. A bare `[ "$(cargo run …)" = X ]` fails silently,
    * which leaves the repair round with an empty error on exactly the mismatches that matter.
    */
  def cargoRunFragment(arg: String, expect: String): String =
    val (a, e) = (shquote(unquote(arg)), shquote(unquote(expect)))
    s"""{ out=$$(cargo run -q -- $a) && [ "$$out" = $e ] || """ +
      s"""{ printf 'cargo run -- %s printed <%s>; expected <%s>\\n' $a "$$out" $e >&2; exit 1; }; }"""

  /** Does the task actually ask for Rust? Lets a create-from-scratch cargo check be legitimate
    * before the project exists, while keeping a chat task off it.
    */
  def taskMentionsCargo(task: String): Boolean =
    val p = task.toLowerCase
    List("cargo", "rust", "crate", "src/main.rs", "src/lib.rs", ".rs").exists(p.contains)

  /** A cargo check for a workspace with no manifest and a task that never mentioned Rust is one
    * the model invented. Measured: "reply with the word pong" became `cargo run -- pong == gnop`.
    */
  def isHallucinatedCargoCheck(check: String, cwd: Path, task: String): Boolean =
    check.contains("cargo") && !Files.exists(cwd.resolve("Cargo.toml")) && !taskMentionsCargo(task)

  /** The floor: what can be checked without asking anybody. */
  def cargoFloor(cwd: Path): Option[String] =
    if !Files.isRegularFile(cwd.resolve("Cargo.toml")) then None
    else
      val hasTests = List("src", "tests")
        .map(cwd.resolve)
        .filter(Files.isDirectory(_))
        .exists { dir =>
          Files.walk(dir).iterator.asScala.exists { f =>
            Files.isRegularFile(f) && f.toString.endsWith(".rs") &&
            scala.util.Try(Files.readString(f)).toOption.exists(_.contains("#[test]"))
          }
        }
      Some(if hasTests then "cargo build -q && cargo test -q" else "cargo build -q")

  /** Ask the model to formalize the task, and build the command from the structured answer.
    * `None` when there is nothing machine-checkable — an honest answer, not a failure.
    */
  def deriveCheck(client: ModelClient, task: String): Option[String] =
    for
      text <- ask(client, DerivePrompt + task, 300)
      j <- firstJson(text)
      if j.obj.get("checkable").flatMap(_.boolOpt).getOrElse(false)
    yield
      val test = j.obj.get("cargo_test").flatMap(_.boolOpt).getOrElse(false)
      val runs = j.obj
        .get("run")
        .flatMap(_.arrOpt)
        .map(_.toList.flatMap { r =>
          for
            a <- r.obj.get("arg").flatMap(_.strOpt)
            e <- r.obj.get("expect").flatMap(_.strOpt)
          yield cargoRunFragment(a, e)
        })
        .getOrElse(Nil)
      (List("cargo build -q") ++ (if test then List("cargo test -q") else Nil) ++ runs).mkString(" && ")

  /** Run the check in `cwd`. Returns `(passed, the tail of what it printed)` — the second half is
    * the repair prompt, so cargo's progress noise goes and the diagnosis stays.
    */
  def runCheck(cmd: String, cwd: Path): (Boolean, String) =
    scala.util
      .Try {
        val p = ProcessBuilder("/bin/sh", "-c", cmd).directory(cwd.toFile).redirectErrorStream(true).start()
        val out = String(p.getInputStream.readAllBytes, "UTF-8")
        val code = p.waitFor()
        val lines = out.linesIterator
          .filterNot { l =>
            val t = l.stripLeading
            t.startsWith("Compiling") || t.startsWith("Finished") || t.startsWith("Running")
          }
          .toList
        (code == 0, lines.takeRight(40).mkString("\n"))
      }
      .getOrElse((false, "verify command failed to run"))

  /** Where the cargo project actually is, when it is not where the check runs.
    *
    * `cargo new <name>` creates a SUBDIRECTORY and a root-level check cannot see it. Returns the
    * single immediate child holding a manifest when the root holds none; ambiguity returns `None`,
    * because a hint that names the wrong directory is worse than no hint.
    *
    * A diagnostic, deliberately not a relocation: moving the check into the subdirectory would
    * turn work delivered where nobody asked into a passing run.
    */
  def misplacedProject(cwd: Path): Option[String] =
    if Files.isRegularFile(cwd.resolve("Cargo.toml")) then None
    else
      scala.util
        .Try(Files.list(cwd).iterator.asScala.toList)
        .getOrElse(Nil)
        .filter(p => Files.isRegularFile(p.resolve("Cargo.toml")))
        .map(_.getFileName.toString) match
        case one :: Nil => Some(one)
        case _          => None

  /** The message a failed check sends back into the conversation: the command and what it actually
    * printed, because "it failed" is not something a model can act on.
    */
  def repairPrompt(check: String, output: String, cwd: Path): String =
    val base =
      s"""The acceptance check for this task FAILED. This is the real output, not a summary.
         |
         |$$ $check
         |$output
         |
         |Fix the cause and make the check pass. Do not explain the failure instead of fixing it,
         |and do not report success until you have run the check yourself and read what it printed.""".stripMargin
    misplacedProject(cwd) match
      case Some(dir) =>
        s"""$base
           |
           |NOTE: the check runs in the workspace ROOT, and there is no Cargo.toml there — the
           |project is in `$dir/`. `cargo new $dir` created a subdirectory; the project has to be in
           |the root itself. Move it up or recreate it with `cargo init` in the root, then run the
           |check again.""".stripMargin
      case None => base

  /** Semantic judgement where nothing deterministic exists. Unknown is not a pass. */
  def judge(client: ModelClient, task: String, cwd: Path): Verdict =
    val code = sourceSnapshot(cwd).getOrElse("(no source found)")
    val prompt =
      s"""You are a strict code reviewer judging whether the CODE accomplishes the TASK. Reply with
         |ONLY a JSON object, no prose: {"pass": <true|false>, "reason": "<one short sentence>"}.
         |Rule pass=false ONLY if the code clearly fails a STATED requirement of the task; if it
         |plausibly satisfies the task, pass=true. Do not invent requirements the task did not state.
         |
         |TASK:
         |$task
         |
         |CODE:
         |$code""".stripMargin
    ask(client, prompt, 200) match
      case Some(text) => parseVerdict(text)
      case None       => Verdict.Unknown("model-judge unavailable or timed out")

  /** Parse a judge reply. A parseable explicit boolean is evidence; anything else is Unknown. */
  def parseVerdict(text: String): Verdict =
    firstJson(text) match
      case Some(j) =>
        j.obj.get("pass").flatMap(_.boolOpt) match
          case Some(false) =>
            val reason = j.obj.get("reason").flatMap(_.strOpt).map(_.trim).getOrElse("task requirement not met")
            Verdict.Fail(s"model-judge ruled the task NOT accomplished: $reason")
          case Some(true) => Verdict.Pass
          case None       => Verdict.Unknown("model-judge response has no boolean `pass` field")
      case None => Verdict.Unknown("model-judge response is not valid verdict JSON")

  /** The workspace's own source, for a reader that cannot run anything. */
  def sourceSnapshot(cwd: Path, maxBytes: Int = 8192): Option[String] =
    val tests = scala.util
      .Try(Files.list(cwd.resolve("tests")).iterator.asScala.toList)
      .getOrElse(Nil)
      .map(p => s"tests/${p.getFileName}")
      .filter(_.endsWith(".rs"))
      .sorted
      .take(4)
    val sections = (List("Cargo.toml", "src/main.rs", "src/lib.rs") ++ tests).flatMap { rel =>
      scala.util.Try(Files.readString(cwd.resolve(rel))).toOption
        .filter(_.length <= maxBytes)
        .map(body => s"--- $rel ---\n$body")
    }
    Option.when(sections.nonEmpty)(sections.mkString("\n\n"))
