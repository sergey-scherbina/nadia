package nadia.rozum

import agent.{McpClient, Tool}
import java.nio.file.{Files, Path, Paths}

/** MCP servers as extra tools — the client direction of `SPEC.md` §2.1.
  *
  * The protocol is `agent.McpClient` (generic, no nadia in it). What lives here is what an
  * application owns: where the config is, which servers are connected, what the tools are
  * called, and what happens when one is broken.
  *
  * The bar for a seventh built-in tool is untouched. This is the other answer to "I need one
  * more": tools nadia does not define and is not responsible for, connected for a run — and
  * connected only when asked, because every tool costs schema tokens in every request of
  * every step, and for a 4B model each one dilutes selection.
  */
object Mcp:

  /** One `mcpServers` entry. `url`/`kind` exist only to explain a refusal. */
  final case class ServerSpec(
      name: String,
      command: Option[String],
      args: List[String],
      env: Map[String, String],
      url: Option[String],
      kind: Option[String]
  )

  /** A connected server and the tools it contributed, already renamed for the model. */
  final case class Connected(name: String, client: McpClient, tools: List[Tool])

  /** The name an MCP tool carries into the model's tool list — the ecosystem's convention.
    * The prefix makes a collision with the six impossible and keeps two servers apart. */
  def prefixed(server: String, tool: String): String = s"mcp__${server}__$tool"

  def isMcpTool(name: String): Boolean = name.startsWith("mcp__")

  /** Where the config is: an explicit path, the workspace's own `.mcp.json`, then the
    * user's. First one that exists, so a project overrides the user's default. */
  def configPath(explicit: Option[String], workspace: Path): Option[Path] =
    explicit.map(Paths.get(_)).orElse {
      val local = workspace.resolve(".mcp.json")
      if Files.isRegularFile(local) then Some(local)
      else
        val user = Paths.get(System.getProperty("user.home"), ".config", "nadia", "mcp.json")
        if Files.isRegularFile(user) then Some(user) else None
    }

  /** Read + parse. A path the caller ASKED for that is missing or malformed is an error,
    * never an empty config: `--mcp-config typo.json` must not read as "no servers". */
  def load(path: Path): Either[String, List[ServerSpec]] =
    try
      val text = Files.readString(path)
      val root = ujson.read(text)
      val servers = root.obj.get("mcpServers").flatMap(_.objOpt).getOrElse(ujson.Obj().obj)
      Right(servers.toList.sortBy(_._1).map { case (name, e) =>
        ServerSpec(
          name,
          e.obj.get("command").flatMap(_.strOpt).filter(_.trim.nonEmpty),
          e.obj.get("args").flatMap(_.arrOpt).map(_.toList.flatMap(_.strOpt)).getOrElse(Nil),
          e.obj.get("env").flatMap(_.objOpt).map(_.toList.flatMap { case (k, v) => v.strOpt.map(k -> _) }.toMap).getOrElse(Map.empty),
          e.obj.get("url").flatMap(_.strOpt),
          e.obj.get("type").flatMap(_.strOpt)
        )
      })
    catch case e: Throwable => Left(s"MCP config $path: ${e.getMessage}")

  /** Why this entry cannot be a stdio server, or `None`. Refusing BY NAME is the point: an
    * operator who configured an HTTP server and saw no error would conclude it was
    * connected, and would then be debugging a model that "ignores its tools". */
  def stdioRefusal(spec: ServerSpec): Option[String] =
    if spec.command.nonEmpty then None
    else
      val what = spec.url
        .map(u => s"a url ($u)")
        .orElse(spec.kind.map(t => s"type `$t`"))
        .getOrElse("no `command`")
      Some(
        s"MCP server `${spec.name}` has $what: nadia speaks the stdio transport only, so this " +
          "server cannot be connected. Give it a `command` that starts the server on stdio, or " +
          "drop it from --mcp."
      )

  /** Which servers to connect. A name that is not in the config is an error naming what IS
    * there — a typo must not read as "that server contributed no tools". */
  def select(
      all: List[ServerSpec],
      wanted: List[String],
      everyOne: Boolean
  ): Either[String, List[ServerSpec]] =
    if everyOne then Right(all)
    else
      wanted.foldLeft[Either[String, List[ServerSpec]]](Right(Nil)) { (acc, name) =>
        acc.flatMap { got =>
          all.find(_.name == name).map(s => got :+ s).toRight {
            val known = all.map(_.name)
            if known.isEmpty then s"no MCP server `$name` — the config has none configured"
            else s"no MCP server `$name` — the config has: ${known.mkString(" ")}"
          }
        }
      }

  /** Connect one server and wrap its tools. The handler holds the client and the server's
    * OWN name for the call, while the model sees the prefixed one. A dead server answers
    * with an error the model can act on rather than killing the run (`SPEC.md` §2.1). */
  def connect(spec: ServerSpec): Either[String, Connected] =
    stdioRefusal(spec).toLeft(()).flatMap { _ =>
      McpClient
        .spawn(spec.command.get, spec.args, spec.env)
        .left
        .map(e => s"MCP server `${spec.name}`: $e")
        .flatMap { client =>
          client.listTools().left.map(e => s"MCP server `${spec.name}`: $e").map { descriptors =>
            val tools = descriptors.map { d =>
              Tool(
                prefixed(spec.name, d.name),
                d.description,
                d.schema,
                args =>
                  if !client.alive then
                    Left(s"MCP server `${spec.name}` is gone — its tools are unavailable for the rest of this run. Use the built-in tools instead.")
                  else client.callTool(d.name, args)
              )
            }
            Connected(spec.name, client, tools)
          }
        }
    }

  /** The line printed once per connected server: what it added, and that it is NOT jailed.
    * Silence here leaves an operator with a mental model ("nadia is confined") that is
    * quietly false — a server is a separate process with its own access to the machine. */
  def connectedLine(c: Connected): String =
    s"mcp `${c.name}`: ${c.tools.length} tool(s) — ${c.tools.map(_.name).mkString(" ")}" +
      " · runs OUTSIDE the workspace jail, gated like bash"
