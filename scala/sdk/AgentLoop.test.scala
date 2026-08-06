package agent

/** A scripted model. This is the point of `ModelClient` being an interface: before the
  * split, the loop could only be exercised against a live gateway, which means it was
  * exercised rarely and never for its edge cases.
  */
final class ScriptedClient(script: List[Turn]) extends ModelClient:
  private var remaining = script
  var seen: List[List[ujson.Value]] = Nil

  def chat(messages: List[ujson.Value], tools: List[Tool], sampling: Sampling): Either[String, Turn] =
    seen = messages :: seen
    remaining match
      case h :: t => remaining = t; Right(h)
      case Nil    => Right(Turn("(script exhausted)", Nil))

object Fixtures:
  def call(id: String, name: String, args: (String, ujson.Value)*): ToolCall =
    ToolCall(id, name, ujson.Obj.from(args))

  def tool(name: String, result: ujson.Value => Either[String, ujson.Value]): Tool =
    Tool(name, "a tool", Schema.obj()(), result)

class AgentLoopSuite extends munit.FunSuite:
  import Fixtures.*

  test("dispatches a tool call, feeds the result back, and ends on a text turn") {
    var got = Option.empty[String]
    val tools = List(tool("echo", args => { got = Some(args("say").str); Right(ujson.Obj("ok" -> true)) }))
    val client = ScriptedClient(
      List(
        Turn("", List(call("c1", "echo", "say" -> ujson.Str("hi")))),
        Turn("done", Nil)
      )
    )

    val r = AgentLoop.run(client, "sys", "do it", tools)

    assertEquals(got, Some("hi"))
    assertEquals(r.stop, Stop.Done)
    assertEquals(r.text, "done")
    assertEquals(r.steps, 2)
    assertEquals(r.operations.map(_.tool), List("echo"))

    // The second request must carry the assistant turn AND the tool result, or the model
    // is answering a question it cannot see the answer to.
    val second = client.seen.reverse(1)
    assert(second.exists(m => m("role").str == "tool"), second.toString)
    assert(second.exists(m => m("role").str == "assistant"), second.toString)
  }

  test("an unknown tool is recoverable, not fatal") {
    val client = ScriptedClient(
      List(Turn("", List(call("c1", "nope"))), Turn("recovered", Nil))
    )
    val r = AgentLoop.run(client, "sys", "go", Nil)
    assertEquals(r.stop, Stop.Done)
    assert(r.operations.head.output.left.exists(_.contains("unknown tool")))
  }

  test("a handler failure reaches the model as the next prompt") {
    val tools = List(tool("boom", _ => Left("old_string matched 0 times")))
    val client = ScriptedClient(List(Turn("", List(call("c1", "boom"))), Turn("ok", Nil)))
    val r = AgentLoop.run(client, "sys", "go", tools)
    val toolMsg = r.transcript.findLast(m => m("role").str == "tool").get
    assert(toolMsg("content").str.contains("matched 0 times"), toolMsg.toString)
  }

  test("the step budget stops the loop and returns what happened so far") {
    val tools = List(tool("spin", _ => Right(ujson.Obj("n" -> 1))))
    // A model that never stops asking for tools.
    val client = new ModelClient:
      private var n = 0
      def chat(m: List[ujson.Value], t: List[Tool], s: Sampling) =
        n += 1
        Right(Turn("", List(call(s"c$n", "spin", "n" -> ujson.Num(n)))))
    val r = AgentLoop.run(client, "sys", "go", tools, Budget(maxSteps = 3))
    assertEquals(r.stop, Stop.BudgetSteps)
    assertEquals(r.steps, 3)
    assertEquals(r.operations.length, 3)
  }

  test("a transport failure ends the run as an error, not as a finished task") {
    val client = new ModelClient:
      def chat(m: List[ujson.Value], t: List[Tool], s: Sampling) = Left("gateway unreachable")
    val r = AgentLoop.run(client, "sys", "go", Nil)
    assertEquals(r.stop, Stop.Error("gateway unreachable"))
    assertEquals(r.text, "")
  }

  test("resume continues an existing conversation instead of starting one") {
    val client = ScriptedClient(List(Turn("second answer", Nil)))
    val prior = List(Wire.system("sys"), Wire.user("first"), Wire.assistant("first answer"))
    val r = AgentLoop.resume(client, prior :+ Wire.user("second"), Nil)
    assertEquals(r.text, "second answer")
    // What the model saw must include the earlier turns — that is the whole difference
    // between a chat and a sequence of unrelated questions.
    val sent = client.seen.head
    assert(sent.exists(m => m("content").str == "first answer"), sent.toString)
    assert(r.transcript.length > prior.length)
  }

class LoopGuardSuite extends munit.FunSuite:

  test("a guard that refused says so, so the caller can start clean"):
    // The refusal is the last thing left in the transcript, and a small model answers the next
    // turn by quoting it — measured in the Rust twin (BUG-027): one step, zero tool calls, and the
    // guard's own sentence as the whole reply. A caller can only avoid that if it is TOLD.
    val g = LoopGuard()
    assert(!g.tripped)
    val call = ToolCall("1", "bash", ujson.Obj("command" -> "cargo build"))
    for _ <- 1 to 3 do g.record(call, Left("same error"))
    assert(g.check(call).isDefined, "four identical calls with one result must be refused")
    assert(g.tripped, "the guard refused and did not say so")

  import Fixtures.*

  private def repeat(guard: LoopGuard, c: ToolCall, out: Either[String, ujson.Value], times: Int) =
    (1 to times).map { _ =>
      val refusal = guard.check(c)
      if refusal.isEmpty then guard.record(c, out)
      refusal
    }.toList

  test("an identical call with an identical result is refused") {
    val guard = LoopGuard()
    val c = call("c", "bash", "command" -> ujson.Str("cargo test"))
    val results = repeat(guard, c, Right(ujson.Obj("out" -> "2 failed")), 5)
    assert(results.take(3).forall(_.isEmpty), "the first three must run")
    assert(results(3).exists(_.contains("Repeating it will not help")), results.toString)
  }

  test("an identical call whose result keeps changing is not a loop") {
    // The regression this pins: matching on the call alone flags the verify half of
    // fix -> test -> fix, where the same command returns something different each time
    // because the files changed underneath it.
    val guard = LoopGuard()
    val c = call("c", "bash", "command" -> ujson.Str("cargo test"))
    (1 to 8).foreach { i =>
      assertEquals(guard.check(c), None, s"call $i must not be mistaken for a loop")
      guard.record(c, Right(ujson.Obj("failures" -> i)))
    }
  }

  test("different arguments are not a loop") {
    val guard = LoopGuard()
    (1 to 8).foreach { i =>
      val c = call("c", "bash", "command" -> ujson.Str(s"echo $i"))
      assertEquals(guard.check(c), None)
      guard.record(c, Right(ujson.Obj("out" -> "same")))
    }
  }

class WireSuite extends munit.FunSuite:
  test("unparseable tool arguments become an empty object rather than killing the run") {
    // A small model emitting a truncated JSON string must not take the loop down; the
    // handler then reports the missing argument by name, which the model can act on.
    val payload = ujson.write(
      ujson.Obj(
        "choices" -> ujson.Arr(
          ujson.Obj(
            "message" -> ujson.Obj(
              "content" -> "",
              "tool_calls" -> ujson.Arr(
                ujson.Obj(
                  "id" -> "c1",
                  "function" -> ujson.Obj("name" -> "read_file", "arguments" -> "{\"path\": ")
                )
              )
            )
          )
        )
      )
    )
    val turn = Wire.readTurn(payload).fold(e => fail(e), identity)
    assertEquals(turn.calls.head.name, "read_file")
    assertEquals(turn.calls.head.args, ujson.Obj())
  }

  test("a reply with neither content nor tool calls is an empty final turn") {
    val payload = """{"choices":[{"message":{}}]}"""
    val turn = Wire.readTurn(payload).fold(e => fail(e), identity)
    assertEquals(turn.text, "")
    assert(turn.calls.isEmpty)
  }
