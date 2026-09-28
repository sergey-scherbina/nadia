package nadia.ui

import okay.ui.{Event, Frame, Telegram, Ui}

/** `sbt "Test/runMain nadia.ui.Show"`: the same state, as the terminal draws it
 * and as a chat draws it — the wide and the narrow layout of one program */
object Show:
  def main(args: Array[String]): Unit =
    val f = InMemory()
    val step = Program.update(f)
    val s = Seq(Event.Pressed("refresh"), Event.Pressed("room.rozum.rozum"),
                Event.Edited("compose", "on it, measuring")).foldLeft(Workspace())(step)
    println("── terminal (wide), 100 columns ──")
    Frame.render(Program.view(Layout.Wide())(s), None, width = 100).foreach(println)
    println()
    println("── chat (narrow): the one message, edited in place ──")
    val (m, _) = Telegram.render(Program.view(Layout.Narrow())(s), frame = 1)
    println(m.text)
    m.keyboard.foreach(row => println("  " + row.map { case Telegram.Key.Press(l, _) => s"[$l]"; case Telegram.Key.Open(l, _) => s"[$l ↗]" }.mkString(" ")))
    println()
    println("── chat (narrow), the room list ──")
    val (m0, _) = Telegram.render(Program.view(Layout.Narrow())(step(s, Event.Pressed("rooms"))), frame = 2)
    println(m0.text)
    m0.keyboard.foreach(row => println("  " + row.map { case Telegram.Key.Press(l, _) => s"[$l]"; case Telegram.Key.Open(l, _) => s"[$l ↗]" }.mkString(" ")))
