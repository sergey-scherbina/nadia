package nadia.ui

import okay.codec.Json
import okay.codec.Json.*

/**
 * Where the WORLD comes from, and the doors back into it. The workspace program
 * never talks to a daemon: it asks a feed for the world, hands it a message to
 * post, a command for an agent, a task to start — and the feed is the daemons
 * (`Rozum` + its `AgentSource`s) or a fixture (`InMemory`). The same program
 * either way, which is how the tests run with no network.
 */
trait WorldFeed:
  /** the world as of now; `focus` names the item whose transcript must be fresh */
  def world(focus: Option[String]): World
  /** post into a room; the error is a string a notice can show */
  def post(room: Room, content: String): Either[String, Unit]
  /** a command for an agent — only the ones its `caps` promise are ever sent */
  def act(agent: Agent, cmd: AgentCmd): Either[String, Unit] = Left(s"${agent.display}: not supported")
  /** start an agent on `task` in `project` — a nadia agent, where a source can */
  def spawn(task: String, project: String): Either[String, Unit] = Left("no agent source can start one")
  /** the same world, and doors that already opened: what a journal is refolded
   * through, so recovering a session never speaks, tells or starts twice */
  final def replaying: WorldFeed =
    val outer = this
    new WorldFeed:
      def world(focus: Option[String]): World = outer.world(focus)
      def post(room: Room, content: String): Either[String, Unit] = Right(())
      override def act(agent: Agent, cmd: AgentCmd): Either[String, Unit] = Right(())
      override def spawn(task: String, project: String): Either[String, Unit] = Right(())

/** one kind of agent: where to list them, and how to command them */
trait AgentSource:
  def name: String
  def agents(): Vector[Agent]
  def act(agent: Agent, cmd: AgentCmd): Either[String, Unit] = Left(s"${agent.display}: not supported")
  def spawn(task: String, project: String): Option[Either[String, Unit]] = None

/** an in-memory world: three projects, a few messages, one agent of each source */
final class InMemory extends WorldFeed:
  private val rooms = Vector(
    Room("rozum", "rozum", "2026-09-28", mentions = 1, responding = Vector("nimble-raven")),
    Room("nadia", "nadia", "2026-09-28"),
    Room("okay", "okay", "2026-09-27"))
  @volatile private var agents = Vector(
    Agent("claude-1", "nimble-raven", "rozum", "claude-code", "responding", "room"),
    Agent("nadia-7", "nadia#7", "nadia", "nadia", "running", "nadia", Set(Cap.Tell, Cap.Pause, Cap.Stop),
      detail = "[bash] sbt test", result = ""),
    Agent("codex-2", "sunny-civet", "okay", "codex", "running", "ucc", Set(Cap.Stop), detail = "coder · okay"))
  @volatile private var transcripts: Map[String, Vector[Msg]] = Map(
    rooms(0).key -> Vector(
      Msg(0, "nimble-raven", "joined: worktree .worktrees/feature/rag-rerank", "10:02"),
      Msg(1, "operator", "@nimble-raven how is the rerank going?", "10:05"),
      Msg(2, "nimble-raven", "working: rerank top-20 by RRF, measuring", "10:06")),
    rooms(1).key -> Vector(
      Msg(0, "nadia#7", "working: gate cases 24/24", "09:40")),
    rooms(2).key -> Vector.empty)
  /** what each nadia agent was told, in order — its inbox */
  @volatile var told: Map[String, Vector[String]] = Map.empty
  @volatile private var next = 8

  def world(focus: Option[String]): World = World(rooms, agents, transcripts)
  def post(room: Room, content: String): Either[String, Unit] =
    val t = transcripts.getOrElse(room.key, Vector.empty)
    transcripts = transcripts.updated(room.key, t :+ Msg(t.length, "operator", content, "now"))
    Right(())
  def posted(room: Room): Vector[Msg] = transcripts.getOrElse(room.key, Vector.empty)

  private def set(a: Agent)(f: Agent => Agent): Unit = agents = agents.map(x => if x.id == a.id then f(x) else x)
  override def act(agent: Agent, cmd: AgentCmd): Either[String, Unit] = cmd match
    case AgentCmd.Tell(text) =>
      told = told.updated(agent.id, told.getOrElse(agent.id, Vector.empty) :+ text); Right(())
    case AgentCmd.Pause => set(agent)(_.copy(state = "paused", caps = Set(Cap.Tell, Cap.Resume, Cap.Stop))); Right(())
    case AgentCmd.Resume => set(agent)(_.copy(state = "running", caps = Set(Cap.Tell, Cap.Pause, Cap.Stop))); Right(())
    case AgentCmd.Stop => set(agent)(_.copy(state = "stopping", caps = Set.empty)); Right(())
  override def spawn(task: String, project: String): Either[String, Unit] =
    agents = agents :+ Agent(s"nadia-$next", s"nadia#$next", project, "nadia", "running", "nadia",
      Set(Cap.Tell, Cap.Pause, Cap.Stop), detail = task)
    next += 1
    Right(())

/** the JSON reading every source here needs — total: a missing field is empty */
private object J:
  def str(j: Json, k: String): String = j match
    case JObj(fs) => fs.collectFirst {
      case (`k`, JStr(s)) => s
      case (`k`, JNum(n)) => n.toLong.toString
      case (`k`, JBool(b)) => b.toString
    }.getOrElse("")
    case _ => ""
  def num(j: Json, k: String): Int = j match
    case JObj(fs) => fs.collectFirst { case (`k`, JNum(n)) => n.toInt }.getOrElse(0)
    case _ => 0
  def bool(j: Json, k: String): Boolean = j match
    case JObj(fs) => fs.collectFirst { case (`k`, JBool(b)) => b }.getOrElse(false)
    case _ => false
  def arr(j: Json, k: String): Vector[Json] = j match
    case JObj(fs) => fs.collectFirst { case (`k`, JArr(vs)) => vs }.getOrElse(Vector.empty)
    case _ => Vector.empty
  def asArr(j: Json): Vector[Json] = j match
    case JArr(vs) => vs
    case _ => Vector.empty
  def quote(s: String): String = Json.write(s)

/** a tiny HTTP door: one header set, JSON in and out, every failure a string */
private final class Http(base: String, headers: Seq[(String, String)]):
  import java.net.http.{HttpClient, HttpRequest, HttpResponse}
  import java.net.URI
  private val client = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(3)).build()
  private def req(path: String) =
    val b = HttpRequest.newBuilder(URI.create(base + path)).timeout(java.time.Duration.ofSeconds(10))
    headers.foreach((k, v) => b.header(k, v)); b
  def get(path: String): Either[String, Json] =
    try
      val res = client.send(req(path).GET().build(), HttpResponse.BodyHandlers.ofString())
      if res.statusCode() / 100 == 2 then Right(Json.parse(res.body())) else Left(s"${res.statusCode()} on $path")
    catch case e: Exception => Left(s"$path: ${e.getMessage}")
  def post(path: String, body: String): Either[String, Json] =
    try
      val res = client.send(req(path).header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
      if res.statusCode() / 100 == 2 then Right(Json.parse(res.body()))
      else Left(s"${res.statusCode()} ${res.body().take(100)}")
    catch case e: Exception => Left(s"$path: ${e.getMessage}")

/**
 * The meeting daemon's REST surface (rozum:docs/specs/meetings-rest-read.md;
 * `crates/rozum-meeting/src/meeting/rest_read.rs`): `GET /rooms`, `GET /roster`,
 * `GET /rooms/{n}/presence` (S4 — who is composing), `GET /rooms/{n}/messages/today`,
 * `POST /rooms/{n}/messages` with `{"content"}`. The token is the operator's
 * (`rozum meetings token issue`), sent as Bearer — as the generated terminal client does.
 * Agents from the roster are `room` agents; `sources` add the others.
 */
final class Rozum(base: String, token: String, sources: Vector[AgentSource] = Vector.empty,
                  window: Int = 200) extends WorldFeed:
  private val http = Http(base, Seq("Authorization" -> s"Bearer $token"))
  @volatile private var cache: Map[String, Vector[Msg]] = Map.empty

  private def presence(name: String): Vector[String] =
    http.get(s"/rooms/$name/presence").map(J.arr(_, "responding").map(e =>
      Seq(J.str(e, "handle"), J.str(e, "display")).find(_.nonEmpty).getOrElse("?"))).getOrElse(Vector.empty)

  private def rooms(): Vector[Room] =
    http.get("/rooms").map(J.arr(_, "entries").map { e =>
      val name = J.str(e, "name")
      Room(name, name, J.str(e, "last"), J.num(e, "mentions"), presence(name))
    }).getOrElse(Vector.empty)

  private def roster(rooms: Vector[Room]): Vector[Agent] =
    val typing = rooms.flatMap(_.responding).toSet
    http.get("/roster").map(J.arr(_, "agents").map { a =>
      val id = Seq(J.str(a, "session_id"), J.str(a, "principal_id"), J.str(a, "display")).find(_.nonEmpty).getOrElse("?")
      val display = Some(J.str(a, "display")).filter(_.nonEmpty).getOrElse(id)
      val where = Seq(J.str(a, "cwd")).find(_.nonEmpty).getOrElse("")
      Agent(id, display, J.str(a, "project"), "agent", if typing(display) then "responding" else "live", "room",
        detail = where)
    }).getOrElse(Vector.empty)

  private def transcript(r: Room): Vector[Msg] =
    http.get(s"/rooms/${r.name}/messages/today?from=0&count=$window").map(J.arr(_, "messages").map { m =>
      Msg(J.num(m, "n"), J.str(m, "display_name"), J.str(m, "content"), J.str(m, "time"))
    }).getOrElse(cache.getOrElse(r.key, Vector.empty))

  def world(focus: Option[String]): World =
    val rs = rooms()
    val agents = roster(rs) ++ sources.flatMap(_.agents())
    val w0 = World(rs, agents, cache)
    // an agent's page shows its project room, so that is the transcript to refresh
    val room = focus.flatMap(k => rs.find(_.key == k).orElse(
      agents.find(_.key == k).flatMap(a => rs.find(r => r.name == a.project))))
    cache = cache ++ room.map(r => r.key -> transcript(r))
    w0.copy(transcripts = cache)

  def post(room: Room, content: String): Either[String, Unit] =
    http.post(s"/rooms/${room.name}/messages", s"""{"content":${J.quote(content)}}""").map(_ => ())

  override def act(agent: Agent, cmd: AgentCmd): Either[String, Unit] =
    sources.find(_.name == agent.source).fold(super.act(agent, cmd))(_.act(agent, cmd))

  override def spawn(task: String, project: String): Either[String, Unit] =
    sources.view.flatMap(_.spawn(task, project)).headOption.getOrElse(super.spawn(task, project))

/**
 * `nadia serve` (rozum `crates/nadia/src/serve.rs`): `GET /agents`, `POST /agents
 * {task, workspace}`, `POST /agents/{id}/tell {message}`, `…/pause|resume|stop`, the
 * `x-nadia-token` header. `workspaces` maps a project name to its directory — a new
 * agent works in the project the operator picked, not in the server's default tree.
 */
final class NadiaServe(base: String, token: String, workspaces: String => Option[String] = _ => None)
    extends AgentSource:
  val name = "nadia"
  private val http = Http(base, if token.isEmpty then Nil else Seq("x-nadia-token" -> token))
  private def caps(phase: String): Set[Cap] = phase match
    case "running" => Set(Cap.Tell, Cap.Pause, Cap.Stop)
    case "paused" => Set(Cap.Tell, Cap.Resume, Cap.Stop)
    case _ => Set.empty
  def agents(): Vector[Agent] =
    http.get("/agents").map { j =>
      val list = J.asArr(j) ++ J.arr(j, "agents")
      list.map { a =>
        val id = J.str(a, "id"); val phase = J.str(a, "phase")
        val ws = J.str(a, "workspace")
        val project = ws.split('/').filter(_.nonEmpty).lastOption.getOrElse("")
        val tool = J.str(a, "last_tool"); val detail = J.str(a, "last_tool_detail")
        Agent(s"nadia-$id", s"nadia#$id", project, "nadia", phase, name, caps(phase),
          detail = if tool.isEmpty then J.str(a, "task") else s"[$tool] $detail".trim,
          result = J.str(a, "result"))
      }
    }.getOrElse(Vector.empty)
  private def num(a: Agent) = a.id.stripPrefix("nadia-")
  override def act(agent: Agent, cmd: AgentCmd): Either[String, Unit] = (cmd match
    case AgentCmd.Tell(text) => http.post(s"/agents/${num(agent)}/tell", s"""{"message":${J.quote(text)}}""")
    case AgentCmd.Pause => http.post(s"/agents/${num(agent)}/pause", "{}")
    case AgentCmd.Resume => http.post(s"/agents/${num(agent)}/resume", "{}")
    case AgentCmd.Stop => http.post(s"/agents/${num(agent)}/stop", "{}")).map(_ => ())
  override def spawn(task: String, project: String): Option[Either[String, Unit]] =
    val ws = workspaces(project).fold("")(w => s""","workspace":${J.quote(w)}""")
    Some(http.post("/agents", s"""{"task":${J.quote(task)}$ws}""").map(_ => ()))

/**
 * The control API (`rozum gateway control-serve`, `GET /control/status`): the model
 * participants (`agents`) and coders (`coders`) it launched, each stoppable
 * (`POST /control/agent/stop` / `/control/coder/stop` with `{id}`). It authenticates by
 * the passkey session cookie `rozum_sess`, which the operator hands over as
 * `ROZUM_CONTROL_SESSION`.
 */
final class ControlApi(base: String, session: String) extends AgentSource:
  val name = "ucc"
  private val http = Http(base, Seq("Cookie" -> s"rozum_sess=$session"))
  def agents(): Vector[Agent] =
    http.get("/control/status").map { st =>
      val participants = J.arr(st, "agents").map { a =>
        val alive = J.bool(a, "alive")
        Agent(s"ucc-agent-${J.str(a, "id")}", J.str(a, "handle"), J.str(a, "room"), s"model · ${J.str(a, "model")}",
          if alive then "running" else "stopped", name, if alive then Set(Cap.Stop) else Set.empty,
          detail = J.str(a, "status"))
      }
      val coders = J.arr(st, "coders").map { c =>
        val alive = J.bool(c, "alive")
        val project = J.str(c, "workdir").split('/').filter(_.nonEmpty).lastOption.getOrElse("")
        Agent(s"ucc-coder-${J.str(c, "id")}", s"${J.str(c, "agent")}·${J.str(c, "id").take(6)}", project,
          J.str(c, "agent"), if alive then "running" else "stopped", name, if alive then Set(Cap.Stop) else Set.empty,
          detail = J.str(c, "prompt").take(80))
      }
      participants ++ coders
    }.getOrElse(Vector.empty)
  override def act(agent: Agent, cmd: AgentCmd): Either[String, Unit] = cmd match
    case AgentCmd.Stop =>
      val (path, id) =
        if agent.id.startsWith("ucc-agent-") then ("/control/agent/stop", agent.id.stripPrefix("ucc-agent-"))
        else ("/control/coder/stop", agent.id.stripPrefix("ucc-coder-"))
      http.post(path, s"""{"id":${J.quote(id)}}""").map(_ => ())
    case _ => super.act(agent, cmd)
