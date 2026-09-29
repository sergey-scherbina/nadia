package nadia

import okay.*
import okay.given
import okay.agent.{Budget, Fleet, Model, Phase, Provider, Spec}
import okay.llm.{Models, Transports as LlmTransports}
import okay.persist.{FileStore, Wire}
import okay.security.{Channel as Door, Roster}
import nadia.agent.NadiaRunner
import nadia.rozum.Gateway
import _root_.agent.{Auth, Endpoint}

/**
 * THE SERVICE — arguments in, the platform wired, nothing else
 * (docs/specs/app.md; what it owes the workspace UI is BACKLOG NAD-14).
 *
 *   nadia-app serve                 the fleet, the roster, the state log served over the wire
 *   nadia-app run "<task>" [DIR]    one agent on the fleet, to its end; prints the result, exits
 *                                   0 on Done, 1 otherwise (the batch contract of SPEC §4.1)
 *
 * Environment: NADIA_STATE (~/.nadia/app), NADIA_PROJECTS (roots, ':'-separated, default
 * the cwd), NADIA_TELEGRAM_OWNER (the owner's user id; the console is the owner too),
 * ROZUM_GATEWAY_URL / OPENAI_BASE_URL (localhost:8080), NADIA_MODEL, NADIA_PROVIDER
 * (rozum | anthropic | openai), ANTHROPIC_API_KEY, OPENAI_API_KEY, NADIA_MAX_STEPS (24),
 * NADIA_WIRE_PORT (8791), NADIA_WIRE_TOKEN (the UI's token; loopback only when unset).
 */
object Main:
  def env(k: String): Option[String] = sys.env.get(k).map(_.trim).filter(_.nonEmpty)

  /** everything a run needs, built once from the environment */
  final class Service:
    val state = java.nio.file.Paths.get(env("NADIA_STATE").getOrElse(sys.props("user.home") + "/.nadia/app"))
    java.nio.file.Files.createDirectories(state)
    val store = FileStore.open(state)
    val roots = env("NADIA_PROJECTS").map(_.split(':').toVector.filter(_.nonEmpty)).getOrElse(Vector(sys.props("user.dir")))
    val roster = env("NADIA_TELEGRAM_OWNER") match
      case Some(id) => Roster.owned(store.topic("roster"), Door.Telegram, id)
      case None => Roster.owned(store.topic("roster"), Door.Console, "")

    // the model: rozum by default; anthropic or openai when asked and a key exists
    val gateway = Gateway.urlFromEnv()                    // …/v1
    val base = gateway.stripSuffix("/v1").stripSuffix("/")
    val provider = env("NADIA_PROVIDER").filter(p => p == "rozum" || (p == "anthropic" && env("ANTHROPIC_API_KEY").isDefined)
      || (p == "openai" && env("OPENAI_API_KEY").isDefined)).getOrElse("rozum")
    val chosen = Gateway.modelFromEnv()
    val wire = LlmTransports.http()
    def modelHandler(spec: Spec): Handler[Model] =
      val m = spec.model.getOrElse(chosen)
      provider match
        case "anthropic" => Provider.anthropic(wire, env("ANTHROPIC_API_KEY").getOrElse(""), m)
        case "openai" => Provider.openAi(wire, env("OPENAI_API_KEY").getOrElse(""), m)
        case _ => Provider.openAi(wire, "", m, url = s"$gateway/chat/completions")
    /** the verify gate speaks the OpenAI form; a hosted Anthropic run is not gated */
    def gateClient(spec: Spec): Option[_root_.agent.ModelClient] = provider match
      case "anthropic" => None
      case "openai" => Some(Gateway.client(Endpoint("https://api.openai.com/v1", spec.model.getOrElse(chosen), Auth.Anonymous)))
      case _ => Some(Gateway.client(Endpoint(gateway, spec.model.getOrElse(chosen))))
    val models: Models.Catalog & Models.Residency = Models.rozum(wire, base)

    private var fleetRef: Fleet = null
    val runner = NadiaRunner(() => fleetRef, modelHandler, gateClient)
    fleetRef = NadiaRunner.block(Fleet.open(store, runner))
    val fleet: Fleet = fleetRef
    val budget = Budget(env("NADIA_MAX_STEPS").flatMap(_.toIntOption).getOrElse(24), 30 * 60 * 1000L)

  def main(args: Array[String]): Unit = args.toList match
    case "serve" :: _ =>
      val s = Service()
      val token = env("NADIA_WIRE_TOKEN")
      val port = env("NADIA_WIRE_PORT").flatMap(_.toIntOption).getOrElse(8791)
      // the UI reads the record and, once fleet-commands lands, writes commands; the roster
      // and everything else stay the service's
      val served = Set("agents", "commands")
      val server = Wire.Server(s.store, t => if token.forall(_ == t) then Some(served) else None, requested = port)
      Console.err.println(s"nadia-app: serving ${served.mkString(", ")} on 127.0.0.1:${server.port}" +
        s" · ${s.fleet.all.size} agents on record · model ${s.provider}/${s.chosen} · roots ${s.roots.mkString(":")}")
      Thread.currentThread.join()
    case "run" :: task :: rest =>
      val s = Service()
      val dir = rest.headOption.getOrElse(sys.props("user.dir"))
      val id = NadiaRunner.block(s.fleet.spawn(Spec(task, dir, s.budget, None, Some(s.chosen))))
      val st = NadiaRunner.block(s.fleet.await(id))
      st.foreach { r =>
        println(r.result.getOrElse(""))
        r.report.foreach(j => Console.err.println(s"nadia-app: ${okay.codec.Json.print(j)}"))
        Console.err.println(s"nadia-app: #${r.id.n} ${r.phase} after ${r.step} steps")
      }
      sys.exit(if st.exists(_.phase == Phase.Done) then 0 else 1)
    case _ =>
      Console.err.println("usage: nadia-app serve | nadia-app run \"<task>\" [DIR]")
      sys.exit(2)
