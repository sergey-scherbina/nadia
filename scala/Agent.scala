package nadia

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.{Duration, Instant}
import scala.util.Try

/** Contract 1 — the model call. `POST /v1/chat/completions`, OpenAI form. */
final case class Endpoint(baseUrl: String, model: String):
  val url: String = s"${Endpoint.withV1(baseUrl)}/chat/completions"

object Endpoint:
  /** The environment hands out two spellings of the same URL — `rozum launch` exports
    * `OPENAI_BASE_URL` with `/v1` and `ROZUM_GATEWAY_URL` without — so normalize instead
    * of making the caller know which variable they happened to set. Getting it wrong is a
    * 404 with an empty body, which reads like anything but a path problem.
    */
  def withV1(u: String): String =
    val t = u.stripSuffix("/")
    if t.endsWith("/v1") then t else s"$t/v1"

  def fromEnv(): String =
    sys.env
      .get("OPENAI_BASE_URL")
      .orElse(sys.env.get("ROZUM_GATEWAY_URL"))
      .map(withV1)
      .getOrElse("http://127.0.0.1:8080/v1")

/** One executed tool call and what it produced — the audit trail of side effects. */
final case class Op(tool: String, input: ujson.Value, output: Either[String, ujson.Value])

enum Stop:
  case Done, BudgetSteps, BudgetTime
  case Error(message: String)

final case class Outcome(
    text: String,
    stop: Stop,
    steps: Int,
    operations: List[Op],
    transcript: List[ujson.Value]
)

final case class Budget(
    maxSteps: Int = 24,
    maxTokens: Int = 4096,
    wallTime: Duration = Duration.ofMinutes(15),
    temperature: Double = 0.0
)

/** What the model is told before anything else.
  *
  * Deliberately short: a small local model follows five rules better than a page of them,
  * and every token here is re-sent on every step of every turn.
  */
def systemPrompt(root: String): String =
  s"""You are nadia, a coding agent working in $root.
     |
     |Work by calling tools, not by describing what should be done. When the task needs a
     |file changed, change it; do not print the file and stop.
     |
     |Before you claim a task is finished, verify it: run the build, the test, or the
     |program with `bash` and READ the output. Exiting 0 proves nothing on its own —
     |compare what it printed against what the task asked for, value by value and in the
     |right order. If they differ, the task is NOT finished: fix the code and run it again.
     |Never report success you have not observed.
     |
     |Read a file before editing it, and quote `old_string` exactly as it appears. Make the
     |smallest change that satisfies the task.
     |
     |When the task is genuinely done, reply with a short plain-text summary. That final
     |message ends the task, so do not send it while work remains.""".stripMargin

/** Contract 2 — the agent loop, and the guard that keeps a small model from spinning in it. */
object Agent:

  private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

  /** How far back the repetition guard looks, and how many identical calls-with-identical
    * results count as stuck. Both halves matter: matching the call alone flags the verify
    * step of fix → test → fix, which is progress, not a spin — the same command returns
    * something different each time because the files changed underneath it.
    */
  private val Window = 12
  private val Repeats = 4

  def run(
      endpoint: Endpoint,
      system: String,
      user: String,
      tools: List[Tool],
      budget: Budget = Budget(),
      observer: Observer = Observer.Silent
  ): Outcome =
    val started = Instant.now()
    val byName = tools.map(t => t.name -> t).toMap
    val schemas = ujson.Arr.from(tools.map(toolSchema))
    var messages = List(msg("system", system), msg("user", user))
    var ops = List.empty[Op]
    var history = List.empty[String]
    var steps = 0

    def outcome(text: String, stop: Stop) = Outcome(text, stop, steps, ops.reverse, messages)

    var result: Option[Outcome] = None
    while result.isEmpty do
      if steps >= budget.maxSteps then result = Some(outcome("", Stop.BudgetSteps))
      else if Duration.between(started, Instant.now()).compareTo(budget.wallTime) >= 0 then
        result = Some(outcome("", Stop.BudgetTime))
      else
        steps += 1
        chat(endpoint, messages, schemas, budget, observer) match
          case Left(err) => result = Some(outcome("", Stop.Error(err)))
          case Right(turn) if turn.calls.isEmpty =>
            if turn.text.nonEmpty then messages = messages :+ msg("assistant", turn.text)
            result = Some(outcome(turn.text, Stop.Done))
          case Right(turn) =>
            messages = messages :+ assistantWithCalls(turn)
            turn.calls.foreach { c =>
              observer.onToolCall(c.name, c.args)
              val key = s"${c.name}:${ujson.write(c.args)}"
              val stuck = history.take(Window).count(_ == key) >= Repeats - 1
              val out =
                if stuck then
                  Left(
                    s"You have called `${c.name}` with these exact arguments several times and " +
                      "got the same result every time. Repeating it will not help. Re-read the " +
                      "current state of the file or the command output, and either take a " +
                      "different approach or stop and report what is blocking you."
                  )
                else
                  byName.get(c.name) match
                    case Some(t) => t.run(c.args)
                    case None    => Left(s"unknown tool: ${c.name}")
              observer.onToolResult(c.name, out.left.toOption)
              // The result is part of the key: an identical call whose output changed is
              // the agent making progress, not repeating itself.
              history = s"$key=>${out.fold(e => s"err:$e", ujson.write(_))}" :: history
              ops = Op(c.name, c.args, out) :: ops
              messages = messages :+ toolResult(c.id, out.fold(identity, ujson.write(_)))
            }
    result.get

  private final case class Call(id: String, name: String, args: ujson.Value)
  private final case class Turn(text: String, calls: List[Call])

  private def chat(
      e: Endpoint,
      messages: List[ujson.Value],
      tools: ujson.Value,
      budget: Budget,
      observer: Observer
  ): Either[String, Turn] =
    val body = ujson.Obj(
      "model" -> e.model,
      "messages" -> ujson.Arr.from(messages),
      "tools" -> tools,
      "tool_choice" -> "auto",
      "temperature" -> budget.temperature,
      "max_tokens" -> budget.maxTokens
    )
    val req = HttpRequest
      .newBuilder(URI.create(e.url))
      .header("content-type", "application/json")
      .timeout(Duration.ofMinutes(10))
      .POST(HttpRequest.BodyPublishers.ofString(ujson.write(body)))
      .build()
    Try(http.send(req, HttpResponse.BodyHandlers.ofString())).toEither.left
      .map(t => s"gateway unreachable: ${t.getMessage}")
      .flatMap { resp =>
        if resp.statusCode() != 200 then
          Left(s"gateway returned ${resp.statusCode()}: ${resp.body().take(200)}")
        else
          Try {
            val m = ujson.read(resp.body())("choices")(0)("message")
            val text = Try(m("content").str).getOrElse("")
            if text.nonEmpty then observer.onText(text)
            val calls = Try(m("tool_calls").arr).getOrElse(scala.collection.mutable.ArrayBuffer.empty).toList.map { c =>
              val f = c("function")
              // `arguments` is a JSON *string* on the wire, and a model that emits nothing
              // parseable must not take the loop down with it — an empty object reaches the
              // handler, which then reports the missing argument by name.
              Call(c("id").str, f("name").str, Try(ujson.read(f("arguments").str)).getOrElse(ujson.Obj()))
            }
            Turn(text, calls)
          }.toEither.left.map(t => s"could not read the reply: ${t.getMessage}")
      }

  private def toolSchema(t: Tool): ujson.Value =
    ujson.Obj(
      "type" -> "function",
      "function" -> ujson.Obj("name" -> t.name, "description" -> t.description, "parameters" -> t.schema)
    )

  private def msg(role: String, content: String): ujson.Value =
    ujson.Obj("role" -> role, "content" -> content)

  private def assistantWithCalls(t: Turn): ujson.Value =
    ujson.Obj(
      "role" -> "assistant",
      "content" -> t.text,
      "tool_calls" -> ujson.Arr.from(t.calls.map { c =>
        ujson.Obj(
          "id" -> c.id,
          "type" -> "function",
          "function" -> ujson.Obj("name" -> c.name, "arguments" -> ujson.write(c.args))
        )
      })
    )

  private def toolResult(id: String, content: String): ujson.Value =
    ujson.Obj("role" -> "tool", "tool_call_id" -> id, "content" -> content)

/** A live view of a run. The loop returns only when a turn is over, which is right for a
  * batch caller and wrong for an interactive one: a chat that says nothing for a minute is
  * indistinguishable from a chat that has hung.
  */
trait Observer:
  def onText(text: String): Unit = ()
  def onToolCall(name: String, args: ujson.Value): Unit = ()
  def onToolResult(name: String, error: Option[String]): Unit = ()

object Observer:
  object Silent extends Observer
