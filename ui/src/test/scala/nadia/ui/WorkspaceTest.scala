package nadia.ui

import okay.*
import okay.given
import okay.ui.{Event, Frame, Host, Telegram, Ui, Wire}
import Telegram.{Act, Key, Message, Update}

/**
 * The gates of rozum:docs/specs/okay-workspace-ui.md, S0 and S2, offline:
 * one program, the same state under every host; switching keeps the context.
 */
class WorkspaceTest extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(20, "s")

  def fresh: (InMemory, Workspace) =
    val f = InMemory()
    (f, Program.update(f)(Workspace(), Event.Pressed("refresh")))

  val rozum = "room.rozum.rozum"; val nadia = "room.nadia.nadia"

  /** every node satisfying p, in document order */
  def find(ui: Ui)(p: PartialFunction[Ui, Boolean]): Vector[Ui] =
    val here = if p.isDefinedAt(ui) && p(ui) then Vector(ui) else Vector.empty
    val kids: Vector[Ui] = ui match
      case Ui.Row(c, _) => c
      case Ui.Column(c, _) => c
      case Ui.Box(c, _, _, _, _, _) => c
      case Ui.Scroll(c, _) => Vector(c)
      case Ui.Form(f, _, _) => f
      case Ui.Items(i, _) => i
      case Ui.Table(_, rows, _, _) => rows.flatten
      case Ui.Tabs(_, _, pages, _) => pages
      case Ui.Modal(_, b, _) => Vector(b)
      case Ui.Disclosure(_, _, b, _) => Vector(b)
      case _ => Vector.empty
    here ++ kids.flatMap(find(_)(p))
  def draft(ui: Ui): String =
    find(ui) { case Ui.Input(_, "compose", _, _, _) => true }.collectFirst { case Ui.Input(v, _, _, _, _) => v }.getOrElse("")
  def text(ui: Ui): String = Frame.render(ui).mkString("\n")

  test("S0 — the scripted host and the terminal renderer agree on every frame; the fixture shows") {
    val (f, init) = fresh
    val frames = scala.collection.mutable.Buffer[Ui]()
    val feed = Channel[Event]()
    val host = new Host:
      def render(ui: Ui): Unit ! Async = async { frames += ui; () }
      def events: Source[Event] = Writer.of(feed)
    val fiber = Async.spawn(Ui.run(init)(Program.view(Layout.Wide()))(Program.update(f))(host))
    Seq(Event.Pressed(rozum), Event.Closed).foreach(feed.offer)
    val end = fiber.join()
    assertEquals(end.focus, Some(rozum))
    val drawn = frames.toList.map(text)
    assert(drawn.head.contains("rozum") && drawn.head.contains("nadia") && drawn.head.contains("okay"), drawn.head)
    assert(drawn.last.contains("how is the rerank going?"), drawn.last)
    // a mention badge before the room is visited, none after
    assert(drawn.head.contains("rozum @1"), drawn.head)
    assert(!drawn.last.contains("@1"), drawn.last)
  }

  test("S2 — switching keeps the draft and the reading position, per item, on the same update") {
    val (f, init) = fresh
    val step = Program.update(f)
    val s1 = Seq(Event.Pressed(rozum), Event.Edited("compose", "on it, measuring"),
                 Event.Pressed(nadia), Event.Edited("compose", "how many cases?"),
                 Event.Pressed(rozum)).foldLeft(init)(step)
    assertEquals(s1.focus, Some(rozum))
    assertEquals(draft(Program.view(Layout.Wide())(s1)), "on it, measuring")
    assertEquals(draft(Program.view(Layout.Narrow())(s1)), "on it, measuring")
    val s2 = step(s1, Event.Pressed(nadia))
    assertEquals(draft(Program.view(Layout.Wide())(s2)), "how many cases?")
    // sending clears only that room's draft, posts to the fixture, and leaves the other room's draft alone
    val s3 = step(s2, Event.Pressed("send"))
    assertEquals(f.posted(Room("nadia", "nadia")).last.text, "how many cases?")
    assertEquals(s3.context(nadia).draft, "")
    assertEquals(s3.context(rozum).draft, "on it, measuring")
    assert(s3.notice.startsWith("sent"), s3.notice)
  }

  test("S2 — a message posted while the reader looks is seen; one posted while away is unread") {
    val (f, init) = fresh
    val step = Program.update(f)
    val away = step(step(init, Event.Pressed(rozum)), Event.Pressed(nadia))
    f.post(Room("rozum", "rozum"), "done: rerank landed")
    val back = step(away, Event.Pressed("refresh"))
    assertEquals(back.unread(Room("rozum", "rozum")), 1)
    assert(text(Program.view(Layout.Wide())(back)).contains("rozum +1"))
    val there = step(back, Event.Pressed(rozum))
    assertEquals(there.unread(Room("rozum", "rozum")), 0)
  }

  test("S2 — the addressee: focusing an agent speaks in its project room, prefixed") {
    val (f, init) = fresh
    val step = Program.update(f)
    val s = Seq(Event.Pressed("agent.claude-1"), Event.Edited("compose", "status?"), Event.Pressed("send")).foldLeft(init)(step)
    assertEquals(f.posted(Room("rozum", "rozum")).last.text, "@nimble-raven status?")
    assertEquals(s.context("agent.claude-1").addressee, "nimble-raven")
  }

  test("THE SEAM — the same walk in a chat behind Wire.serve reaches the scripted host's state") {
    // (a) the scripted host, the wide layout
    val (fa, ia) = fresh
    final class TestHost extends Host:
      val feed = Channel[Event]()
      def render(ui: Ui): Unit ! Async = async(())
      def events: Source[Event] = Writer.of(feed)
    val th = TestHost()
    val local = Async.spawn(Ui.run(ia)(Program.view(Layout.Wide()))(Program.update(fa))(th))
    Seq(Event.Pressed(rozum), Event.Edited("compose", "hello from the terminal"), Event.Pressed("send"),
        Event.Pressed(nadia), Event.Closed).foreach(th.feed.offer)
    val expected = local.join()

    // (b) the chat, the narrow layout, behind the wire; a second fixture so the posts are its own
    val (fb, ib) = fresh
    val up = Channel[String](); val down = Channel[String]()
    var served: Option[Workspace] = None
    val server = Async.spawn {
      def drain(p: Workspace ! Writer % String + Async): Unit ! Async =
        Writer.uncons[String, Workspace, Async](p).flatMap {
          case Left(s) => async { served = Some(s); down.close() }
          case Right((l, rest)) => down.send(l).map(_ => ()).flatMap(_ => drain(rest))
        }
      drain(through[String, String, Async, Unit, Workspace](Writer.of(up))(
        !.widen[Workspace, okay.Take % String + Writer % String, Async](
          Wire.serveClosing(ib)(Program.view(Layout.Narrow()))((s, e) =>
            val s2 = Program.update(fb)(s, e); (s2, e == Event.Pressed(nadia))))))
    }
    // the chat: every frame the host draws; a step waits for the frames a
    // press causes and takes the LAST — one event may arrive as several
    // patches, each redrawn, and a press belongs to the newest frame
    val frames = new java.util.concurrent.atomic.AtomicInteger(0)
    @volatile var latest: Message = Message("", Vector.empty)
    val (host, hear) = Telegram.host {
      case Act.Send(m) => async { latest = m; frames.incrementAndGet(); Some(1L) }
      case Act.Edit(_, m) => async { latest = m; frames.incrementAndGet(); None }
      case _ => async(None)
    }
    Async.spawn(Wire.client(host)(Writer.of(down), l => up.send(l).map(_ => ()))): Unit
    def settled(after: Int): Message =
      val deadline = System.currentTimeMillis + 5000
      while frames.get <= after && System.currentTimeMillis < deadline do Thread.sleep(20)
      assert(frames.get > after, "no frame arrived from the chat")
      var n = frames.get
      var quiet = false
      while !quiet do
        Thread.sleep(150)
        if frames.get == n then quiet = true else n = frames.get
      latest
    def data(m: Message, label: String): String =
      m.keyboard.flatten.collectFirst { case Key.Press(l, d) if l == label || l.startsWith(label) => d }
        .getOrElse(fail(s"no button «$label» in ${m.keyboard.flatten.map { case Key.Press(l, _) => l; case Key.Open(l, _) => l }}"))
    var m = settled(0)
    def press(label: String, redraws: Boolean = true): Unit =
      val before = frames.get
      hear(Update.Pressed(data(m, label), "cb")).runWith
      if redraws then m = settled(before)
    def say(text: String): Unit =
      val before = frames.get
      hear(Update.Said(text)).runWith
      m = settled(before)
    press("rozum")
    press("✎ Message", redraws = false)      // the chat ASKS; no frame until the answer
    say("hello from the terminal")            // folded by the client, redrawn; live, so it crosses too
    press("Send")                             // ONE Submitted crosses the wire
    press("← rooms")
    press("nadia", redraws = false)           // the server closes on this one
    server.join()

    val same = (w: Workspace) => (w.focus, w.contexts, w.world.transcripts)
    assertEquals(served.map(same), Some(same(expected)))
    assertEquals(fb.posted(Room("rozum", "rozum")).last.text, "hello from the terminal")
    assertEquals(expected.focus, Some(nadia))
  }

/** the second half of S2: one session, many devices, and a restart that loses nothing */
class SharedTest extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(20, "s")
  val rozum = "room.rozum.rozum"; val nadia = "room.nadia.nadia"

  final class Device extends Host:
    val feed = Channel[Event]()
    val frames = new java.util.concurrent.LinkedBlockingQueue[Ui]()
    def render(ui: Ui): Unit ! Async = async { frames.put(ui); () }
    def events: Source[Event] = Writer.of(feed)
    /** the frames drawn so far, waiting until one satisfies p (5 s) */
    def until(p: Ui => Boolean): Ui =
      val deadline = System.currentTimeMillis + 5000
      var last: Ui = null
      while System.currentTimeMillis < deadline do
        val f = frames.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS)
        if f != null then { last = f; if p(f) then return f }
      fail(s"no such frame; last: ${Option(last).map(Frame.render(_).mkString("|"))}")
  def draft(ui: Ui): String =
    def go(u: Ui): Option[String] = u match
      case Ui.Input(v, "compose", _, _, _) => Some(v)
      case Ui.Row(c, _) => c.view.flatMap(go).headOption
      case Ui.Column(c, _) => c.view.flatMap(go).headOption
      case Ui.Box(c, _, _, _, _, _) => c.view.flatMap(go).headOption
      case Ui.Scroll(c, _) => go(c)
      case Ui.Form(f, _, _) => f.view.flatMap(go).headOption
      case Ui.Items(i, _) => i.view.flatMap(go).headOption
      case _ => None
    go(ui).getOrElse("")

  test("S2 — two devices, one session: the draft typed on the terminal is on the phone, and back") {
    val shared = Shared(InMemory(), Journal.memory())
    val a = Device(); val b = Device()
    val la = Async.spawn(shared.attach(a, Layout.Wide()))
    val lb = Async.spawn(shared.attach(b, Layout.Narrow()))
    a.until(_ => true); b.until(_ => true)
    a.feed.offer(Event.Pressed(rozum)); a.feed.offer(Event.Edited("compose", "typed on A"))
    // the phone, having done nothing, shows the room the terminal opened and its draft
    val onB = b.until(draft(_) == "typed on A")
    assert(Frame.render(onB).mkString("\n").contains("how is the rerank going?"))
    // the phone switches rooms; the terminal follows, and the draft is per room
    b.feed.offer(Event.Pressed(nadia))
    val onA = a.until(f => Frame.render(f).mkString("\n").contains("▶ nadia"))
    assertEquals(draft(onA), "")
    b.feed.offer(Event.Pressed(rozum))
    assertEquals(draft(a.until(draft(_) == "typed on A")), "typed on A")
    a.feed.offer(Event.Closed); b.feed.offer(Event.Closed)
    la.join(); lb.join()
    assertEquals(shared.state.context(rozum).draft, "typed on A")
  }

  test("S2 — live == recovery: a restart over the same journal reaches the same session") {
    // the world outlives the session, as the daemon does: one fixture, two sessions over one journal
    val world = InMemory()
    val journal = Journal.memory()
    val live = Shared(world, journal)
    Seq(Event.Pressed(rozum), Event.Edited("compose", "kept"), Event.Pressed(nadia),
        Event.Edited("compose", "sent"), Event.Pressed("send"), Event.Pressed("refresh"), Event.Pressed(rozum))
      .foreach(live.apply)
    val recovered = Shared(world, journal)
    val same = (w: Workspace) => (w.focus, w.contexts)
    assertEquals(same(recovered.state), same(live.state))
    assertEquals(recovered.state.context(rozum).draft, "kept")
    // the refold does not speak in the room again: the message sent live is there ONCE
    assertEquals(world.posted(Room("nadia", "nadia")).count(_.text == "sent"), 1)
    // refreshes are not journaled; the human's acts are
    assertEquals(journal.lines.size, 6)
  }

  test("S2 — the file journal: appended intent-first, read back whole, damage dropped") {
    val dir = java.nio.file.Files.createTempDirectory("nadia-ui")
    val path = dir.resolve("state").resolve("workspace.jsonl")
    val j = Journal.file(path)
    val s = Shared(InMemory(), j)
    s(Event.Pressed(rozum)); s(Event.Edited("compose", "on disk"))
    java.nio.file.Files.writeString(path, "{ damaged\n", java.nio.file.StandardOpenOption.APPEND)
    val back = Shared(InMemory(), Journal.file(path))
    assertEquals(back.state.focus, Some(rozum))
    assertEquals(back.state.context(rozum).draft, "on disk")
    assertEquals(Journal.file(path).lines.size, 3)
  }
