package nadia

import agent.Budget
import nadia.rozum.{Gateway, Nadia, Sandbox}

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
      gateway: String = Gateway.urlFromEnv(),
      model: String = Gateway.modelFromEnv(),
      maxSteps: Int = 24,
      allowNet: Boolean = false,
      confine: Boolean = true,
      json: Boolean = false
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
        case ("-h" | "--help") :: _ => Left(usage)
        case "--allow-net" :: t     => loop(t, mode, task, o.copy(allowNet = true))
        case "--no-confine" :: t    => loop(t, mode, task, o.copy(confine = false))
        case "--json" :: t          => loop(t, mode, task, o.copy(json = true))
        case "--workspace" :: v :: t => loop(t, mode, task, o.copy(workspace = v))
        case "--gateway" :: v :: t   => loop(t, mode, task, o.copy(gateway = v))
        case "--model" :: v :: t     => loop(t, mode, task, o.copy(model = v))
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
            val sb = base.copy(allowNet = o.allowNet, confine = o.confine && Sandbox.seatbeltAvailable)
            val client = Gateway.client(o.gateway, o.model)
            val budget = Budget(maxSteps = o.maxSteps)
            mode match
              case "run" if task.isEmpty =>
                Console.err.println(s"nadia run needs a task\n\n$usage")
                2
              case "run"  => Nadia.batch(client, sb, budget, task, o.json)
              case "chat" => Nadia.chat(client, sb, budget, o.model, o.allowNet)
              case other =>
                Console.err.println(s"unknown mode `$other`\n\n$usage")
                2
