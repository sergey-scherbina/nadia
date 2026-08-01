package nadia.rozum

/** The REPL's command table — one place that knows a command exists, what it is called
  * with, and what it does.
  *
  * `SPEC.md` §4.2. The clause exists because the help used to be a string literal next to
  * the `if/else` that dispatches: two lists that must agree and therefore eventually don't.
  * Here the help is *rendered from* the table, so the drift has nowhere to live.
  */
object Commands:

  /** `format` is the literal call shape WITH its arguments, because what a user does not
    * know at the moment they ask is the arguments, not the name they just typed. */
  final case class Command(name: String, format: String, short: String, long: String)

  val all: List[Command] = List(
    Command(
      "/help",
      "help | ? | /help [command]",
      "this list, or one command in detail",
      "With no argument: every command, with its format and one line. With a command name " +
        "(the leading slash is optional — `help tools` and `? /tools` are the same question) " +
        "it prints that command's format, its short line and this paragraph. `help` and `?` " +
        "work without the slash: at a prompt they are a question for the program, and sending " +
        "them to the model would spend a turn to be told what nadia already knows."
    ),
    Command(
      "/tools",
      "/tools",
      "list the tools the model can call",
      "The six built-ins, plus any tool from an MCP server connected with --mcp (those are " +
        "named mcp__<server>__<tool>). Every tool listed here costs schema tokens in every " +
        "request of every step, which is why the set is small and why MCP servers are opt-in " +
        "per run rather than loaded because a config file exists."
    ),
    Command(
      "/mcp",
      "/mcp",
      "the connected MCP servers and their tools",
      "Shows the tools each connected server contributed. An MCP server is a separate process " +
        "with its own access to the machine: the path jail and the confinement described at " +
        "startup constrain nadia, not it."
    ),
    Command(
      "/clear",
      "/clear",
      "forget the conversation",
      "Drops the transcript and starts a fresh context; the workspace is untouched. A small " +
        "model follows a short, clean context better than a long one it has half-forgotten, so " +
        "this is usually better than hoping a drifting thread recovers."
    ),
    Command(
      "/quit",
      "/quit | /exit",
      "exit nadia",
      "Leaves the session. Ctrl-D does the same. Connected MCP servers are child processes and " +
        "end with it."
    )
  )

  /** Look a command up by the name a user typed: with or without the leading slash, any
    * case. `?` and `help` both resolve to `/help`, because that is the command they are. */
  def find(name: String): Option[Command] =
    val bare = name.trim.stripPrefix("/").toLowerCase
    val key = if bare == "?" || bare == "help" then "help" else bare
    all.find(_.name.stripPrefix("/") == key)

  /** The bare `help` page: every command as `format — short`, aligned. */
  def helpAll: String =
    val width = all.map(_.format.length).maxOption.getOrElse(0)
    all.map(c => s"  ${c.format.padTo(width, ' ')}  ${c.short}").mkString("\n") +
      "\n\n  help <command> for one of them in detail. Anything else is a message to the model."

  /** `help <command>`: format, short, then the paragraph. `None` when it is not a command —
    * the caller answers with the names, so a typo does not hide the answer under a page. */
  def helpOne(name: String): Option[String] =
    find(name).map(c => s"  ${c.format}\n  ${c.short}\n\n${wrap(c.long, 76, "  ")}")

  /** The answer to a name that is not a command: say which, then the names only. */
  def unknown(name: String): String =
    s"no command `${name.trim}`. There is: ${all.map(_.name).mkString(" ")}"

  /** Is this line a request for help, and for which command?
    *
    *   - `Some(None)` — the whole list
    *   - `Some(Some(name))` — one command in detail
    *   - `None` — not a help request
    *
    * Only as the whole line's first word: `help me refactor this` asks the model, not nadia.
    */
  def helpRequest(line: String): Option[Option[String]] =
    val t = line.trim
    val (head, rest) = t.split("\\s+", 2) match
      case Array(h)    => (h, "")
      case Array(h, r) => (h, r.trim)
      case _           => ("", "")
    if Set("help", "?", "/help", "/?").contains(head.toLowerCase) then
      Some(Option(rest).filter(_.nonEmpty))
    else None

  /** Soft-wrap: the long text is one string, and a terminal is not obliged to be wide. */
  private def wrap(text: String, width: Int, indent: String): String =
    val (lines, last) = text.split("\\s+").foldLeft((List.empty[String], "")) {
      case ((acc, line), word) =>
        if line.nonEmpty && line.length + 1 + word.length > width then (acc :+ (indent + line), word)
        else if line.isEmpty then (acc, word)
        else (acc, s"$line $word")
    }
    (if last.isEmpty then lines else lines :+ (indent + last)).mkString("\n")
