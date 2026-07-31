package nadia.rozum

import agent.{AgentLoop, Budget, ModelClient, Observer, Outcome, Stop, Tool, Wire}
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
  def batch(client: ModelClient, sb: Sandbox, budget: Budget, task: String, asJson: Boolean): Int =
    val r = AgentLoop.run(client, systemPrompt(sb.root.toString), task, Tools.all(sb), budget)
    if asJson then println(ujson.write(report(r))) else println(r.text)
    r.stop match
      case Stop.Done => 0
      case Stop.BudgetSteps | Stop.BudgetTime =>
        Console.err.println(s"nadia: budget exhausted after ${r.steps} steps")
        1
      case Stop.Error(m) =>
        Console.err.println(s"nadia: $m")
        2

  /** Interactive. Line-based on purpose: it works over ssh, in a pipe and inside tmux
    * without a terminal-control layer, and what a coding agent's UI has to get right is
    * showing WHAT IT DID, not drawing panes.
    */
  def chat(client: ModelClient, sb: Sandbox, budget: Budget, model: String, allowNet: Boolean): Int =
    val tools = Tools.all(sb)
    var conversation = List(Wire.system(systemPrompt(sb.root.toString)))

    println(s"nadia · $model · ${sb.root}")
    println(s"${tools.length} tools · /help for commands · ctrl-d to exit")
    if !allowNet then println("network denied to `bash` (--allow-net to permit)")

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
          else if line == "/quit" || line == "/exit" then running = false
          else if line == "/help" then
            println("/tools  list the tools\n/clear  forget the conversation\n/quit   exit")
          else if line == "/tools" then
            tools.foreach(t => println(f"  ${t.name}%-11s ${t.description.takeWhile(_ != '.')}"))
          else if line == "/clear" then
            conversation = List(Wire.system(systemPrompt(sb.root.toString)))
            println("context cleared")
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
      case _            => s("path")

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
