package nadia.agent

import okay.*
import okay.given
import okay.agent.{Agent, AgentId, Compact, Context, Fleet, Handlers, Model, Outcome, Phase, Runner, Spec, Tool, ToolCall, Toolbox, Turn}
import okay.codec.Json
import nadia.rozum.{Gate, Sandbox, Tools, systemPrompt}

/**
 * ONE AGENT'S RUN — nadia's policy over okay-agent's loop (docs/specs/app.md).
 *
 * What is nadia's here and nowhere else: the six tools and the sandbox
 * (`scala/rozum`, unchanged), the prompt, the verify gate, and the rule
 * that a parent may `delegate`. What is okay's: the loop (`Agent`), the
 * context fold, the model provider, the fleet that owns the budget, the
 * pause and the stop.
 *
 * The turn is ONE synchronous program on the agent's fiber (a virtual
 * thread): the tool handler asks the fleet's checkpoint before each call
 * and parks there while paused, which is what lets the six tools stay the
 * plain functions they already are.
 */
final class NadiaRunner(fleet: () => Fleet, model: Spec => Handler[Model],
                        gate: Spec => Option[_root_.agent.ModelClient]) extends Runner:

  def run(id: AgentId, spec: Spec, ctx: Fleet.Ctx): Outcome ! Async = async {
    Sandbox.at(spec.workspace) match
      case Left(err) => Outcome(s"workspace: $err", None, Phase.Failed)
      case Right(sb) => attempt(id, spec, ctx, sb)
  }

  private def attempt(id: AgentId, spec: Spec, ctx: Fleet.Ctx, sb: Sandbox): Outcome =
    val six = Tools.all(sb).foldLeft(Toolbox.empty) { (box, t) =>
      box.raw(t.name, t.description, Json.parse(ujson.write(t.schema))) { args =>
        t.run(ujson.read(Json.print(args))).fold(identity, ujson.write(_))
      }
    }
    val box: Toolbox.In[Async] = six.in[Async] ++ Fleet.delegate(fleet(), id)
    var step = 0
    var stopped = false
    // every tool call passes the fleet's checkpoint: the step is recorded,
    // a pause waits here, a stop or an exhausted budget ends the turn
    val table: Map[String, ToolCall => String] = box.table.map { (name, f) =>
      name -> { (c: ToolCall) =>
        step += 1
        NadiaRunner.block(ctx.checkpoint(step, name)) match
          case Some(_) => stopped = true; "stopped: the operator halted this run, or its budget is spent — answer with what you have"
          case None => NadiaRunner.block(f(c))
      }
    }
    val (_, inner) = Handlers.context(Compact.all)
    val recording: Handler[Context] = new:
      def handle[A](e: Context[A]): A =
        e match
          case Context.Remember(t) => ctx.turned(t)
          case _ => ()
        inner.handle(e)

    def loop(): String ! Agent =
      okay.!.each(ctx.inbox())(m => Agent.remember(Turn.User(m))).flatMap { _ =>
        Agent.complete(box.specs).flatMap { r =>
          Agent.remember(Turn.Assistant(r.text, r.calls)).flatMap { _ =>
            if r.calls.isEmpty || stopped then pure(r.text)
            else Agent.runTools(r.calls).flatMap(_ => if stopped then pure(r.text) else loop())
          }
        }
      }
    def turn(first: String): String =
      NadiaRunner.runAgent(Agent.remember(Turn.User(first)).flatMap(_ => loop()))(model(spec), Handlers.tools(table), recording)

    NadiaRunner.runAgent(Agent.remember(Turn.System(systemPrompt(sb.root.toString))))(model(spec), Handlers.tools(table), recording)
    var text = turn(spec.task)
    if stopped then Outcome(text, None, Phase.Interrupted)
    else
      // the verify gate — SPEC.md §3.1, the same policy the batch runner applies
      gate(spec) match
        case None => Outcome(text, None, Phase.Done)
        case Some(client) =>
          val check = Gate.derive(client, spec.task, sb.root)
          var report = Gate.Report()
          var round = 0
          var going = true
          while going do
            Gate.check(client, spec.task, sb.root, check, finished = !stopped) match
              case (rep, Some(prompt)) if round < Gate.rounds && !stopped =>
                report = rep.copy(rounds = round); round += 1
                text = turn(prompt)
              case (rep, _) =>
                report = rep.copy(rounds = round); going = false
          Outcome(text, Some(NadiaRunner.reportJson(report)), if stopped then Phase.Interrupted else Phase.Done)

object NadiaRunner:
  /** run an Async program to its answer on this (virtual) thread */
  def block[A](p: A ! Async): A = Async.run[A, Pure](p).runWith

  /** the row's handlers, assembled as okay-agent's own suite does */
  def runAgent[A](prog: A ! Agent)(model: Handler[Model], tool: Handler[Tool], ctx: Handler[Context]): A =
    given Handler[Model] = model
    given Handler[Tool] = tool
    given Handler[Context] = ctx
    given rowCA: Handler[Context + Async] = Handler.union[Context, Async]
    given rowTCA: Handler[Tool + (Context + Async)] = Handler.union[Tool, Context + Async]
    given rowAll: Handler[Agent] = Handler.union[Model, Tool + (Context + Async)]
    prog.runWith

  def reportJson(r: Gate.Report): Json =
    import Json.*
    JObj(Vector(
      "check" -> r.check.fold[Json](JNull)(JStr(_)),
      "passed" -> r.passed.fold[Json](JNull)(JBool(_)),
      "detail" -> JStr(r.detail),
      "rounds" -> JNum(r.rounds.toDouble),
      "summary" -> JStr(r.summary)))
