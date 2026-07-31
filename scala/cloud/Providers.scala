package nadia.cloud

import agent.{Auth, Endpoint}
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.{Files, Paths}
import java.time.{Duration, Instant}
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** Where the model comes from when it does not come from this machine.
  *
  * Everything here resolves to the same two values the agent already understands — a base URL
  * and an `Auth` — because AWS and Google both speak OpenAI-compatible chat completions, and
  * a provider that needed its own request shape would belong in the SDK, not in a lookup
  * table. What differs between them is only the URL, and where the bearer token comes from.
  *
  * The local rozum gateway stays the default and stays credential-free. That is the property
  * worth protecting: a hosted model is an option here, never a requirement.
  */
object Provider:

  val names = List("local", "openai", "bedrock", "vertex")

  /** Resolve the flags into one endpoint, or say exactly which flag is missing.
    *
    * Refusing with the name of the missing value is the whole job. The alternative — build a
    * URL with an empty segment in it and let the request fail — produces a 404 that reads
    * like an outage.
    */
  def resolve(
      name: String,
      gateway: String,
      model: String,
      region: Option[String],
      project: Option[String],
      keyFile: Option[String]
  ): Either[String, Endpoint] =
    for
      key <- Credential.staticKey(keyFile)
      _ <- placeholderModel(name, model)
      ep <- name match
        case "local" =>
          // The one case where no credential is not an error: a gateway on loopback.
          Right(Endpoint(gateway, model, key.map(Auth.Bearer.apply).getOrElse(Auth.Anonymous)))

        case "openai" =>
          key
            .toRight("--provider openai needs a key: set NADIA_API_KEY or pass --api-key-file")
            .map(k => Endpoint(gateway, model, Auth.Bearer(k)))

        case "bedrock" =>
          for
            r <- region.orElse(env("AWS_REGION", "AWS_DEFAULT_REGION")).toRight(
              "--provider bedrock needs --region (or AWS_REGION), e.g. us-east-1"
            )
            k <- key
              .orElse(env("AWS_BEARER_TOKEN_BEDROCK"))
              .toRight(
                "--provider bedrock needs a Bedrock API key: set AWS_BEARER_TOKEN_BEDROCK or pass --api-key-file"
              )
          yield Endpoint(s"https://bedrock-mantle.$r.api.aws/v1", model, Auth.Bearer(k))

        case "vertex" =>
          for
            p <- project.orElse(env("GOOGLE_CLOUD_PROJECT", "GCP_PROJECT")).toRight(
              "--provider vertex needs --project (or GOOGLE_CLOUD_PROJECT)"
            )
            l <- region.orElse(env("GOOGLE_CLOUD_REGION", "GOOGLE_CLOUD_LOCATION")).toRight(
              "--provider vertex needs --region (or GOOGLE_CLOUD_REGION), e.g. europe-west4 or global"
            )
          yield
            val host = if l == "global" then "aiplatform.googleapis.com" else s"$l-aiplatform.googleapis.com"
            val base = s"https://$host/v1/projects/$p/locations/$l/endpoints/openapi"
            // A static key wins if one was supplied; otherwise ask Google for a fresh one on
            // every request, because the ones it issues expire inside an hour.
            Endpoint(base, model, key.map(Auth.Bearer.apply).getOrElse(Auth.Fresh(GoogleToken.get)))

        case other =>
          Left(s"unknown provider `$other` — one of ${names.mkString(", ")}")
    yield ep

  /** `local` is the model id a caller uses to mean "whatever you have resident", which is a
    * rozum gateway idea. Sent to Bedrock or Vertex it is a 404 on a model name, and that
    * error does not say "you forgot --model".
    */
  private def placeholderModel(provider: String, model: String): Either[String, Unit] =
    if provider == "local" || model != "local" then Right(())
    else
      Left(
        s"--provider $provider needs an explicit --model (or NADIA_MODEL) — " +
          (if provider == "bedrock" then "e.g. us.anthropic.claude-sonnet-4-6"
           else if provider == "vertex" then "e.g. google/gemini-3-flash"
           else "the model id your endpoint serves")
      )

  private def env(names: String*): Option[String] =
    names.iterator.flatMap(n => sys.env.get(n)).map(_.trim).find(_.nonEmpty)

/** Bearer tokens, from the places a deployment actually keeps them. */
object Credential:

  /** A fixed key, if there is one anywhere.
    *
    * A *file* is listed first and is the one to use under an orchestrator: Kubernetes and
    * ECS can both project a secret as a mounted file, and unlike an environment variable a
    * file is not inherited by every child process the agent spawns — and this agent spawns
    * `bash` on a model's say-so.
    *
    * There is deliberately no `--api-key <value>` flag. It would put the key in `ps` output
    * and in the operator's shell history, and both outlive the run.
    */
  def staticKey(file: Option[String]): Either[String, Option[String]] =
    file match
      case Some(f) => readKeyFile(f).map(Some(_))
      case None =>
        Right(
          List("NADIA_API_KEY", "OPENAI_API_KEY").iterator
            .flatMap(sys.env.get)
            .map(_.trim)
            .find(_.nonEmpty)
        )

  private def readKeyFile(f: String): Either[String, String] =
    val p = Paths.get(f)
    if !Files.isReadable(p) then Left(s"--api-key-file $f is not readable")
    else
      Try(Files.readString(p).trim).toEither.left
        .map(t => s"--api-key-file $f: ${t.getMessage}")
        .flatMap(s => if s.isEmpty then Left(s"--api-key-file $f is empty") else Right(s))

/** Google's short-lived OAuth tokens, renewed on demand.
  *
  * On GKE, Cloud Run and GCE there is no key material at all: the workload's identity is the
  * credential and the metadata server hands out tokens against it. That is the deployment
  * worth supporting properly, because it is the one where nothing has to be copied anywhere.
  * `gcloud` is the fallback for a developer's laptop, which has no metadata server.
  */
object GoogleToken:

  private val Metadata =
    "http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/token"

  private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()

  private var cached: Option[(String, Instant)] = None

  /** Cached until a minute before expiry — a token that is valid when checked and expired
    * when it arrives is the classic version of this bug.
    */
  def get(): Either[String, String] = synchronized {
    val now = Instant.now()
    cached.filter(_._2.isAfter(now)) match
      case Some((t, _)) => Right(t)
      case None =>
        fromMetadata().orElse(fromGcloud()) match
          case Right((t, ttl)) =>
            cached = Some((t, now.plusSeconds(math.max(ttl - 60, 30))))
            Right(t)
          case Left(e) => Left(e)
  }

  private def fromMetadata(): Either[String, (String, Long)] =
    val req = HttpRequest
      .newBuilder(URI.create(Metadata))
      .header("Metadata-Flavor", "Google")
      .timeout(Duration.ofSeconds(5))
      .GET()
      .build()
    Try(http.send(req, HttpResponse.BodyHandlers.ofString())).toEither.left
      .map(t => s"metadata server unreachable (${t.getMessage})")
      .flatMap { r =>
        if r.statusCode() != 200 then Left(s"metadata server returned ${r.statusCode()}")
        else
          Try {
            val j = ujson.read(r.body())
            (j("access_token").str, Try(j("expires_in").num.toLong).getOrElse(3600L))
          }.toEither.left.map(t => s"metadata token unreadable: ${t.getMessage}")
      }

  private def fromGcloud(): Either[String, (String, Long)] =
    val pb = ProcessBuilder(List("gcloud", "auth", "print-access-token").asJava)
    pb.redirectErrorStream(false)
    Try {
      val proc = pb.start()
      val out = String(proc.getInputStream.readAllBytes, "UTF-8").trim
      val err = String(proc.getErrorStream.readAllBytes, "UTF-8").trim
      if !proc.waitFor(30, TimeUnit.SECONDS) then
        proc.destroyForcibly()
        Left("gcloud auth print-access-token timed out")
      else if proc.exitValue != 0 || out.isEmpty then
        Left(s"gcloud auth print-access-token failed: ${err.take(200)}")
      else Right((out, 3600L))
    }.toEither.left
      .map(t => s"no Google credential: metadata server unreachable and gcloud not usable (${t.getMessage})")
      .flatten
