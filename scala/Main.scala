package nadia

import agent.{AgentLoop, Budget, Endpoint, HttpModelClient, ModelClient, Observer, Outcome, Stop, Tool}
import scala.io.StdIn
import scala.util.Try

/** Batch and interactive front-ends over one loop.
  *
  * Batch is the contract `rozum:scripts/bench/agentic.sh` needs: a CLI on PATH, a prompt
  * as an argument, work done in the current directory, and an exit code that separates
  * "finished" from "gave up" from "the gateway is down" — the harness reads rc=2 as
  * infrastructure rather than a bad model, and conflating the two is how a dead gateway
  * gets recorded as a model failure.
  */
object Main:

  private val usage =
    """nadia — a coding agent on a local model (Scala 3)
      |
      |USAGE:
      |    nadia run <task>      run one task headlessly in the current directory
      |    nadia chat            interactive session (default with no arguments)
      |
      |OPTIONS:
      |    --workspace <DIR>     where the agent may act    [default: current directory]
      |    --gateway <URL>       gateway base URL           [env: OPENAI_BASE_URL / ROZUM_GATEWAY_URL]
      |    --model <ID>          model id                   [env: NADIA_MODEL]
      |    --max-steps <N>       model round-trips per task [default: 24]
      |    --allow-net           let `bash` reach the network
      |    --no-confine          do not wrap `bash` in sandbox-exec
      |    --json                batch: print the full result as JSON
      |
      |EXIT CODES (batch):  0 finished   1 budget exhausted   2 gateway failure""".stripMargin

  private final case class Opts(
      workspace: String = System.getProperty("user.dir"),
      gateway: String = Endpoint.fromEnv(),
      model: String = sys.env.getOrElse("NADIA_MODEL", "local"),
      maxSteps: Int = 24,
      allowNet: Boolean = false,
      confine: Boolean = true,
      json: Boolean = false
  )

  private def parse(args: List[String]): Either[String, (String, String, Opts)] =
    def loop(rest: List[String], mode: Option[String], task: List[String], o: Opts): Either[String, (String, String, Opts)] =
      rest match
        case Nil => Right((mode.getOrElse("chat"), task.reverse.mkString(" "), o))
        case ("-h" | "--help") :: _        => Left(usage)
        case "--allow-net" :: t            => loop(t, mode, task, o.copy(allowNet = true))
        case "--no-confine" :: t           => loop(t, mode, task, o.copy(confine = false))
        case "--json" :: t                 => loop(t, mode, task, o.copy(json = true))
        case "--workspace" :: v :: t       => loop(t, mode, task, o.copy(workspace = v))
        case "--gateway" :: v :: t         => loop(t, mode, task, o.copy(gateway = Endpoint.withV1(v)))
        case "--model" :: v :: t           => loop(t, mode, task, o.copy(model = v))
        case "--max-steps" :: v :: t       =>
          Try(v.toInt).toEither.left.map(_ => s"--max-steps $v: not a number")
            .flatMap(n => loop(t, mode, task, o.copy(maxSteps = n)))
        case flag :: Nil if flag.startsWith("--") => Left(s"$flag needs a value")
        case flag :: _ if flag.startsWith("-")    => Left(s"unknown option $flag\n\n$usage")
        case word :: t if mode.isEmpty            => loop(t, Some(word), task, o)
        case word :: t                            => loop(t, mode, word :: task, o)
    loop(args, None, Nil, Opts())

  def main(argv: Array[String]): Unit =
    parse(argv.toList) match
      case Left(msg) =>
        Console.err.println(msg)
        sys.exit(if msg.startsWith("nadia —") then 0 else 2)
      case Right((mode, task, o)) =>
        Sandbox.at(o.workspace) match
          case Left(e) =>
            Console.err.println(s"nadia: $e")
            sys.exit(2)
          case Right(base) =>
            val sb = base.copy(
              allowNet = o.allowNet,
              confine = o.confine && System.getProperty("os.name").toLowerCase.contains("mac")
            )
            val client = HttpModelClient(Endpoint(o.gateway, o.model))
            val tools = Tools.all(sb)
            val budget = Budget(maxSteps = o.maxSteps)
            mode match
              case "run" if task.isEmpty =>
                Console.err.println(s"nadia run needs a task\n\n$usage")
                sys.exit(2)
              case "run"  => batch(client, sb, tools, budget, task, o)
              case "chat" => repl(client, sb, tools, budget, o)
              case other =>
                Console.err.println(s"unknown mode `$other`\n\n$usage")
                sys.exit(2)

  private def batch(client: ModelClient, sb: Sandbox, tools: List[Tool], b: Budget, task: String, o: Opts): Unit =
    val r = AgentLoop.run(client, systemPrompt(sb.root.toString), task, tools, b)
    if o.json then println(ujson.write(asJson(r)))
    else println(r.text)
    r.stop match
      case Stop.Done => sys.exit(0)
      case Stop.BudgetSteps | Stop.BudgetTime =>
        Console.err.println(s"nadia: budget exhausted after ${r.steps} steps")
        sys.exit(1)
      case Stop.Error(m) =>
        Console.err.println(s"nadia: $m")
        sys.exit(2)

  private def asJson(r: Outcome): ujson.Value =
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

  /** Line-based on purpose: it works over ssh, in a pipe and inside tmux without a
    * terminal-control layer, and what a coding agent's UI has to get right is showing
    * WHAT IT DID, not drawing panes.
    */
  private def repl(client: ModelClient, sb: Sandbox, tools: List[Tool], b: Budget, o: Opts): Unit =
    var conversation = List(agent.Wire.system(systemPrompt(sb.root.toString)))
    println(s"nadia · ${o.model} · ${sb.root}")
    println(s"${tools.length} tools · /help for commands · ctrl-d to exit")
    if !o.allowNet then println("network denied to `bash` (--allow-net to permit)")

    val live = new Observer:
      override def onToolCall(name: String, args: ujson.Value): Unit =
        println(s"\n  ⏺ $name ${describe(name, args)}")
      override def onToolResult(name: String, error: Option[String]): Unit =
        error.foreach(e => println(s"    ✗ ${oneLine(e)}"))

    var running = true
    while running do
      print("\n› ")
      Console.out.flush()
      Option(StdIn.readLine()) match
        case None => running = false
        case Some(raw) =>
          val line = raw.trim
          if !System.console().isInstanceOf[java.io.Console] then println(line)
          if line.isEmpty then ()
          else if line == "/quit" || line == "/exit" then running = false
          else if line == "/help" then
            println("/tools  list the tools\n/clear  forget the conversation\n/quit   exit")
          else if line == "/tools" then
            tools.foreach(t => println(f"  ${t.name}%-11s ${t.description.takeWhile(_ != '.')}"))
          else
            // resume, not run: the transcript from the previous turn is the context for
            // this one, so the agent remembers the session and the gateway keeps its KV
            // prefix instead of re-prefilling the whole conversation.
            val r = AgentLoop.resume(client, conversation :+ agent.Wire.user(line), tools, b, live)
            conversation = r.transcript
            if r.text.nonEmpty then println(s"\n${r.text}")
            r.stop match
              case Stop.Done           => ()
              case Stop.BudgetSteps    => println(s"\n[stopped: step budget after ${r.steps} steps]")
              case Stop.BudgetTime     => println("\n[stopped: time budget]")
              case Stop.Error(m)       => println(s"\n[gateway error: $m]")

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
