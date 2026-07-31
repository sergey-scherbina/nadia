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

/** How a request proves it is allowed to ask.
  *
  * `Anonymous` is the default and stays the default: the agent has to remain usable against
  * a gateway on the same machine with no account, no key and no network. Everything hosted
  * wants a bearer token instead.
  *
  * The general case is a *supplier*, not a string, because Google's tokens expire in an hour
  * — shorter than a long agent run, so a token read once at startup dies mid-task. A fixed
  * key is then the degenerate case of a supplier, rather than the other way round.
  */
sealed trait Auth:
  /** The header value to send, or the reason one could not be obtained. */
  def header: Either[String, Option[String]]

object Auth:
  case object Anonymous extends Auth:
    def header: Either[String, Option[String]] = Right(None)

  /** `toString` is redacted deliberately. This value is reachable from `Endpoint`, which
    * appears in exception messages and debug prints, and a key that is printed once is a key
    * that has leaked.
    */
  final case class Bearer(token: String) extends Auth:
    def header: Either[String, Option[String]] = Right(Some(s"Bearer $token"))
    override def toString: String = "Bearer(<redacted>)"

  /** Re-asked before every request, so the supplier owns caching and renewal. */
  final case class Fresh(get: () => Either[String, String]) extends Auth:
    def header: Either[String, Option[String]] = get().map(t => Some(s"Bearer $t"))
    override def toString: String = "Fresh(<supplier>)"

final case class Endpoint(baseUrl: String, model: String, auth: Auth = Auth.Anonymous):
  val url: String = s"${Endpoint.withV1(baseUrl)}/chat/completions"

object Endpoint:
  /** OpenAI-compatible base URLs circulate in two spellings, with and without the `/v1`
    * segment, and callers rarely know which one they were handed. Complete rather than make
    * them care: getting it wrong is a 404 with an empty body, which reads like anything
    * except a path problem.
    *
    * Only a *bare origin* is completed. A base URL that already carries a path is somebody's
    * deliberate route and must survive untouched — Vertex AI's
    * `…/locations/L/endpoints/openapi` is the case that taught us this, and the earlier rule
    * ("append unless it ends in /v1") turned it into `…/openapi/v1/chat/completions`, which
    * 404s exactly like a wrong host or a wrong key.
    */
  def withV1(u: String): String =
    val t = u.stripSuffix("/")
    if hasPath(t) then t else s"$t/v1"

  private def hasPath(u: String): Boolean =
    Try(URI.create(u).getPath).toOption.exists(p => p != null && p.nonEmpty && p != "/")

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
    // Asked per request, not per client: a credential with an hour of life must be renewed
    // under a run that outlives it, and this is the only place that knows a request is
    // about to happen.
    endpoint.auth.header.left.map(e => s"no credential: $e").flatMap { auth =>
      val builder = HttpRequest
        .newBuilder(URI.create(endpoint.url))
        .header("content-type", "application/json")
        .timeout(requestTimeout)
        .POST(HttpRequest.BodyPublishers.ofString(ujson.write(body)))
      auth.foreach(v => builder.header("authorization", v))
      val req = builder.build()
      Try(http.send(req, HttpResponse.BodyHandlers.ofString())).toEither.left
        // `getMessage` is null on a bare ConnectException, and "gateway unreachable: null"
        // is the first thing anyone sees when a deployment points at the wrong address —
        // the class name at least says whether it was refused, timed out or never resolved.
        .map(t => s"gateway unreachable at ${endpoint.url}: ${describe(t)}")
        .flatMap { resp =>
          if resp.statusCode() != 200 then
            // 401/403 are the two that a deployment gets wrong, and "gateway returned 401"
            // on its own sends people to look at the model id. Name the likely cause.
            val hint = resp.statusCode() match
              case 401 | 403 => " — the credential was rejected; check the key and its scope"
              case 404       => s" — nothing at ${endpoint.url}; check the base URL"
              case _         => ""
            Left(s"gateway returned ${resp.statusCode()}$hint: ${resp.body().take(200)}")
          else Wire.readTurn(resp.body())
        }
    }

  private def describe(t: Throwable): String =
    Option(t.getMessage).filter(_.nonEmpty).getOrElse(t.getClass.getSimpleName)

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
