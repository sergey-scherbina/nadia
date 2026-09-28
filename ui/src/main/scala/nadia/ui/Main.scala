package nadia.ui

import okay.*
import okay.given
import okay.ui.{Event, Host, Terminal, Telegram, Ui, Wire}

/**
 * The hosts. ONE program (`Workspace.view` / `Workspace.update`), three ways to
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
    val feed: Feed =
      if args.contains("--fixture") || sys.env.get("ROZUM_MEETING_TOKEN").forall(_.isEmpty) then Fixture()
      else Rozum(sys.env.getOrElse("ROZUM_MEETING_BASE", "http://127.0.0.1:8401"), sys.env("ROZUM_MEETING_TOKEN"))
    val init = Workspace.update(feed)(Workspace(), Event.Pressed("refresh"))
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

  private def terminal(feed: Feed, init: Workspace): Unit =
    val update = Workspace.update(feed)
    val end = Ui.runCmd(init)(Workspace.view(Layout.Wide()))((s, e) =>
      val s2 = update(s, e)
      (s2, if s2.quit then Vector(async(Event.Closed)) else Vector.empty))(Terminal.host(), ticks(3000)).runWith
    println(s"\nbye — ${end.contexts.size} contexts kept")

  private def chat(feed: Feed, init: Workspace): Unit =
    import Telegram.{Act, Key, Update}
    var buttons = Vector.empty[(String, String)]           // label -> callback data, numbered
    def show(m: Telegram.Message): Unit =
      println("─" * 40); println(m.text.replaceAll("<[^>]+>", ""))
      buttons = m.keyboard.flatten.collect { case Key.Press(l, d) => l -> d }
      buttons.zipWithIndex.foreach { case ((l, _), i) => println(s"  [${i + 1}] $l") }
    val (host, hear) = Telegram.host {
      case Act.Send(m) => async { show(m); Some(1L) }
      case Act.Edit(_, m) => async { show(m); None }
      case Act.Ask(prompt) => async { println(s"? $prompt"); None }
      case Act.Answer(_, notice) => async { if notice.nonEmpty then println(s"! $notice"); None }
    }
    val update = Workspace.update(feed)
    val loop = Async.spawn(Ui.run(init)(Workspace.view(Layout.Narrow()))(update)(host))
    val in = scala.io.Source.stdin.getLines()
    while in.hasNext do
      val line = in.next().trim
      val u = line.toIntOption.flatMap(i => buttons.lift(i - 1)).map((_, d) => Update.Pressed(d, "cb")).getOrElse(Update.Said(line))
      hear(u).runWith
    ()

  private def wire(feed: Feed, init: Workspace): Unit =
    val up = Channel[String](); val down = Channel[String]()
    val reader = new Thread(() => { scala.io.Source.stdin.getLines().foreach(up.offer); up.close() })
    reader.setDaemon(true); reader.start()
    val update = Workspace.update(feed)
    def drain(p: Workspace ! Writer % String + Async): Unit ! Async =
      Writer.uncons[String, Workspace, Async](p).flatMap {
        case Left(_) => async { down.close() }
        case Right((l, rest)) => async { println(l); System.out.flush() }.flatMap(_ => drain(rest))
      }
    drain(through[String, String, Async, Unit, Workspace](Writer.of(up))(
      !.widen[Workspace, okay.Take % String + Writer % String, Async](
        Wire.serveClosing(init)(Workspace.view(Layout.Wide()))((s, e) =>
          val s2 = update(s, e); (s2, s2.quit))))).runWith
