package nadia.rozum

import agent.{Endpoint, HttpModelClient, ModelClient}
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import scala.util.Try

/** How this agent finds the rozum gateway.
  *
  * Lives here rather than in the SDK because it is entirely rozum's convention: the
  * variable names, the two spellings of the same URL, and the port a local gateway
  * defaults to. A generic agent SDK that knew `ROZUM_GATEWAY_URL` would be a generic SDK
  * with one project's habits baked into it.
  *
  * Honouring both variables is what makes this agent a drop-in in rozum's matrix without
  * touching the launcher: `rozum launch` already exports `OPENAI_BASE_URL` (with `/v1`)
  * and `ROZUM_GATEWAY_URL` (without) to every agent it starts. They are the same URL in
  * two spellings, which is why the SDK normalizes and this layer only has to pick.
  */
object Gateway:

  val DefaultUrl = "http://127.0.0.1:8080/v1"

  /** The gateway's URL: what the environment says, else what rozum itself says it is running,
    * else the historical default. Measured 2026-10-06: the shared gateway on this machine sat on
    * 8089 while every nadia defaulted to 8080 and reported "no gateway" — a wrong port is the one
    * failure that looks exactly like an absent server, and rozum already knows the right answer
    * (`rozum gateway status --json`, `gateway.port`), so nobody should have to be told it.
    */
  def urlFromEnv(): String =
    sys.env
      .get("OPENAI_BASE_URL")
      .orElse(sys.env.get("ROZUM_GATEWAY_URL"))
      .map(Endpoint.withV1)
      .orElse(discover())
      .getOrElse(DefaultUrl)

  /** Ask rozum where its gateway is. None when rozum is absent, slow, or has no healthy gateway;
    * never a throw — a missing CLI is the normal case in a container.
    */
  def discover(timeoutMs: Long = 3000): Option[String] =
    Try {
      val pb = ProcessBuilder("rozum", "gateway", "status", "--json").redirectErrorStream(false)
      val proc = pb.start()
      val out = new String(proc.getInputStream.readAllBytes(), "UTF-8")
      if !proc.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) then { proc.destroyForcibly(); None }
      else if proc.exitValue() != 0 then None
      else portFromStatus(out).map(p => s"http://127.0.0.1:$p/v1")
    }.toOption.flatten

  /** The port in `rozum gateway status --json`, when the gateway there is healthy. Pure. */
  def portFromStatus(json: String): Option[Int] =
    Try {
      val g = ujson.read(json)("gateway")
      val healthy = Try(g("healthy").bool).getOrElse(true)
      Try(g("port").num.toInt).toOption.filter(_ > 0).filter(_ => healthy)
    }.toOption.flatten

  /** The gateway serves whichever model is resident, so a caller that does not care can
    * say so and let the server decide.
    */
  def modelFromEnv(): String = sys.env.getOrElse("NADIA_MODEL", "local")

  /** The read timeout is generous because the first request to a cold gateway pays for
    * loading the weights, and a hosted endpoint under load can be slower still. Being
    * impatient here turns a slow start into a reported model failure.
    */
  def client(endpoint: Endpoint): ModelClient =
    HttpModelClient(endpoint, requestTimeout = Duration.ofMinutes(10))

  /** One model, several valid spellings — rozum's own rule, mirrored.
    *
    * `mlx-community:Qwen3.5-4B-MLX-4bit` is how a gateway is launched, `hf:org/repo` and
    * `mlx-community/Qwen3.5-4B-MLX-4bit` are how the Hub writes the same repository, and the
    * last is what anyone copying an id off a model page will send. Comparing them as strings
    * is the bug this method exists to avoid; rozum had it too, in the gateway's own warm
    * cache, where it warmed a second resident copy of weights already loaded.
    */
  def sameModel(a: String, b: String): Boolean =
    if a == b then true
    else
      (hfRepo(a), hfRepo(b)) match
        case (Some(x), Some(y)) => x.equalsIgnoreCase(y)
        case _                  => false

  private def hfRepo(spec: String): Option[String] =
    val s = spec.stripPrefix("hf:")
    val candidate = if s.contains('/') then s else s.replaceFirst(":", "/")
    Option.when(candidate.count(_ == '/') == 1 && !candidate.startsWith("/") && !candidate.contains(':'))(
      candidate
    )

  /** What model this gateway currently has resident, if it will say. */
  def residentModel(baseUrl: String): Option[String] =
    val req = HttpRequest
      .newBuilder(URI.create(s"${Endpoint.withV1(baseUrl)}/models"))
      .timeout(Duration.ofSeconds(5))
      .GET()
      .build()
    Try {
      val r = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString())
      val first = ujson.read(r.body())("data")(0)
      Try(first("display_name").str).getOrElse(first("id").str)
    }.toOption.filter(_.nonEmpty)

  /** A warning when the gateway is serving something other than what was asked for.
    *
    * Worth a round trip because of how this fails otherwise. A rozum gateway asked for a
    * model it does not have and cannot warm does not refuse — it answers **with the model it
    * does have**, and the reply carries the requested id back, so nothing in the transcript
    * says a different model wrote it. That is a wrong answer wearing the right label, and it
    * is the one failure here that cannot be noticed by reading the output.
    *
    * A warning rather than a refusal: a gateway legitimately serves a second cached model
    * alongside its primary, and refusing would break that. Silence is the only option that
    * is definitely wrong.
    */
  def residentWarning(baseUrl: String, wanted: String): Option[String] =
    residentModel(baseUrl).filterNot(sameModel(_, wanted)).map { resident =>
      s"""warning — this gateway has `$resident` resident, not `$wanted`.
         |  It will serve `$wanted` only if those weights are already downloaded; otherwise it
         |  answers with `$resident` and labels the reply `$wanted`. To be sure, run a gateway on it:
         |      rozum gateway --model ${rozumSpec(wanted)} --port 8080""".stripMargin
    }

  /** rozum launches with `org:repo`; the Hub writes `org/repo`. Same repository. */
  def rozumSpec(model: String): String =
    hfRepo(model).map(_.replaceFirst("/", ":")).getOrElse(model)
