package agent

import java.time.{Duration, Instant}

/** Contract 2 — the agent loop.
  *
  * `[system, user] → model → (tool calls → dispatch → results)* → final text`, bounded by a
  * budget. The application supplies the tools and the prompt; the loop supplies nothing
  * except the turn-taking, which is the whole point of separating them.
  */
object AgentLoop:

  def run(
      client: ModelClient,
      system: String,
      user: String,
      tools: List[Tool],
      budget: Budget = Budget(),
      observer: Observer = Observer.Silent
  ): Outcome =
    resume(client, List(Wire.system(system), Wire.user(user)), tools, budget, observer)

  /** The same loop over an EXISTING conversation — what a multi-turn chat needs. Feed back
    * the transcript of the previous turn with the new user message appended and the model
    * keeps its context (and the gateway keeps its KV prefix, which is what makes turn N+1
    * cheap).
    */
  def resume(
      client: ModelClient,
      conversation: List[ujson.Value],
      tools: List[Tool],
      budget: Budget = Budget(),
      observer: Observer = Observer.Silent
  ): Outcome =
    val started = Instant.now()
    val byName = tools.map(t => t.name -> t).toMap
    val guard = LoopGuard()
    var messages = conversation
    var ops = List.empty[Op]
    var steps = 0

    def finish(text: String, stop: Stop) = Outcome(text, stop, steps, ops.reverse, messages)

    var result: Option[Outcome] = None
    while result.isEmpty do
      if steps >= budget.maxSteps then result = Some(finish("", Stop.BudgetSteps))
      else if Duration.between(started, Instant.now()).compareTo(budget.wallTime) >= 0 then
        result = Some(finish("", Stop.BudgetTime))
      else
        steps += 1
        client.chat(messages, tools, budget.sampling) match
          case Left(err) => result = Some(finish("", Stop.Error(err)))

          case Right(turn) if turn.calls.isEmpty =>
            // No tool calls means the model is answering rather than working: the turn ends.
            if turn.text.nonEmpty then
              observer.onText(turn.text)
              messages = messages :+ Wire.assistant(turn.text)
            result = Some(finish(turn.text, Stop.Done))

          case Right(turn) =>
            if turn.text.nonEmpty then observer.onText(turn.text)
            messages = messages :+ Wire.assistantCalls(turn)
            turn.calls.foreach { c =>
              observer.onToolCall(c.name, c.args)
              val out = guard.check(c) match
                case Some(refusal) => Left(refusal)
                case None =>
                  byName.get(c.name) match
                    case Some(t) => t.run(c.args)
                    case None    => Left(s"unknown tool: ${c.name}")
              guard.record(c, out)
              observer.onToolResult(c.name, out.left.toOption)
              ops = Op(c.name, c.args, out) :: ops
              messages = messages :+ Wire.toolResult(c.id, out.fold(identity, ujson.write(_)))
            }
    result.get

/** Bounds on a run. Exhaustion returns a partial [[Outcome]] rather than throwing — a
  * budget is a stop condition, not a failure.
  */
final case class Budget(
    maxSteps: Int = 24,
    wallTime: Duration = Duration.ofMinutes(15),
    sampling: Sampling = Sampling()
)

enum Stop:
  case Done, BudgetSteps, BudgetTime
  case Error(message: String)

/** One executed tool call and what it produced — the audit trail of side effects. */
final case class Op(tool: String, input: ujson.Value, output: Either[String, ujson.Value])

final case class Outcome(
    text: String,
    stop: Stop,
    steps: Int,
    operations: List[Op],
    transcript: List[ujson.Value]
)

/** Refuses a call that is going in circles.
  *
  * Budgets bound the damage; they do not stop the failure a small model actually exhibits,
  * which is re-issuing an identical call **after an identical result** — most often an edit
  * whose `old_string` is not in the file, read as "try again" rather than "the premise is
  * wrong".
  *
  * Both halves of that sentence are load-bearing. Matching on the call alone flags the
  * verify step of fix → test → fix, which is progress: the same `cargo test` returns
  * something different each time because the files changed underneath it. Measured on the
  * 2026-07-31 rozum matrix, the call-only form cut 11 of 16 cells for one agent and 6 of 16
  * for another while leaving a third untouched — the skew was the tell.
  *
  * The intervention is an error the model reads, not a silent halt: a halt leaves it unable
  * to say what it had already done.
  */
final class LoopGuard(window: Int = 12, repeats: Int = 4):
  private var history = List.empty[String]

  private def key(c: ToolCall) = s"${c.name}:${ujson.write(c.args)}"

  def check(c: ToolCall): Option[String] =
    // Same call AND same result, every time. Counting by call alone is the defect this
    // guard exists to avoid — it would flag a verify loop, where the command is identical
    // and the output is not.
    val prefix = key(c) + "=>"
    val recent = history.take(window).filter(_.startsWith(prefix))
    Option.when(recent.length >= repeats - 1 && recent.distinct.length == 1)(
      s"You have called `${c.name}` with these exact arguments several times and got the same " +
        "result every time. Repeating it will not help. Re-read the current state of the file " +
        "or the command output, and either take a different approach or stop and report what " +
        "is blocking you."
    )

  def record(c: ToolCall, out: Either[String, ujson.Value]): Unit =
    val fingerprint = out.fold(e => s"err:$e", ujson.write(_))
    history = s"${key(c)}=>$fingerprint" :: history

/** A live view of a run.
  *
  * The loop returns only when a turn is over, which is right for a batch caller and wrong
  * for an interactive one: a session that says nothing for a minute is indistinguishable
  * from one that has hung. Every method defaults to a no-op, so an implementor takes only
  * the events it wants and adding an event later is not a breaking change.
  */
trait Observer:
  def onText(text: String): Unit = ()
  def onToolCall(name: String, args: ujson.Value): Unit = ()
  def onToolResult(name: String, error: Option[String]): Unit = ()

object Observer:
  object Silent extends Observer
