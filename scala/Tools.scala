package nadia

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.{Try, Using}

/** A tool the model may call: the schema it advertises, and the handler that runs it.
  *
  * `run` returns `Left(message)` for a recoverable failure. That message is not an error
  * in the exception sense — it is the next prompt, and it is written to be read by the
  * model: what went wrong, and what to do differently.
  */
final case class Tool(
    name: String,
    description: String,
    schema: ujson.Value,
    run: ujson.Value => Either[String, ujson.Value]
)

object Tools:

  /** Byte ceiling for one tool result. A `cargo test` on a broken crate emits megabytes;
    * feeding that back burns the context window in a single step and the agent loses the
    * thread. Truncation is always announced — a silent cut makes the model confidently
    * wrong about what it saw.
    */
  private val MaxOut = 8000

  private def clip(s: String): (String, Boolean) =
    if s.length <= MaxOut then (s, false) else (s.take(MaxOut), true)

  private def str(v: ujson.Value, key: String): Either[String, String] =
    Try(v(key).str).toOption.toRight(s"missing required string argument `$key`")

  private def optStr(v: ujson.Value, key: String): Option[String] =
    Try(v(key).str).toOption

  private def prop(t: String, desc: String): ujson.Value =
    ujson.Obj("type" -> t, "description" -> desc)

  private def schemaOf(props: (String, ujson.Value)*)(required: String*): ujson.Value =
    ujson.Obj(
      "type" -> "object",
      "properties" -> ujson.Obj.from(props),
      "required" -> ujson.Arr.from(required.map(ujson.Str(_))),
      "additionalProperties" -> false
    )

  /** The six tools, and nothing else.
    *
    * The bar for a seventh: it must enable a task class that is *impossible* with these,
    * not merely more convenient. Every tool costs schema tokens in every request of every
    * step and dilutes selection for a small model.
    */
  def all(sb: Sandbox): List[Tool] =
    List(readFile(sb), writeFile(sb), editFile(sb), listDir(sb), grep(sb), bash(sb))

  private def readFile(sb: Sandbox) = Tool(
    "read_file",
    "Read a text file from the workspace. Read a file before editing it — `edit_file` " +
      "needs `old_string` to match the file exactly.",
    schemaOf("path" -> prop("string", "Path relative to the workspace root."))("path"),
    args =>
      for
        p <- str(args, "path")
        full <- sb.resolve(p)
        text <- Try(Files.readString(full)).toEither.left.map(e => s"read $p: ${e.getMessage}")
      yield
        val numbered = text.linesIterator.zipWithIndex
          .map((l, i) => f"${i + 1}%6d\t$l")
          .mkString("\n")
        val (body, cut) = clip(numbered)
        ujson.Obj("path" -> p, "content" -> body, "truncated" -> cut)
  )

  private def writeFile(sb: Sandbox) = Tool(
    "write_file",
    "Create a file, or replace its entire contents. Use `edit_file` to change part of an " +
      "existing file — writing a whole file to change one line loses everything you did " +
      "not repeat.",
    schemaOf(
      "path" -> prop("string", "Path relative to the workspace root."),
      "content" -> prop("string", "The complete new contents of the file.")
    )("path", "content"),
    args =>
      for
        p <- str(args, "path")
        content <- str(args, "content")
        full <- sb.resolve(p)
        _ <- Try {
          Option(full.getParent).foreach(Files.createDirectories(_))
          Files.writeString(full, content)
        }.toEither.left.map(e => s"write $p: ${e.getMessage}")
      yield ujson.Obj("written" -> p, "bytes" -> content.length)
  )

  private def editFile(sb: Sandbox) = Tool(
    "edit_file",
    "Replace one exact occurrence of `old_string` with `new_string`. `old_string` must " +
      "appear EXACTLY ONCE — include enough surrounding context to make it unique. If it " +
      "matches zero or several times the edit is refused and nothing changes.",
    schemaOf(
      "path" -> prop("string", "Path relative to the workspace root."),
      "old_string" -> prop("string", "Text to replace, copied exactly from the file."),
      "new_string" -> prop("string", "Replacement text.")
    )("path", "old_string", "new_string"),
    args =>
      for
        p <- str(args, "path")
        oldS <- str(args, "old_string")
        newS <- str(args, "new_string")
        full <- sb.resolve(p)
        text <- Try(Files.readString(full)).toEither.left.map(e => s"read $p: ${e.getMessage}")
        // The single-occurrence rule is the whole point of this tool: replacing the first
        // of several identical lines lets a model report success for a fix it did not make.
        _ <- occurrences(text, oldS) match
          case 1 => Right(())
          case 0 =>
            Left(
              s"old_string matched 0 times in $p — re-read the file: the text you quoted is " +
                "not there (check whitespace and indentation)."
            )
          case n =>
            Left(
              s"old_string matched $n times in $p — it must match exactly once. Include more " +
                "surrounding lines to make it unique."
            )
        _ <- Try(Files.writeString(full, text.replaceFirst(java.util.regex.Pattern.quote(oldS), java.util.regex.Matcher.quoteReplacement(newS))))
          .toEither.left.map(e => s"write $p: ${e.getMessage}")
      yield ujson.Obj("path" -> p, "replaced" -> 1)
  )

  private def listDir(sb: Sandbox) = Tool(
    "list_dir",
    "List the entries of a directory in the workspace.",
    schemaOf("path" -> prop("string", "Directory relative to the workspace root."))(),
    args =>
      val p = optStr(args, "path").filter(_.nonEmpty).getOrElse(".")
      for
        full <- sb.resolve(p)
        entries <- Try(Using.resource(Files.list(full))(_.iterator.asScala.toList))
          .toEither.left.map(e => s"list $p: ${e.getMessage}")
      yield ujson.Obj(
        "path" -> p,
        "entries" -> ujson.Arr.from(
          entries
            .sortBy(_.getFileName.toString)
            .map(e => ujson.Obj("name" -> e.getFileName.toString, "dir" -> Files.isDirectory(e)))
        )
      )
  )

  private def grep(sb: Sandbox) = Tool(
    "grep",
    "Search the workspace with a regular expression. Returns `path:line:text` for each " +
      "match. Use it to find where something is defined before reading a file.",
    schemaOf(
      "pattern" -> prop("string", "Regular expression, e.g. `fn \\w+_handler` or `TODO`."),
      "path" -> prop("string", "File or directory to search. Defaults to the workspace.")
    )("pattern"),
    args =>
      for
        pattern <- str(args, "pattern")
        // A bad pattern is the model's to fix, so the regex error goes back verbatim
        // rather than being flattened into "no matches" — which is what a literal
        // fallback would silently look like.
        re <- Try(pattern.r).toEither.left
          .map(e => s"`$pattern` is not a valid regular expression: ${e.getMessage}")
        start <- sb.resolve(optStr(args, "path").filter(_.nonEmpty).getOrElse("."))
      yield
        val hits = StringBuilder()
        walk(start).foreach { f =>
          Try(Files.readString(f)).foreach { text =>
            val rel = sb.root.relativize(f).toString
            text.linesIterator.zipWithIndex.foreach { (line, i) =>
              if re.findFirstIn(line).isDefined then hits.append(s"$rel:${i + 1}:${line.stripTrailing}\n")
            }
          }
        }
        val (body, cut) = clip(hits.toString)
        ujson.Obj("pattern" -> pattern, "matches" -> body, "truncated" -> cut)
  )

  /** Skipping build and VCS trees is not a nicety: `target/` alone can be hundreds of
    * thousands of files, and the walk would dominate the step.
    */
  private val Skip = Set("target", ".git", "node_modules")

  private def walk(start: Path): List[Path] =
    if Files.isRegularFile(start) then List(start)
    else if !Files.isDirectory(start) then Nil
    else
      Try(Using.resource(Files.list(start))(_.iterator.asScala.toList)).getOrElse(Nil).flatMap { p =>
        if Files.isDirectory(p) then
          if Skip.contains(p.getFileName.toString) then Nil else walk(p)
        else List(p)
      }

  private def bash(sb: Sandbox) = Tool(
    "bash",
    "Run a shell command in the workspace and return its output. This is how you build, " +
      "test and run things (`cargo build`, `cargo test`, `cargo run`). Read the output " +
      "before claiming success — a command that failed did not do what you asked.",
    schemaOf(
      "command" -> prop("string", "Shell command line to run."),
      "timeout_ms" -> prop("integer", "Kill the command after this many milliseconds.")
    )("command"),
    args =>
      str(args, "command").map { command =>
        val limit = Try(args("timeout_ms").num.toLong).toOption
          .map(java.time.Duration.ofMillis)
        val r = sb.exec(command, limit)
        val (out, outCut) = clip(r.stdout)
        val (err, errCut) = clip(r.stderr)
        ujson.Obj(
          "exit_code" -> r.exitCode,
          "stdout" -> out,
          "stderr" -> err,
          "timed_out" -> r.timedOut,
          "truncated" -> (outCut || errCut)
        )
      }
  )

  def occurrences(hay: String, needle: String): Int =
    if needle.isEmpty then 0
    else
      var count = 0
      var i = hay.indexOf(needle)
      while i >= 0 do
        count += 1
        i = hay.indexOf(needle, i + needle.length)
      count
