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
    val fixture = args.contains("--fixture") || sys.env.get("ROZUM_MEETING_TOKEN").forall(_.isEmpty)
    val feed: WorldFeed =
      if fixture then InMemory()
      else Rozum(sys.env.getOrElse("ROZUM_MEETING_BASE", "http://127.0.0.1:8401"), sys.env("ROZUM_MEETING_TOKEN"))
    // ONE session on disk, whichever host opens it: quit the terminal, open the
    // chat, the draft and the room are there. `--fresh` starts it over.
    val journalPath = sys.env.get("NADIA_UI_JOURNAL").map(java.nio.file.Path.of(_)).getOrElse {
      val state = sys.env.get("XDG_STATE_HOME").map(java.nio.file.Path.of(_))
        .getOrElse(java.nio.file.Path.of(sys.props("user.home"), ".local", "state"))
      state.resolve("nadia").resolve(if fixture then "workspace-fixture.jsonl" else "workspace.jsonl")
    }
    if args.contains("--fresh") then java.nio.file.Files.deleteIfExists(journalPath): Unit
    val shared = Shared(feed, Journal.file(journalPath))
    mode match
      case "terminal" => terminal(shared)
      case "chat" => chat(shared)
      case "wire" => wire(shared)
      case other => System.err.println(s"unknown mode $other: terminal | chat | wire [--fixture] [--fresh]")

  /** a refresh every few seconds, as an event the tree permits (the ↻ button is on it) */
  private def ticks(every: Long): Source[Event] =
    val ch = Channel[Event]()
    val t = new Thread(() => { while true do { Thread.sleep(every); ch.offer(Event.Pressed("refresh")) } })
    t.setDaemon(true); t.start()
    Writer.of(ch)

  private def terminal(shared: Shared): Unit =
    val end = shared.attach(Terminal.host(), Layout.Wide(), ticks(3000)).runWith
    println(s"\nbye — ${end.contexts.size} contexts kept")

  private def chat(shared: Shared): Unit =
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
    Async.spawn(shared.attach(host, Layout.Narrow())): Unit
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

  private def wire(shared: Shared): Unit =
    val up = Channel[String]()
    val reader = new Thread(() => { scala.io.Source.stdin.getLines().foreach(up.offer); up.close() })
    reader.setDaemon(true); reader.start()
    def drain(p: Workspace ! Writer % String + Async): Unit ! Async =
      Writer.uncons[String, Workspace, Async](p).flatMap {
        case Left(_) => pure(())
        case Right((l, rest)) => async { println(l); System.out.flush() }.flatMap(_ => drain(rest))
      }
    drain(shared.serve(Layout.Wide())(Writer.of(up))).runWith
