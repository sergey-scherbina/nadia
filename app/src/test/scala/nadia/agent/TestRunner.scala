package nadia.agent

import okay.*
import okay.given
import okay.agent.{Budget, Fleet, Handlers, Phase, Reply, Spec, ToolCall, Turn}
import okay.agent.Fleet.Control
import okay.codec.Json
import okay.persist.MemoryStore

/**
 * docs/specs/app.md — the runner, with no model and no gateway: a scripted
 * model reads a file through the real `read_file` in a real temporary
 * workspace, and the fleet's record shows the turns and the steps.
 */
class TestRunner extends munit.FunSuite:
  def go[A](p: A ! Async): A = Async.run[A, Pure](p).runWith
  def until(what: => Boolean, ms: Int = 10_000): Unit =
    val end = System.currentTimeMillis + ms
    while !what && System.currentTimeMillis < end do Thread.sleep(10)
    assert(what, s"waited ${ms}ms")

  def workspace(): String =
    val d = java.nio.file.Files.createTempDirectory("nadia-app-test")
    java.nio.file.Files.writeString(d.resolve("hello.txt"), "one\ntwo\n")
    d.toRealPath().toString

  /** a model that asks for one tool, then answers */
  def scripted(replies: Seq[Reply]) = Handlers.scripted(replies)

  test("a task runs through the six tools: the model's read_file call is executed in the workspace, the transcript records it") {
    val dir = workspace()
    val model = scripted(Seq(
      Reply("", Seq(ToolCall("c1", "read_file", Json.JObj(Vector("path" -> Json.JStr("hello.txt")))))),
      Reply("the file has two lines", Nil)))
    var fleet: Fleet = null
    val runner = NadiaRunner(() => fleet, _ => model, _ => None)
    fleet = go(Fleet.open(MemoryStore(), runner))
    val id = go(fleet.spawn(Spec("say how many lines hello.txt has", dir, Budget(5, 60_000))))
    val st = go(fleet.await(id)).get
    assertEquals(st.phase, Phase.Done)
    assertEquals(st.result, Some("the file has two lines"))
    assertEquals(st.step, 1)
    assertEquals(st.lastTool, Some("read_file"))
    val turns = fleet.transcript(id)
    assert(turns.exists { case Turn.Result("c1", content) => content.contains("two"); case _ => false }, turns.toString)
    assert(turns.head.isInstanceOf[Turn.System])
    assertEquals(turns.collect { case Turn.User(t) => t }, Vector("say how many lines hello.txt has"))
  }

  test("a path outside the workspace is refused by the sandbox, and the model reads the refusal as the tool's answer") {
    val dir = workspace()
    val model = scripted(Seq(
      Reply("", Seq(ToolCall("c1", "read_file", Json.JObj(Vector("path" -> Json.JStr("../../etc/passwd")))))),
      Reply("refused", Nil)))
    var fleet: Fleet = null
    fleet = go(Fleet.open(MemoryStore(), NadiaRunner(() => fleet, _ => model, _ => None)))
    val id = go(fleet.spawn(Spec("read it", dir, Budget(5, 60_000))))
    val st = go(fleet.await(id)).get
    assertEquals(st.phase, Phase.Done)
    val result = fleet.transcript(id).collectFirst { case Turn.Result(_, c) => c }.get
    assert(!result.contains("root:"), result)
  }

  test("a stop between tool calls ends the run Interrupted with what the model had") {
    val dir = workspace()
    val model = scripted(Seq(
      Reply("", Seq(ToolCall("c1", "read_file", Json.JObj(Vector("path" -> Json.JStr("hello.txt")))))),
      Reply("", Seq(ToolCall("c2", "read_file", Json.JObj(Vector("path" -> Json.JStr("hello.txt")))))),
      Reply("", Seq(ToolCall("c3", "read_file", Json.JObj(Vector("path" -> Json.JStr("hello.txt")))))),
      Reply("never", Nil)))
    var fleet: Fleet = null
    fleet = go(Fleet.open(MemoryStore(), NadiaRunner(() => fleet, _ => model, _ => None)))
    val id = go(fleet.spawn(Spec("loop", dir, Budget(2, 60_000))))   // two steps: the third call is refused
    val st = go(fleet.await(id)).get
    assertEquals(st.phase, Phase.Interrupted)
    assertEquals(st.step, 3)
  }

  test("delegate: a parent's tool runs a child through the same runner") {
    val dir = workspace()
    val parent = scripted(Seq(
      Reply("", Seq(ToolCall("d1", "delegate", Json.JObj(Vector("task" -> Json.JStr("count lines"), "budget" -> Json.JNum(2)))))),
      Reply("child said it", Nil)))
    val child = scripted(Seq(Reply("two lines", Nil)))
    var fleet: Fleet = null
    fleet = go(Fleet.open(MemoryStore(), NadiaRunner(() => fleet, s => if s.parent.isDefined then child else parent, _ => None)))
    val p = go(fleet.spawn(Spec("whole", dir, Budget(6, 60_000))))
    val st = go(fleet.await(p)).get
    assertEquals(st.phase, Phase.Done)
    assertEquals(st.children.size, 1)
    val c = fleet.status(st.children.head).get
    assertEquals((c.phase, c.result, c.workspace), (Phase.Done, Some("two lines"), dir))
    val answer = fleet.transcript(p).collectFirst { case Turn.Result("d1", r) => r }.get
    assert(answer.contains("\"result\":\"two lines\""), answer)
  }

  test("asks: a write_file call waits for the operator; yes writes the file, no comes back as a refusal the model reads") {
    val dir = workspace()
    def writing(name: String) = Reply("", Seq(ToolCall("w", "write_file", Json.JObj(Vector("path" -> Json.JStr(name), "content" -> Json.JStr("x"))))))
    val model = scripted(Seq(writing("a.txt"), writing("b.txt"), Reply("done", Nil)))
    var fleet: Fleet = null
    fleet = go(Fleet.open(MemoryStore(), NadiaRunner(() => fleet, _ => model, _ => None, _ => true)))
    val id = go(fleet.spawn(Spec("write two files", dir, Budget(5, 60_000))))
    until(fleet.status(id).exists(_.asking.exists(_.tool == "write_file")))
    assertEquals(fleet.status(id).flatMap(_.asking).map(_.seq), Some(1L))
    assert(go(fleet.send(id, Control.Approve(1, true))))
    until(fleet.status(id).exists(_.asking.exists(_.seq == 2)))
    assert(java.nio.file.Files.exists(java.nio.file.Paths.get(dir, "a.txt")), "approved: written")
    assert(go(fleet.send(id, Control.Approve(2, false))))
    val st = go(fleet.await(id)).get
    assertEquals(st.phase, Phase.Done)
    assert(!java.nio.file.Files.exists(java.nio.file.Paths.get(dir, "b.txt")), "refused: not written")
    val refusal = fleet.transcript(id).collect { case Turn.Result("w", c) => c }.last
    assert(refusal.startsWith("refused:"), refusal)
  }
