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

  /** rozum's `DEFAULT_GATEWAY_PORT`. Reached only where no rozum CLI answers (a container); with
    * one, rozum says where its gateway is. This was `:8080` while the gateway lived on `:8089`.
    */
  val DefaultUrl = "http://127.0.0.1:8089/v1"

  /** The gateway's URL: what the environment says, else what rozum finds or starts, else the
    * default. Measured 2026-10-06: the shared gateway on this machine sat on 8089 while every
    * nadia defaulted to 8080 and reported "no gateway" — a wrong port is the one failure that
    * looks exactly like an absent server, and rozum already knows the right answer, so nobody
    * should have to be told it. Since 2026-10-08 rozum also STARTS it when nothing answers
    * (`rozum:docs/specs/gateway-ensure.md`) — so call this only where a local model is used.
    */
  def urlFromEnv(): String = explicit().orElse(discover()).getOrElse(DefaultUrl)

  /** The URL the environment names, if it names one. */
  def explicit(): Option[String] =
    sys.env.get("OPENAI_BASE_URL").orElse(sys.env.get("ROZUM_GATEWAY_URL")).map(Endpoint.withV1)

  /** Ask rozum: `gateway ensure` (find, or start through launchd or a spawned daemon), else, for
    * a rozum that predates it, `gateway status`. None when rozum is absent or has no gateway to
    * give; never a throw — a missing CLI is the normal case in a container.
    */
  def discover(): Option[String] = ensure().orElse(status())

  /** `rozum gateway ensure --json`. Up to 330 s: a gateway it starts loads its weights first. When
    * rozum answers that it could not start one, that reason is the one worth reading, so it is
    * printed rather than swallowed.
    */
  def ensure(timeoutMs: Long = 330000): Option[String] =
    rozum(List("gateway", "ensure", "--json"), timeoutMs).flatMap {
      case (0, out, _)   => urlFromEnsure(out)
      case (1, _, err)   => { Console.err.println(s"nadia: ${err.trim}"); None }
      case _             => None // an older rozum: no such subcommand
    }

  /** `rozum gateway status --json`: the port of a healthy gateway, starting nothing. */
  def status(timeoutMs: Long = 3000): Option[String] =
    rozum(List("gateway", "status", "--json"), timeoutMs).flatMap {
      case (0, out, _) => portFromStatus(out).map(p => s"http://127.0.0.1:$p/v1")
      case _           => None
    }

  private def rozum(args: List[String], timeoutMs: Long): Option[(Int, String, String)] =
    Try {
      val proc = ProcessBuilder(("rozum" :: args)*).start()
      val err = new StringBuilder
      val errReader = Thread.ofVirtual().start(() => err.append(new String(proc.getErrorStream.readAllBytes(), "UTF-8")))
      val out = new String(proc.getInputStream.readAllBytes(), "UTF-8")
      if !proc.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) then { proc.destroyForcibly(); None }
      else { errReader.join(); Some((proc.exitValue(), out, err.toString)) }
    }.toOption.flatten

  /** The `/v1` URL in `rozum gateway ensure --json`. Pure. */
  def urlFromEnsure(json: String): Option[String] =
    Try(ujson.read(json)("url").str).toOption.filter(_.startsWith("http")).map(Endpoint.withV1)

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
         |  answers with `$resident` and labels the reply `$wanted`. To be sure, switch the gateway to it:
         |      rozum gateway switch --model ${rozumSpec(wanted)}""".stripMargin
    }

  /** rozum launches with `org:repo`; the Hub writes `org/repo`. Same repository. */
  def rozumSpec(model: String): String =
    hfRepo(model).map(_.replaceFirst("/", ":")).getOrElse(model)
