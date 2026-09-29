package nadia.ui

import okay.*
import okay.given
import okay.ui.{Event, Host, Protocol, Ui, Wire}
import okay.ui.Protocol.Msg

/**
 * The journal of a session: the inbound event lines, verbatim, in order — the
 * shape okay-ui's `Sessions` journals too (specs/ui.md, ui-durable), kept here as
 * plain JSON lines because the state holds Maps a `Schema` does not derive yet
 * and because a file the operator can read beats a store they cannot.
 */
trait Journal:
  def append(line: String): Unit
  def lines: Vector[String]

object Journal:
  /** for tests, and for a session nobody wants back */
  def memory(): Journal = new Journal:
    private val buf = scala.collection.mutable.ArrayBuffer[String]()
    def append(line: String): Unit = synchronized { buf += line }
    def lines: Vector[String] = synchronized(buf.toVector)

  /** one JSONL file, appended intent-first — the line is on disk before the
   * state moves, so a crash between the two leaves it in history, not in limbo */
  def file(path: java.nio.file.Path): Journal = new Journal:
    import java.nio.file.{Files, StandardOpenOption as O}
    Option(path.getParent).foreach(Files.createDirectories(_))
    def append(line: String): Unit = synchronized {
      Files.write(path, (line + "\n").getBytes("UTF-8"), O.CREATE, O.APPEND, O.WRITE): Unit
    }
    def lines: Vector[String] =
      if Files.exists(path) then Files.readAllLines(path).toArray(Array.empty[String]).toVector.filter(_.nonEmpty)
      else Vector.empty

/**
 * ONE session, many devices (rozum:docs/specs/okay-workspace-ui.md, "Sessions").
 *
 * The state is one cell; every device — a terminal in-process, a chat, a wire
 * client — folds its events into it and is told when another device moved it,
 * so the phone and the terminal show the same workspace: the draft typed on
 * one is on the other, the room opened on one is open on the other. (Focus is
 * shared too: that is "continue where I left off"; a per-device focus would be
 * a map keyed by device, not a different design — a decision to revisit with
 * real use.)
 *
 * Every event a device folds is journaled first; a restart refolds the journal
 * through the same `Program.update`, so a live run and a recovery reach the same
 * state — the test `S2 — live == recovery` is that equality. The world is not
 * journaled: `refresh` reads the daemons, and a replayed `Pressed(room)` reads
 * them again — the session is what the human did, the world is what is.
 */
final class Shared(feed: WorldFeed, journal: Journal):
  private val update = Program.update(feed)
  private val lock = new Object
  private var watchers = Vector.empty[() => Unit]

  /** the state, recovered: the world first, then the journal refolded — through
   * the REPLAYING feed, whose posts already happened (found by the recovery
   * test: a refold through the live feed sent every journaled message again) */
  @volatile var state: Workspace =
    val replay = Program.update(feed.replaying)
    journal.lines.foldLeft(update(Workspace(), Event.Pressed("refresh"))) { (s, line) =>
      Protocol.parse(line) match
        case Some(Msg.Event(e)) => replay(s, e)
        case _ => s                                  // damage is dropped, deterministically
    }

  /** what one device did: journaled, folded, and announced to the others */
  def apply(e: Event): Workspace =
    val (s2, announce) = lock.synchronized {
      e match
        case Shared.Sync | Event.Closed => (state, Vector.empty)
        case _ =>
          if Shared.journaled(e) then journal.append(Protocol.eventLine(e))
          state = update(state, e)
          (state, watchers)
    }
    announce.foreach(_())
    s2

  private def watch(f: () => Unit): Unit = lock.synchronized { watchers = watchers :+ f }
  private def unwatch(f: () => Unit): Unit = lock.synchronized { watchers = watchers.filterNot(_ eq f) }

  /** a device's door: the other devices' moves, as events */
  def announcements(): Channel[Event] =
    val ch = Channel[Event]()
    watch(() => ch.offer(Shared.Sync): Unit)
    ch

  /** an in-process device: `Ui.run` over this session, with the device's own layout */
  def attach(host: Host, layout: Layout, external: Source[Event] = pure(()))(using Scheduler, CanBlock): Workspace ! Async =
    Ui.runCmd(state)(Program.view(layout))((s, e) =>
      val s2 = apply(e)
      (s2, if s2.quit then Vector(async(Event.Closed)) else Vector.empty))(host, Writer.of(announcements()) merge external)

  /** a wire device: `Wire.serve` over this session. ONE inbound channel carries
   * the client's lines and the other devices' moves (as sync lines); it closes
   * when the client's lines end, so the session ends with its transport — a
   * `merge` would wait for the announcements too, which never end (found live:
   * `wire` on a closed stdin hung forever) */
  def serve(layout: Layout)(lines: Source[String])(using Scheduler, CanBlock): Workspace ! (Writer % String + Async) =
    val inbound = Channel[String]()
    val announce = () => inbound.offer(Protocol.eventLine(Shared.Sync)): Unit
    watch(announce)
    def drain(src: Source[String]): Unit ! Async =
      Writer.uncons[String, Unit, Async](src).flatMap {
        case Left(_) => async { unwatch(announce); inbound.close() }
        case Right((l, more)) => async(inbound.offer(l): Unit).flatMap(_ => drain(more))
      }
    Async.spawn(drain(lines)): Unit
    through[String, String, Async, Unit, Workspace](Writer.of(inbound))(
      !.widen[Workspace, okay.Take % String + Writer % String, Async](
        Wire.serveClosing(state)(Program.view(layout))((s, e) => { val s2 = apply(e); (s2, s2.quit) })))

object Shared:
  /** "another device moved the session" — a key press with no key: always
   * permitted on the wire (it names no capability), never journaled, never an
   * action; `update` folds it as nothing and the loop redraws from the cell */
  val Sync: Event = Event.Key('\u0000')

  /** the human's acts are the session; a refresh is the world's, and stays out */
  def journaled(e: Event): Boolean = e match
    case Event.Pressed("refresh") | Event.Key(_) | Event.Resized(_, _) | Event.Closed => false
    case _ => true
