package nadia.ui

import okay.ui.{Event, Ui, Style, Tone, Role, InputKind, Dir}
import okay.ui.Ui.*

/**
 * The "what": one `view` and one `update`, for every host. There is no host
 * code here and no per-platform application — a chat, a terminal and a browser
 * draw THIS, and what differs is the vocabulary each claims (okay lowers what a
 * host does not draw) and one `Layout` value chosen where the host is wired.
 *
 * `Layout` is the operator's "low-level details sometimes decide everything":
 * a chat shows one thing at a time and a handful of messages, a terminal a
 * sidebar and a page, a browser everything at once. It is a value at the edge,
 * not a branch in the logic — `update` never sees it, and the tests prove the
 * same events reach the same state under every layout and every host.
 */
enum Layout:
  /** sidebar · item · agents, `window` messages of transcript */
  case Wide(window: Int = 20)
  /** one screen at a time — the room list, or one item — `window` messages */
  case Narrow(window: Int = 6)
  def window: Int = this match
    case Wide(w) => w
    case Narrow(w) => w

object Workspace:

  private val bold = Style(bold = true)
  private val muted = Style(tone = Tone.Muted)
  private val emphasis = Style(tone = Tone.Emphasis)

  // ---- view -----------------------------------------------------------------

  def view(layout: Layout)(s: Workspace): Ui = layout match
    case Layout.Wide(_) =>
      Column(Vector(
        Row(Vector(Text("nadia workspace", bold), Text(if s.notice.isEmpty then "" else s"  ${s.notice}", muted))),
        Box(Vector(sidebar(s), page(s, layout.window), agents(s)), Dir.Horizontal, weights = Vector(1, 3, 1), gap = 1)))
    case Layout.Narrow(_) => s.focus match
      case None => Column(Vector(
        Text("nadia workspace", bold),
        Text(s.notice, muted),
        rooms(s),
        Text("Agents", emphasis),
        Items(s.world.agents.map(a => Button(s"${a.display} · ${a.state}", a.key)), "agents"),
        Button("↻", "refresh")))
      case Some(_) => Column(Vector(page(s, layout.window), Button("← rooms", "rooms")))

  private def sidebar(s: Workspace): Ui =
    Column(Vector(rooms(s), Text(""), Button("↻ refresh", "refresh"), Button("quit", "quit")))

  private def rooms(s: Workspace): Ui =
    Column(Vector(
      Text("Rooms", emphasis),
      Items(s.world.rooms.map { r =>
        val here = s.focus.contains(r.key)
        val unread = s.unread(r)
        val badge =
          if unread > 0 then s" +$unread"
          else if s.context(r.key).seen < 0 && r.mentions > 0 then s" @${r.mentions}"
          else ""
        Button(s"${if here then "▶ " else ""}${r.title}$badge", r.key, if here then Role.Active else Role.Plain)
      }, "rooms")))

  private def agents(s: Workspace): Ui =
    Column(Vector(
      Text("Agents", emphasis),
      Table(Vector("who", "state", "project"),
        s.world.agents.map(a => Vector(
          Button(a.display, a.key, if s.focus.contains(a.key) then Role.Active else Role.Plain),
          Text(a.state, muted), Text(a.project))),
        "agents")))

  /** the focused item — a room's transcript, or an agent's card — and the composer under it */
  private def page(s: Workspace, window: Int): Ui = s.focus match
    case None => Column(Vector(Text("pick a room or an agent", muted)))
    case Some(key) =>
      val ctx = s.context(key)
      val room = s.roomOf(key)
      val head = s.world.agent(key) match
        case Some(a) => Column(Vector(
          Text(a.display, bold),
          Text(s"${a.kind} · ${a.state} · ${a.project}", muted),
          Text(room.fold("no room")(r => s"speaks in ${r.title}"), muted)))
        case None => Text(room.fold(key)(_.title), bold)
      val all = room.map(r => s.world.transcript(r.key)).getOrElse(Vector.empty)
      val shown = slice(all, ctx.anchor, window)
      val lines = if shown.isEmpty then Vector(Text("— nothing yet —", muted))
                  else shown.map(m => Text(s"${m.time} ${m.who}: ${m.text}".trim))
      val older = all.nonEmpty && shown.headOption.exists(_.n > all.head.n)
      val newer = ctx.anchor >= 0
      Column(Vector(
        head,
        Scroll(Column(lines), "transcript"),
        Row(Vector(
          Button(if older then "↑ older" else "·", "older"),
          Button(if newer then "↓ newer" else "·", "newer"))),
        Form(Vector(Input(ctx.draft, "compose",
          if ctx.addressee.isEmpty then "Message" else s"To ${ctx.addressee}",
          InputKind.Multiline, live = true)), "Send", "send")))

  /** `window` messages from `anchor` (the n at the top), or the tail when anchor < 0 */
  def slice(all: Vector[Msg], anchor: Int, window: Int): Vector[Msg] =
    if anchor < 0 then all.takeRight(window)
    else all.dropWhile(_.n < anchor).take(window)

  // ---- update ---------------------------------------------------------------

  /**
   * One update for every host and every layout. `feed` is the world's door:
   * `refresh` pulls it, `send` pushes through it — the two places the pure
   * fold meets the daemons, both named, both replaceable by a fixture.
   */
  def update(feed: Feed)(s: Workspace, e: Event): Workspace = e match
    case Event.Pressed("refresh") => refreshed(feed, s)
    case Event.Pressed("rooms") => s.copy(focus = None)
    case Event.Pressed("quit") => s.copy(quit = true)
    case Event.Pressed(k) if s.world.room(k).isDefined || s.world.agent(k).isDefined => focusOn(feed, s, k)
    case Event.Edited("compose", v) =>
      s.focus.fold(s)(k => s.withContext(k)(_.copy(draft = v)))
    case Event.Pressed("older") => s.focus.fold(s) { k =>
      s.roomOf(k).fold(s) { r =>
        val all = s.world.transcript(r.key); val ctx = s.context(k)
        val shownTop = slice(all, ctx.anchor, window(s)).headOption.map(_.n).getOrElse(0)
        val top = all.map(_.n).filter(_ < shownTop).takeRight(window(s)).headOption
        top.fold(s)(n => s.withContext(k)(_.copy(anchor = n)))
      } }
    case Event.Pressed("newer") => s.focus.fold(s) { k =>
      s.roomOf(k).fold(s) { r =>
        val all = s.world.transcript(r.key); val ctx = s.context(k)
        if ctx.anchor < 0 then s
        else
          val next = all.map(_.n).filter(_ > slice(all, ctx.anchor, window(s)).lastOption.map(_.n).getOrElse(ctx.anchor)).headOption
          val tail = all.takeRight(window(s)).headOption.map(_.n).getOrElse(-1)
          s.withContext(k)(_.copy(anchor = next.filter(_ < tail).getOrElse(-1)))
      } }
    case Event.Pressed("send") => sent(feed, s)
    case Event.Submitted("send", edits) => sent(feed, edits.foldLeft(s)(update(feed)))
    case _ => s

  /** the window `older`/`newer` step by — the largest layout's, so a chat and a
   * terminal page the same way and their anchors mean the same thing */
  private def window(s: Workspace): Int = 20

  private def refreshed(feed: Feed, s: Workspace): Workspace =
    val w = feed.world(s.focus)
    // the reader is looking at the tail of the focused room: what arrives while
    // they look is seen, not unread
    val s2 = s.copy(world = w)
    s2.focus.flatMap(s2.roomOf).filter(r => s2.focused.anchor < 0).fold(s2) { r =>
      val last = w.transcript(r.key).lastOption.map(_.n).getOrElse(-1)
      s2.withContext(s2.focus.get)(c => c.copy(seen = math.max(c.seen, last)))
    }

  private def focusOn(feed: Feed, s: Workspace, k: String): Workspace =
    val s1 = s.copy(focus = Some(k), notice = "")
    val s2 = s1.copy(world = feed.world(Some(k)))
    val addressee = s2.world.agent(k).map(_.display).getOrElse("")
    s2.roomOf(k).fold(s2) { r =>
      val last = s2.world.transcript(r.key).lastOption.map(_.n).getOrElse(-1)
      s2.withContext(k)(c => c.copy(seen = if c.anchor < 0 then math.max(c.seen, last) else c.seen, addressee = addressee))
    }

  private def sent(feed: Feed, s: Workspace): Workspace = s.focus.fold(s) { k =>
    val ctx = s.context(k)
    val text = ctx.draft.trim
    if text.isEmpty then s.copy(notice = "nothing to send")
    else s.roomOf(k) match
      case None => s.copy(notice = "no room to speak in")
      case Some(r) =>
        val line = if ctx.addressee.isEmpty then text else s"@${ctx.addressee} $text"
        feed.post(r, line) match
          case Left(err) => s.copy(notice = err)
          case Right(()) =>
            val s2 = s.withContext(k)(_.copy(draft = "", anchor = -1)).copy(notice = s"sent to ${r.title}")
            refreshed(feed, s2)
  }
