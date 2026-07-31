package agent

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import scala.util.Try

/** One assistant turn: whatever it said, and whatever it wants run. */
final case class Turn(text: String, calls: List[ToolCall])
final case class ToolCall(id: String, name: String, args: ujson.Value)

/** Contract 1 — the model call.
  *
  * An interface rather than a function so the loop can be driven by something other than a
  * network: a stub client is what makes the agent loop testable at all, and a loop that can
  * only be exercised against a live model is a loop nobody exercises.
  */
trait ModelClient:
  def chat(messages: List[ujson.Value], tools: List[Tool], sampling: Sampling): Either[String, Turn]

final case class Sampling(temperature: Double = 0.0, maxTokens: Int = 4096)

final case class Endpoint(baseUrl: String, model: String):
  val url: String = s"${Endpoint.withV1(baseUrl)}/chat/completions"

object Endpoint:
  /** The environment hands out two spellings of the same URL — `rozum launch` exports
    * `OPENAI_BASE_URL` with `/v1` and `ROZUM_GATEWAY_URL` without — so normalize instead of
    * making the caller know which variable they happened to set. Getting it wrong is a 404
    * with an empty body, which reads like anything but a path problem.
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

/** An OpenAI-compatible gateway over HTTP: `POST /v1/chat/completions`.
  *
  * Deliberately does NOT render tools into any model family's dialect. The gateway owns
  * that — Qwen's `<tool_call>`, GLM's `<arg_key>`, DeepSeek's separator tokens — along with
  * parsing the reply back and constraining decoding so arguments are valid by construction.
  * A second renderer here would be a second source of truth, and its failures would read as
  * model failures.
  */
final class HttpModelClient(endpoint: Endpoint, requestTimeout: Duration = Duration.ofMinutes(10))
    extends ModelClient:

  private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

  def chat(messages: List[ujson.Value], tools: List[Tool], sampling: Sampling): Either[String, Turn] =
    val body = ujson.Obj(
      "model" -> endpoint.model,
      "messages" -> ujson.Arr.from(messages),
      "tools" -> ujson.Arr.from(tools.map(Wire.toolSchema)),
      "tool_choice" -> "auto",
      "temperature" -> sampling.temperature,
      "max_tokens" -> sampling.maxTokens
    )
    val req = HttpRequest
      .newBuilder(URI.create(endpoint.url))
      .header("content-type", "application/json")
      .timeout(requestTimeout)
      .POST(HttpRequest.BodyPublishers.ofString(ujson.write(body)))
      .build()
    Try(http.send(req, HttpResponse.BodyHandlers.ofString())).toEither.left
      .map(t => s"gateway unreachable: ${t.getMessage}")
      .flatMap { resp =>
        if resp.statusCode() != 200 then
          Left(s"gateway returned ${resp.statusCode()}: ${resp.body().take(200)}")
        else Wire.readTurn(resp.body())
      }

/** The wire format, in one place so the loop never touches JSON shapes. */
object Wire:

  def toolSchema(t: Tool): ujson.Value =
    ujson.Obj(
      "type" -> "function",
      "function" -> ujson.Obj(
        "name" -> t.name,
        "description" -> t.description,
        "parameters" -> t.schema
      )
    )

  def system(content: String): ujson.Value = ujson.Obj("role" -> "system", "content" -> content)
  def user(content: String): ujson.Value = ujson.Obj("role" -> "user", "content" -> content)
  def assistant(content: String): ujson.Value = ujson.Obj("role" -> "assistant", "content" -> content)

  def assistantCalls(t: Turn): ujson.Value =
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

  def toolResult(id: String, content: String): ujson.Value =
    ujson.Obj("role" -> "tool", "tool_call_id" -> id, "content" -> content)

  def readTurn(payload: String): Either[String, Turn] =
    Try {
      val m = ujson.read(payload)("choices")(0)("message")
      val text = Try(m("content").str).getOrElse("")
      val calls = Try(m("tool_calls").arr).getOrElse(scala.collection.mutable.ArrayBuffer.empty).toList.map { c =>
        val f = c("function")
        // `arguments` arrives as a JSON *string*. A model that emits something unparseable
        // must not take the loop down with it: an empty object reaches the handler, which
        // then reports the missing argument by name — which the model can fix.
        ToolCall(c("id").str, f("name").str, Try(ujson.read(f("arguments").str)).getOrElse(ujson.Obj()))
      }
      Turn(text, calls)
    }.toEither.left.map(t => s"could not read the reply: ${t.getMessage}")
