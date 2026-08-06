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
      |{"checkable": <true|false>, "cargo_test": <true|false>, "run": [{"args": ["<one entry PER command-line argument>"], "expect": "<exact stdout>"}]}
      |- "run": one entry per concrete example the task states as `cargo run -- X` printing `Y`.
      |  `args` is the argument LIST X, one entry per argument — for "cargo run -- hello prints
      |  olleh", args is ["hello"] (NOT ["cargo run -- hello"]); `expect` is JUST Y, e.g. "olleh".
      |- HOW MANY entries is decided by the task's spacing and quotes, and it matters:
      |  `cargo run -- 3 4` printing 7 is TWO arguments, args ["3", "4"] — while
      |  `cargo run -- "3 4 + 2 *"` printing 14 is ONE argument, args ["3 4 + 2 *"]. Quotes that
      |  DELIMIT a value are not part of it: no surrounding quotes in the entries or in expect.
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

  /** Split a command line the way a shell does — whitespace separates, quotes group. No escapes
    * and no expansion: this reads the argument list a TASK wrote, and a task needing `\$` in an
    * example is past the point where a derived one-line check helps.
    */
  def shellLex(s: String): List[String] =
    val out = scala.collection.mutable.ListBuffer.empty[String]
    val cur = StringBuilder()
    var quote: Option[Char] = None
    var has = false
    for c <- s do
      quote match
        case Some(q) if c == q => quote = None
        case Some(_)           => cur += c
        case None if c == '"' || c == '\'' =>
          quote = Some(c); has = true
        case None if c.isWhitespace =>
          if has || cur.nonEmpty then
            out += cur.toString; cur.clear(); has = false
        case None => cur += c
    if has || cur.nonEmpty then out += cur.toString
    out.toList

  /** Does the task actually STATE this expected output?
    *
    * A task can say what a program must do without saying what it prints — `wordcount` reads a
    * data file and prints the top three words, and the answer is in the file, not in the task.
    * Asked to formalize that, the model invents an expectation: measured 2026-08-05 it produced
    * `a 3 / c 2 / d 2`, three lines that appear nowhere, and the derived check then demanded them.
    * **No correct program can pass that**, and both repair rounds went into fighting the check
    * instead of the compile errors in the way — 0/4 before the guard, 3/3 after.
    *
    * `checkable: false` is what the prompt asks for here and what the model does not always give,
    * so the guard is deterministic, exactly like [[taskArgvFor]]: the task is the source of truth
    * and the model may only point at it. Whitespace- and case-insensitive, because a task writes
    * ``prints `olleh` `` and a model may answer `olleh\n`.
    */
  def taskStates(task: String, expect: String): Boolean =
    def squeeze(s: String) = s.split("\\s+").filter(_.nonEmpty).mkString(" ").toLowerCase
    val e = squeeze(expect)
    // An empty expectation ("prints nothing") is real and cannot be looked up — not this route.
    e.nonEmpty && squeeze(task).contains(e)

  /** The arity of an example, taken from the TASK rather than from the model.
    *
    * The model is good at "what should this print" and bad at shell lexing — and lexing is the one
    * part we can do exactly, because the task already wrote the list with its quotes. Measured
    * 2026-08-04 in BOTH directions with the same 4B model: asked for a single string it merged
    * `cargo run -- 3 4` into one argument; asked for a list it split `cargo run -- "3 4 + 2 *"`
    * into five. Either answer fails a program that does exactly what the task asked.
    *
    * So lex what follows `cargo run --` and take the shortest prefix whose words are the value the
    * model reported. `None` when the task states no such example — then the model's list stands.
    */
  def taskArgvFor(task: String, joined: String): Option[List[String]] =
    val want = joined.split("\\s+").filter(_.nonEmpty).toList
    if want.isEmpty then None
    else
      val marker = "cargo run --"
      var from = task.indexOf(marker)
      var found: Option[List[String]] = None
      while from >= 0 && found.isEmpty do
        val tail = task.substring(from + marker.length).linesIterator.nextOption().getOrElse("")
        val lexed = shellLex(tail)
        found = (1 to lexed.length).view
          .map(n => lexed.take(n))
          .find(prefix => prefix.flatMap(_.split("\\s+").filter(_.nonEmpty)) == want)
        from = task.indexOf(marker, from + 1)
      found

  /** A `cargo run` check that SAYS what it saw. A bare `[ "$(cargo run …)" = X ]` fails silently,
    * which leaves the repair round with an empty error on exactly the mismatches that matter.
    */
  def cargoRunFragment(arg: String, expect: String): String =
    cargoRunFragmentArgs(List(arg), expect)

  /** The same check for a program invoked with SEVERAL arguments.
    *
    * Arity is part of the criterion and the single-string form could not express it. Measured
    * end-to-end 2026-08-04 by THIS implementation: for "cargo run -- 3 4 must print 7" the model
    * answered `arg = "3 4"`, which quotes into one literal, so the check ran
    * `cargo run -q -- '3 4'` against a correct two-argument program — exit 1, both repair rounds
    * spent, FAILED reported on work that was right. A false negative is the expensive kind.
    */
  def cargoRunFragmentArgs(args: List[String], expect: String): String =
    val a = args.map(x => shquote(unquote(x))).mkString(" ")
    val shown = shquote(args.map(unquote).mkString(" "))
    val e = shquote(unquote(expect))
    s"""{ out=$$(cargo run -q -- $a) && [ "$$out" = $e ] || """ +
      s"""{ printf 'cargo run -- %s printed <%s>; expected <%s>\\n' $shown "$$out" $e >&2; exit 1; }; }"""

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
          // `args` carries the arity the single string could not; `arg` stays valid as ONE argument.
          val argv = r.obj
            .get("args")
            .flatMap(_.arrOpt)
            .map(_.toList.flatMap(_.strOpt))
            .orElse(r.obj.get("arg").flatMap(_.strOpt).map(List(_)))
            .getOrElse(Nil)
          for
            e <- r.obj.get("expect").flatMap(_.strOpt)
            if argv.nonEmpty
            // An expectation the task never states is the model inventing the answer.
            if taskStates(task, e)
          yield
            // Arity comes from the task's own punctuation when the task states the example.
            val joined = argv.map(unquote).mkString(" ")
            cargoRunFragmentArgs(taskArgvFor(task, joined).getOrElse(argv), e)
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
