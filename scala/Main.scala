package nadia

import agent.Budget
import nadia.cloud.Provider
import nadia.rozum.{Confinement, Gateway, Mcp, Nadia, Sandbox}

/** The runner: read the arguments, build the pieces, hand over, exit.
  *
  * Nothing else belongs here. The agent loop is `agent` (generic, with no rozum in it),
  * the agent itself is `nadia.rozum` (the tools, the prompt, the gateway wiring), and this
  * file is the seam between a command line and those two — small enough to read in one
  * sitting, which is the only way anyone ever checks what a flag actually does.
  */
object Main:

  private val usage =
    """nadia — a coding agent on a local model (Scala 3)
      |
      |USAGE:
      |    nadia run <task>      run one task headlessly in the current directory
      |    nadia chat            interactive session (default with no arguments)
      |    nadia mcp list        the configured MCP servers (--probe also lists their tools)
      |    nadia help            this text
      |
      |OPTIONS:
      |    --workspace <DIR>     where the agent may act    [default: current directory]
      |    --gateway <URL>       gateway base URL           [env: OPENAI_BASE_URL / ROZUM_GATEWAY_URL]
      |    --model <ID>          model id                   [env: NADIA_MODEL]
      |    --max-steps <N>       model round-trips per task [default: 24]
      |    --allow-net           let `bash` reach the network (no effect in a container)
      |    --no-confine          do not wrap `bash` in sandbox-exec
      |    --json                batch: print the full result as JSON
      |
      |MCP (tools nadia does not ship — opt-in per run, because every tool costs schema
      |tokens in every request; they are named mcp__<server>__<tool> and run OUTSIDE the jail):
      |    --mcp <NAME>          connect this server's tools (repeatable)
      |    --mcp-all             connect every server in the config
      |    --mcp-config <PATH>   [default: <workspace>/.mcp.json, else ~/.config/nadia/mcp.json]
      |    --probe               `mcp list`: connect each server and list its tools
      |
      |WHERE THE MODEL RUNS:
      |    --provider <NAME>     local | openai | huggingface | bedrock | vertex
      |                                                              [env: NADIA_PROVIDER]
      |    --region <ID>         AWS region, or Vertex location      [env: AWS_REGION / GOOGLE_CLOUD_REGION]
      |    --project <ID>        Google Cloud project                [env: GOOGLE_CLOUD_PROJECT]
      |    --api-key-file <F>    bearer token, read from a file      [env: NADIA_API_KEY / OPENAI_API_KEY]
      |                                                              huggingface also reads HF_TOKEN
      |
      |    `local` is the default and needs no credential. There is no --api-key flag on
      |    purpose: a key passed as an argument is in `ps` output and in shell history.
      |
      |EXIT CODES (batch):  0 finished   1 budget exhausted   2 gateway failure""".stripMargin

  private final case class Opts(
      workspace: String = System.getProperty("user.dir"),
      gateway: String = Gateway.urlFromEnv(),
      model: String = Gateway.modelFromEnv(),
      provider: String = sys.env.getOrElse("NADIA_PROVIDER", "local"),
      region: Option[String] = None,
      project: Option[String] = None,
      keyFile: Option[String] = sys.env.get("NADIA_API_KEY_FILE"),
      maxSteps: Int = 24,
      allowNet: Boolean = false,
      confine: Boolean = true,
      json: Boolean = false,
      mcp: List[String] = Nil,
      mcpAll: Boolean = false,
      mcpConfig: Option[String] = None,
      probe: Boolean = false
  )

  private def parse(args: List[String]): Either[String, (String, String, Opts)] =
    def loop(
        rest: List[String],
        mode: Option[String],
        task: List[String],
        o: Opts
    ): Either[String, (String, String, Opts)] =
      rest match
        case Nil                    => Right((mode.getOrElse("chat"), task.reverse.mkString(" "), o))
        // `nadia help` is the same answer as `-h`: a user who guesses the word everybody
        // guesses should not be told that their guess is an unknown mode (SPEC §4.2).
        case ("-h" | "--help" | "help" | "?") :: _ => Left(usage)
        case "--allow-net" :: t     => loop(t, mode, task, o.copy(allowNet = true))
        case "--no-confine" :: t    => loop(t, mode, task, o.copy(confine = false))
        case "--json" :: t          => loop(t, mode, task, o.copy(json = true))
        case "--mcp-all" :: t       => loop(t, mode, task, o.copy(mcpAll = true))
        case "--probe" :: t         => loop(t, mode, task, o.copy(probe = true))
        // Repeatable: `--mcp a --mcp b` connects both, in the order given.
        case "--mcp" :: v :: t          => loop(t, mode, task, o.copy(mcp = o.mcp :+ v))
        case "--mcp-config" :: v :: t   => loop(t, mode, task, o.copy(mcpConfig = Some(v)))
        case "--workspace" :: v :: t    => loop(t, mode, task, o.copy(workspace = v))
        case "--gateway" :: v :: t      => loop(t, mode, task, o.copy(gateway = v))
        case "--model" :: v :: t        => loop(t, mode, task, o.copy(model = v))
        case "--provider" :: v :: t     => loop(t, mode, task, o.copy(provider = v))
        case "--region" :: v :: t       => loop(t, mode, task, o.copy(region = Some(v)))
        case "--project" :: v :: t      => loop(t, mode, task, o.copy(project = Some(v)))
        case "--api-key-file" :: v :: t => loop(t, mode, task, o.copy(keyFile = Some(v)))
        case "--max-steps" :: v :: t =>
          v.toIntOption
            .toRight(s"--max-steps $v: not a number")
            .flatMap(n => loop(t, mode, task, o.copy(maxSteps = n)))
        case flag :: Nil if flag.startsWith("--") => Left(s"$flag needs a value")
        case flag :: _ if flag.startsWith("-")    => Left(s"unknown option $flag\n\n$usage")
        case word :: t if mode.isEmpty            => loop(t, Some(word), task, o)
        case word :: t                            => loop(t, mode, word :: task, o)
    loop(args, None, Nil, Opts())

  def main(argv: Array[String]): Unit = sys.exit(run(argv.toList))

  private def run(argv: List[String]): Int =
    parse(argv) match
      case Left(msg) =>
        Console.err.println(msg)
        if msg.startsWith("nadia —") then 0 else 2
      case Right((mode, task, o)) =>
        Sandbox.at(o.workspace) match
          case Left(e) =>
            Console.err.println(s"nadia: $e")
            2
          case Right(base) =>
            val confinement = Confinement.select(requested = o.confine)
            val sb = base.copy(allowNet = o.allowNet, confinement = confinement)
            // Asking for confinement and not getting it is the one case worth interrupting
            // an unattended run for. It happens on plain Linux, which is where this binary
            // ends up the moment it leaves a Mac — and silence there reads as "confined".
            if o.confine && confinement == Confinement.Open then
              Console.err.println(s"nadia: warning — ${confinement.describe(o.allowNet)}")
            Provider.resolve(o.provider, o.gateway, o.model, o.region, o.project, o.keyFile) match
              case Left(e) =>
                Console.err.println(s"nadia: $e")
                2
              case Right(endpoint) =>
                // Only when a specific repository was named: `local` means "whatever you have
                // resident", so there is nothing to disagree with, and the benchmark harness —
                // which always passes it — pays no round trip.
                if Provider.servedLocally(o.provider, o.model) && o.model != "local" then
                  Gateway.residentWarning(endpoint.baseUrl, endpoint.model)
                    .foreach(w => Console.err.println(s"nadia: $w"))
                val client = Gateway.client(endpoint)
                val budget = Budget(maxSteps = o.maxSteps)
                mode match
                  case "run" if task.isEmpty =>
                    Console.err.println(s"nadia run needs a task\n\n$usage")
                    2
                  case "run" =>
                    withMcp(o, sb)(mcp => Nadia.batch(client, sb, budget, task, o.json, mcp))
                  case "chat" =>
                    withMcp(o, sb)(mcp => Nadia.chat(client, sb, budget, endpoint.model, mcp))
                  case "mcp" => mcpList(o, sb)
                  case other =>
                    Console.err.println(s"unknown mode `$other`\n\n$usage")
                    2

  /** Connect the MCP servers this run asked for, hand them to `body`, and close them after.
    *
    * Nothing asked for → nothing connected, whatever the config holds: the tools cost schema
    * tokens on every request of every step, so paying is the operator's decision. A named
    * server that will not start ends the run HERE, with its name — a run that silently lost
    * half its tools produces a confidently wrong answer (`SPEC.md` §2.1).
    */
  private def withMcp(o: Opts, sb: Sandbox)(body: List[Mcp.Connected] => Int): Int =
    if o.mcp.isEmpty && !o.mcpAll then body(Nil)
    else
      connectAll(o, sb) match
        case Left(e) =>
          Console.err.println(s"nadia: $e")
          2
        case Right(connected) =>
          connected.foreach(c => println(Mcp.connectedLine(c)))
          try body(connected)
          finally connected.foreach(_.client.close())

  private def connectAll(o: Opts, sb: Sandbox): Either[String, List[Mcp.Connected]] =
    Mcp
      .configPath(o.mcpConfig, sb.root)
      .toRight(
        "no MCP config found — looked for <workspace>/.mcp.json and ~/.config/nadia/mcp.json " +
          "(or pass --mcp-config)"
      )
      .flatMap(Mcp.load)
      .flatMap(all => Mcp.select(all, o.mcp, o.mcpAll))
      .flatMap { chosen =>
        chosen.foldLeft[Either[String, List[Mcp.Connected]]](Right(Nil)) { (acc, spec) =>
          acc.flatMap(got => Mcp.connect(spec).map(got :+ _))
        }
      }

  /** `nadia mcp list [--probe]` — what is configured, and with `--probe` what each server
    * actually serves, under the prefix its tools will carry. */
  private def mcpList(o: Opts, sb: Sandbox): Int =
    Mcp.configPath(o.mcpConfig, sb.root) match
      case None =>
        println(s"no MCP config — looked for ${sb.root}/.mcp.json and ~/.config/nadia/mcp.json")
        0
      case Some(path) =>
        Mcp.load(path) match
          case Left(e) =>
            Console.err.println(s"nadia: $e")
            2
          case Right(all) =>
            println(path.toString)
            if all.isEmpty then println("  (no servers configured)")
            all.foldLeft(0) { (rc, spec) =>
              Mcp.stdioRefusal(spec) match
                case Some(why) =>
                  println(s"  ${spec.name}  ✗ $why")
                  2
                case None =>
                  println(s"  ${spec.name}  ${spec.command.getOrElse("")} ${spec.args.mkString(" ")}")
                  if !o.probe then rc
                  else
                    Mcp.connect(spec) match
                      case Left(e) =>
                        println(s"      ✗ $e")
                        2
                      case Right(c) =>
                        c.tools.foreach(t => println(s"      ${t.name}"))
                        c.client.close()
                        rc
            }
