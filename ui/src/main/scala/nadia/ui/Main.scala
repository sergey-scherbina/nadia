package nadia.ui

import okay.*
import okay.given
import okay.ui.{Event, Host, Terminal, Telegram, Ui, Wire}

/**
 * The hosts. ONE program (`Program.view` / `Program.update`), three ways to
 * draw it, chosen by the first argument:
 *
 *   terminal   this terminal, in-process (`Terminal.host()`), the wide layout
 *   chat       the Telegram host driven from the console — the chat's acts are
 *              printed, its updates are read: a number presses that button, a
 *              line of text is what the person said. No Bot API yet: stage 1 of
 *              the spec hands the acts to rozum's bridge, stage 2 to a bot on okay
 *   wire       `Wire.serve` on stdin/stdout, JSON lines — what a thin client in
 *              any language (okay-compose, okay-swift, a browser page) speaks
 *
 * The world is the daemon at ROZUM_MEETING_BASE (default 127.0.0.1:8401) with
 * ROZUM_MEETING_TOKEN, or `--fixture` for the in-memory one.
 */
object Main:
  def main(args: Array[String]): Unit =
    val mode = args.headOption.getOrElse("terminal")
    val feed: WorldFeed =
      if args.contains("--fixture") || sys.env.get("ROZUM_MEETING_TOKEN").forall(_.isEmpty) then InMemory()
      else Rozum(sys.env.getOrElse("ROZUM_MEETING_BASE", "http://127.0.0.1:8401"), sys.env("ROZUM_MEETING_TOKEN"))
    val init = Program.update(feed)(Workspace(), Event.Pressed("refresh"))
    mode match
      case "terminal" => terminal(feed, init)
      case "chat" => chat(feed, init)
      case "wire" => wire(feed, init)
      case other => System.err.println(s"unknown mode $other: terminal | chat | wire [--fixture]")

  /** a refresh every few seconds, as an event the tree permits (the ↻ button is on it) */
  private def ticks(every: Long): Source[Event] =
    val ch = Channel[Event]()
    val t = new Thread(() => { while true do { Thread.sleep(every); ch.offer(Event.Pressed("refresh")) } })
    t.setDaemon(true); t.start()
    Writer.of(ch)

  private def terminal(feed: WorldFeed, init: Workspace): Unit =
    val update = Program.update(feed)
    val end = Ui.runCmd(init)(Program.view(Layout.Wide()))((s, e) =>
      val s2 = update(s, e)
      (s2, if s2.quit then Vector(async(Event.Closed)) else Vector.empty))(Terminal.host(), ticks(3000)).runWith
    println(s"\nbye — ${end.contexts.size} contexts kept")

  private def chat(feed: WorldFeed, init: Workspace): Unit =
    import Telegram.{Act, Key, Update}
    @volatile var buttons = Vector.empty[(String, String)] // label -> callback data, numbered
    val frames = new java.util.concurrent.atomic.AtomicInteger(0)
    def show(m: Telegram.Message): Unit =
      println("─" * 40); println(m.text.replaceAll("<[^>]+>", ""))
      buttons = m.keyboard.flatten.collect { case Key.Press(l, d) => l -> d }
      buttons.zipWithIndex.foreach { case ((l, _), i) => println(s"  [${i + 1}] $l") }
      frames.incrementAndGet(): Unit
    /** the next frame, or 300 ms — a redraw is asynchronous, a person is not */
    def settle(after: Int): Unit =
      val deadline = System.currentTimeMillis + 300
      while frames.get == after && System.currentTimeMillis < deadline do Thread.sleep(10)
    val (host, hear) = Telegram.host {
      case Act.Send(m) => async { show(m); Some(1L) }
      case Act.Edit(_, m) => async { show(m); None }
      case Act.Ask(prompt) => async { println(s"? $prompt"); None }
      case Act.Answer(_, notice) => async { if notice.nonEmpty then println(s"! $notice"); None }
    }
    val update = Program.update(feed)
    val loop = Async.spawn(Ui.run(init)(Program.view(Layout.Narrow()))(update)(host))
    settle(0)
    val in = scala.io.Source.stdin.getLines()
    while in.hasNext do
      val line = in.next().trim
      val before = frames.get
      val u = line.toIntOption.flatMap(i => buttons.lift(i - 1)).map((_, d) => Update.Pressed(d, "cb")).getOrElse(Update.Said(line))
      hear(u).runWith
      settle(before)
    println("bye")
    System.exit(0)

  private def wire(feed: WorldFeed, init: Workspace): Unit =
    val up = Channel[String](); val down = Channel[String]()
    val reader = new Thread(() => { scala.io.Source.stdin.getLines().foreach(up.offer); up.close() })
    reader.setDaemon(true); reader.start()
    val update = Program.update(feed)
    def drain(p: Workspace ! Writer % String + Async): Unit ! Async =
      Writer.uncons[String, Workspace, Async](p).flatMap {
        case Left(_) => async { down.close() }
        case Right((l, rest)) => async { println(l); System.out.flush() }.flatMap(_ => drain(rest))
      }
    drain(through[String, String, Async, Unit, Workspace](Writer.of(up))(
      !.widen[Workspace, okay.Take % String + Writer % String, Async](
        Wire.serveClosing(init)(Program.view(Layout.Wide()))((s, e) =>
          val s2 = update(s, e); (s2, s2.quit))))).runWith
