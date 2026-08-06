package nadia.rozum

import agent.{AgentLoop, Budget, ModelClient, Observer, Outcome, Stop, Wire}
import scala.io.StdIn
import scala.util.Try

/** The agent itself: the SDK's loop, pointed at a rozum gateway, holding nadia's tools.
  *
  * Everything above this is generic (`agent`) and everything at the top level is argument
  * parsing. What is here is the part that is specifically *this* agent against *that*
  * gateway: which tools exist, what the model is told, how a run is reported, and which
  * exit codes a run produces.
  */
object Nadia:

  /** Headless. The exit codes are a contract with `rozum:scripts/bench/agentic.sh`, which
    * reads rc=2 as infrastructure rather than a bad model — conflating the two is how a
    * dead gateway gets recorded as a model failure.
    */
  def batch(
      client: ModelClient,
      sb: Sandbox,
      budget: Budget,
      task: String,
      asJson: Boolean,
      mcp: List[Mcp.Connected] = Nil
  ): Int =
    val tools = Tools.all(sb) ++ mcp.flatMap(_.tools)
    // What "done" means, decided before the run (SPEC §3.1). One model call; None when the task
    // has no machine-checkable criterion, which is an answer rather than a failure.
    val check = Gate.derive(client, task, sb.root)
    check.foreach(c => Console.err.println(s"nadia: acceptance check — $c"))

    var r = AgentLoop.run(client, systemPrompt(sb.root.toString), task, tools, budget)
    var gate = Gate.Report()
    var round = 0
    var repairing = true
    // `Gate.rounds` REPAIRS and one check MORE than that. Checking only before each repair leaves
    // the last attempt unjudged — measured in the Rust twin, three runs in six then reported a
    // failed check about code that builds (BUG-027). "The check decides" cannot hold while the
    // final attempt is never checked.
    while repairing do
      Gate.check(client, task, sb.root, check, r.stop == Stop.Done) match
        case (rep, _) if round >= Gate.rounds =>
          gate = rep.copy(rounds = round)
          repairing = false
        case (rep, Some(prompt)) =>
          gate = rep.copy(rounds = round)
          round += 1
          Console.err.println(s"nadia: check failed — repair round $round")
          // A turn cut by the loop guard ends with the guard's refusal, and a small model answers
          // the next turn by quoting it. Resuming that transcript spends the round before it
          // starts, so a repair after a break begins CLEAN: the task and the check output are all
          // the next attempt needs.
          r =
            if r.loopBroken then
              Console.err.println("nadia: …the last turn was cut for repetition — restarting clean")
              AgentLoop.run(client, systemPrompt(sb.root.toString), s"$task\n\n$prompt", tools, budget)
            else AgentLoop.resume(client, r.transcript :+ Wire.user(prompt), tools, budget)
        case (rep, None) =>
          gate = rep.copy(rounds = round)
          repairing = false
    Console.err.println(s"nadia: ${gate.summary}")

    if asJson then println(ujson.write(report(r))) else println(r.text)
    // A run whose check FAILED did not finish, whatever the model says about it. Exit 1 — the
    // "gave up" code the harness reads — because success is not the model's to declare.
    if gate.passed.contains(false) then return 1
    r.stop match
      case Stop.Done => 0
      case Stop.BudgetSteps | Stop.BudgetTime =>
        // The check decides in BOTH directions. An agent that satisfied the acceptance criterion
        // and then ran out of steps has done the task; calling that a failure would be the same
        // mistake as trusting a model that says it finished.
        if gate.passed.contains(true) then
          Console.err.println(s"nadia: budget exhausted after ${r.steps} steps — but the check passed")
          0
        else
          Console.err.println(s"nadia: budget exhausted after ${r.steps} steps")
          1
      case Stop.Error(m) =>
        Console.err.println(s"nadia: $m")
        2

  /** Interactive. Line-based on purpose: it works over ssh, in a pipe and inside tmux
    * without a terminal-control layer, and what a coding agent's UI has to get right is
    * showing WHAT IT DID, not drawing panes.
    */
  def chat(
      client: ModelClient,
      sb: Sandbox,
      budget: Budget,
      model: String,
      mcp: List[Mcp.Connected] = Nil
  ): Int =
    val tools = Tools.all(sb) ++ mcp.flatMap(_.tools)
    var conversation = List(Wire.system(systemPrompt(sb.root.toString)))

    println(s"nadia · $model · ${sb.root}")
    println(s"${tools.length} tools · help for commands · ctrl-d to exit")
    // The containment is stated, not implied. Which mechanism is in force depends on where
    // this is running, and an operator who assumes the wrong one is the failure mode.
    println(sb.confinement.describe(sb.allowNet))

    var running = true
    while running do
      print("\n› ")
      Console.out.flush()
      Option(StdIn.readLine()) match
        case None => running = false
        case Some(raw) =>
          val line = raw.trim
          // Under a pipe there is no typed echo, so the prompt and the first line of
          // output collide and the transcript becomes unreadable. Echo it ourselves.
          if System.console() == null then println(line)
          if line.isEmpty then ()
          // `help`, `?` and `/?` are the same command as `/help`, with or without an
          // argument: at a prompt they are a question for the program, and spending a model
          // turn to answer what nadia already knows is seconds on a local model (SPEC §4.2).
          else if Commands.helpRequest(line).isDefined then
            Commands.helpRequest(line).get match
              case None       => println(Commands.helpAll)
              case Some(name) => println(Commands.helpOne(name).getOrElse(Commands.unknown(name)))
          else if line == "/quit" || line == "/exit" then running = false
          else if line == "/tools" then
            tools.foreach(t => println(f"  ${t.name}%-11s ${t.description.takeWhile(_ != '.')}"))
          else if line == "/mcp" then
            val names = tools.map(_.name).filter(Mcp.isMcpTool)
            if names.isEmpty then
              println(
                "no MCP server connected — start nadia with --mcp <name> " +
                  "(`nadia mcp list` shows what is configured)"
              )
            else
              println(s"${names.length} MCP tool(s), OUTSIDE the workspace jail:")
              names.foreach(n => println(s"  $n"))
          else if line == "/clear" then
            conversation = List(Wire.system(systemPrompt(sb.root.toString)))
            println("context cleared")
          // Anything else starting with a slash is a mistyped command, not a message: send
          // it to the model and it answers a question nobody asked. Last, so every real
          // command above is still reachable.
          else if line.startsWith("/") then println(Commands.unknown(line))
          else
            // resume, not run: the previous transcript is this turn's context, so the
            // session remembers and the gateway keeps its KV prefix instead of
            // re-prefilling the whole conversation.
            val r = AgentLoop.resume(client, conversation :+ Wire.user(line), tools, budget, Live)
            conversation = r.transcript
            if r.text.nonEmpty then println(s"\n${r.text}")
            r.stop match
              case Stop.Done        => ()
              case Stop.BudgetSteps => println(s"\n[stopped: step budget after ${r.steps} steps]")
              case Stop.BudgetTime  => println("\n[stopped: time budget]")
              case Stop.Error(m)    => println(s"\n[gateway error: $m]")
    0

  /** Renders the run as it happens. Without it a turn is silent for its whole duration —
    * up to a minute on the slower benchmark tasks — and an agent that prints nothing is
    * indistinguishable from one that has hung.
    */
  private object Live extends Observer:
    override def onToolCall(name: String, args: ujson.Value): Unit =
      println(s"\n  ⏺ $name ${describe(name, args)}")
    override def onToolResult(name: String, error: Option[String]): Unit =
      error.foreach(e => println(s"    ✗ ${oneLine(e)}"))

  /** What a call actually does, in one line — the command, or the file and the size of the
    * change. A raw JSON blob is not something anyone reads.
    */
  private def describe(tool: String, args: ujson.Value): String =
    def s(k: String) = Try(args(k).str).getOrElse("")
    tool match
      case "bash"       => oneLine(s("command"))
      case "write_file" => s"${s("path")} (${s("content").length} bytes)"
      case "edit_file"  => s"${s("path")} — ${s("old_string").linesIterator.size} line(s)"
      case "grep"       => s("pattern")
      // An MCP call: which server, which of its tools, and the arguments clipped. The raw
      // JSON of an unknown schema is all there is, but a wall of it is not read by anyone.
      case t if Mcp.isMcpTool(t) =>
        val rest = t.stripPrefix("mcp__")
        val (server, tool) = rest.span(_ != '_')
        s"$server · ${tool.stripPrefix("__")} ${oneLine(ujson.write(args))}"
      case _ => s("path")

  private def oneLine(s: String): String =
    val flat = s.replace('\n', ' ')
    if flat.length > 96 then flat.take(96) + "…" else flat

  private def report(r: Outcome): ujson.Value =
    ujson.Obj(
      "text" -> r.text,
      "stop" -> r.stop.toString,
      "steps" -> r.steps,
      "operations" -> ujson.Arr.from(r.operations.map { op =>
        ujson.Obj(
          "tool" -> op.tool,
          "input" -> op.input,
          "ok" -> op.output.isRight,
          "output" -> op.output.fold(ujson.Str(_), identity)
        )
      })
    )
