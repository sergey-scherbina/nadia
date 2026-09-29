package nadia.ui

/**
 * The model of "what" (rozum:docs/specs/okay-workspace-ui.md). Two halves, kept
 * apart on purpose:
 *
 *  - the WORLD is what the daemons say — rooms, agents, transcripts. It is
 *    refreshed, never edited here, and it is not the session's to journal;
 *  - the SESSION is what the human did — which item has the focus, and one
 *    `Context` per item: the draft being typed, where the reader is in the
 *    transcript, what was seen. This is what switching preserves, and it lives
 *    in the workspace, never in a client, so the phone and the terminal agree.
 *
 * Keys are addresses: a room's key is `room.<project>.<name>`, an agent's is
 * `agent.<id>`; every button that names an item carries the item's key, so an
 * `Event.Pressed(key)` IS the item, with no lookup table beside the tree.
 */
final case class Room(project: String, name: String, last: String = "", mentions: Int = 0,
                      /** who is composing a reply right now (the daemon's `responding`) */
                      responding: Vector[String] = Vector.empty):
  def key: String = s"room.$project.$name"
  def title: String = if project == name then name else s"$project/$name"

final case class Msg(n: Int, who: String, text: String, time: String = "")

/**
 * One agent, whichever source knows it (rozum:docs/specs/okay-workspace-ui.md, S4):
 *
 *  - `room`   — an agent that said `rozum meetings hello` (Claude Code, codex…): it lives in
 *               a meeting room, and the only way to reach it is to speak there, addressed;
 *  - `ucc`    — a model participant or a coder the control API launched: it can be stopped;
 *  - `nadia`  — a nadia agent under `nadia serve`: it has its own inbox (`tell`) and can be
 *               paused, resumed and stopped, and it reports its last tool and its result.
 *
 * What an agent CAN do is data (`caps`), so the view shows exactly the buttons that act —
 * and the tree being the capability list, a press on one it did not show is refused.
 */
enum Cap:
  case Tell, Pause, Resume, Stop

final case class Agent(id: String, display: String, project: String, kind: String, state: String,
                       source: String = "room", caps: Set[Cap] = Set.empty,
                       /** what it is doing, in one line — `[bash] cargo check`, a task */
                       detail: String = "",
                       /** its answer, once it has one */
                       result: String = ""):
  def key: String = s"agent.$id"

/** what the workspace can ask of an agent */
enum AgentCmd:
  case Tell(text: String)
  case Pause, Resume, Stop

final case class World(rooms: Vector[Room] = Vector.empty,
                       agents: Vector[Agent] = Vector.empty,
                       transcripts: Map[String, Vector[Msg]] = Map.empty):
  def room(key: String): Option[Room] = rooms.find(_.key == key)
  def agent(key: String): Option[Agent] = agents.find(_.key == key)
  def transcript(key: String): Vector[Msg] = transcripts.getOrElse(key, Vector.empty)

/** what switching keeps, per item */
final case class Context(draft: String = "",
                         /** the `n` at the TOP of the window the reader is looking at;
                          * -1 follows the tail (the newest messages) */
                         anchor: Int = -1,
                         /** the newest `n` the reader had in front of them */
                         seen: Int = -1,
                         /** whom the composer addresses — an agent's display name, or none */
                         addressee: String = "")

final case class Workspace(world: World = World(),
                           focus: Option[String] = None,
                           contexts: Map[String, Context] = Map.empty,
                           notice: String = "",
                           quit: Boolean = false,
                           /** the "new agent" form: its task, and which project it works in */
                           spawnTask: String = "",
                           spawnProject: Int = 0):
  def context(key: String): Context = contexts.getOrElse(key, Context())
  def focused: Context = focus.map(context).getOrElse(Context())
  def withContext(key: String)(f: Context => Context): Workspace =
    copy(contexts = contexts.updated(key, f(context(key))))
  /** the room a focused item speaks in: a room itself, or an agent's project room */
  def roomOf(key: String): Option[Room] =
    world.room(key).orElse(world.agent(key).flatMap(a => world.rooms.find(r => r.project == a.project && r.name == a.project)))
  def unread(r: Room): Int =
    val seen = context(r.key).seen
    if seen < 0 then 0 else world.transcript(r.key).count(_.n > seen)
