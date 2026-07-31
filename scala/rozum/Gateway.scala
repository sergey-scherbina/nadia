package nadia.rozum

import agent.{Endpoint, HttpModelClient, ModelClient}

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

  def urlFromEnv(): String =
    sys.env
      .get("OPENAI_BASE_URL")
      .orElse(sys.env.get("ROZUM_GATEWAY_URL"))
      .map(Endpoint.withV1)
      .getOrElse(DefaultUrl)

  /** The gateway serves whichever model is resident, so a caller that does not care can
    * say so and let the server decide.
    */
  def modelFromEnv(): String = sys.env.getOrElse("NADIA_MODEL", "local")

  def client(url: String, model: String): ModelClient =
    HttpModelClient(Endpoint(Endpoint.withV1(url), model))
