package nadia.ui

import okay.codec.Json
import okay.codec.Json.*

/**
 * Where the WORLD comes from. The workspace program never talks to a daemon:
 * it asks a Feed for the world and hands it a message to post, and the Feed is
 * the daemons (`Rozum`) or a fixture (`Fixture`) — the same program either way,
 * which is how the tests run with no network.
 */
trait Feed:
  /** the world as of now; `focus` names the item whose transcript must be fresh */
  def world(focus: Option[String]): World
  /** post into a room; the error is a string a notice can show */
  def post(room: Room, content: String): Either[String, Unit]

/** an in-memory world: three projects, a few messages, posting appends */
final class Fixture extends Feed:
  private val rooms = Vector(
    Room("rozum", "rozum", "2026-09-28", mentions = 1),
    Room("nadia", "nadia", "2026-09-28"),
    Room("okay", "okay", "2026-09-27"))
  private val agents = Vector(
    Agent("claude-1", "nimble-raven", "rozum", "claude-code", "responding"),
    Agent("nadia-7", "nadia#7", "nadia", "nadia", "running"),
    Agent("codex-2", "sunny-civet", "okay", "codex", "idle"))
  @volatile private var transcripts: Map[String, Vector[Msg]] = Map(
    rooms(0).key -> Vector(
      Msg(0, "nimble-raven", "joined: worktree .worktrees/feature/rag-rerank", "10:02"),
      Msg(1, "operator", "@nimble-raven how is the rerank going?", "10:05"),
      Msg(2, "nimble-raven", "working: rerank top-20 by RRF, measuring", "10:06")),
    rooms(1).key -> Vector(
      Msg(0, "nadia#7", "working: gate cases 24/24", "09:40")),
    rooms(2).key -> Vector.empty)

  def world(focus: Option[String]): World = World(rooms, agents, transcripts)
  def post(room: Room, content: String): Either[String, Unit] =
    val t = transcripts.getOrElse(room.key, Vector.empty)
    transcripts = transcripts.updated(room.key, t :+ Msg(t.length, "operator", content, "now"))
    Right(())
  def posted(room: Room): Vector[Msg] = transcripts.getOrElse(room.key, Vector.empty)

/**
 * The meeting daemon's REST surface (rozum:docs/specs/meetings-rest-read.md;
 * `crates/rozum-meeting/src/meeting/rest_read.rs`): `GET /rooms`, `GET /roster`,
 * `GET /rooms/{n}/messages/today`, `POST /rooms/{n}/messages` with `{"content"}`.
 * The token is the operator's (`rozum meetings token issue`), sent as Bearer —
 * what the generated terminal client sends too.
 */
final class Rozum(base: String, token: String, window: Int = 200) extends Feed:
  import java.net.http.{HttpClient, HttpRequest, HttpResponse}
  import java.net.URI
  private val http = HttpClient.newHttpClient()
  @volatile private var cache: Map[String, Vector[Msg]] = Map.empty

  private def get(path: String): Either[String, Json] =
    try
      val req = HttpRequest.newBuilder(URI.create(base + path))
        .header("Authorization", s"Bearer $token").GET().build()
      val res = http.send(req, HttpResponse.BodyHandlers.ofString())
      if res.statusCode() / 100 == 2 then Right(Json.parse(res.body()))
      else Left(s"${res.statusCode()} on $path")
    catch case e: Exception => Left(s"$path: ${e.getMessage}")

  private def str(j: Json, k: String): String = j match
    case JObj(fs) => fs.collectFirst { case (`k`, JStr(s)) => s; case (`k`, JNum(n)) => n.toLong.toString }.getOrElse("")
    case _ => ""
  private def num(j: Json, k: String): Int = j match
    case JObj(fs) => fs.collectFirst { case (`k`, JNum(n)) => n.toInt }.getOrElse(0)
    case _ => 0
  private def arr(j: Json, k: String): Vector[Json] = j match
    case JObj(fs) => fs.collectFirst { case (`k`, JArr(vs)) => vs }.getOrElse(Vector.empty)
    case _ => Vector.empty

  private def rooms(): Vector[Room] =
    get("/rooms").map(arr(_, "entries").map { e =>
      val name = str(e, "name")
      Room(name, name, str(e, "last"), num(e, "mentions"))
    }).getOrElse(Vector.empty)

  private def agents(): Vector[Agent] =
    get("/roster").map(arr(_, "agents").map { a =>
      val id = Seq(str(a, "session_id"), str(a, "principal_id"), str(a, "display")).find(_.nonEmpty).getOrElse("?")
      val project = str(a, "project")
      Agent(id, str(a, "display").pipeIfEmpty(id), project, "agent", "live")
    }).getOrElse(Vector.empty)

  private def transcript(r: Room): Vector[Msg] =
    get(s"/rooms/${r.name}/messages/today?from=0&count=$window").map(arr(_, "messages").map { m =>
      Msg(num(m, "n"), str(m, "display_name"), str(m, "content"), str(m, "time"))
    }).getOrElse(cache.getOrElse(r.key, Vector.empty))

  extension (s: String) private def pipeIfEmpty(alt: String): String = if s.isEmpty then alt else s

  def world(focus: Option[String]): World =
    val rs = rooms()
    val fresh = focus.flatMap(k => rs.find(_.key == k)).map(r => r.key -> transcript(r))
    cache = cache ++ fresh
    World(rs, agents(), cache)

  def post(room: Room, content: String): Either[String, Unit] =
    try
      val body = Json.write(content)
      val req = HttpRequest.newBuilder(URI.create(s"$base/rooms/${room.name}/messages"))
        .header("Authorization", s"Bearer $token").header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(s"""{"content":$body}""")).build()
      val res = http.send(req, HttpResponse.BodyHandlers.ofString())
      if res.statusCode() / 100 == 2 then Right(()) else Left(s"post: ${res.statusCode()} ${res.body().take(80)}")
    catch case e: Exception => Left(s"post: ${e.getMessage}")
