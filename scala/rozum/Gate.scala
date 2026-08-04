package nadia.rozum

import agent.{ModelClient, Verify}
import java.nio.file.Path

/** The policy around `agent.Verify` — `SPEC.md` §3.1.
  *
  * The primitives decide *what* the check is and whether it passed. This decides how many repair
  * rounds a run gets, what the operator is told, and when nothing is checked at all. Same split as
  * the Rust implementation (`rozum-agent::verify` / `nadia::gate`), for the same reason: the
  * primitives are shared and the policy is the application's.
  *
  * Opt-out (`NADIA_VERIFY=0`) rather than opt-in, because the failure it prevents is silent — an
  * unverified run and a verified one look identical until someone reads the output.
  */
object Gate:

  /** What the gate concluded, for the operator rather than for the model. */
  final case class Report(
      check: Option[String] = None,
      passed: Option[Boolean] = None,
      detail: String = "",
      rounds: Int = 0
  ):
    /** One line. Says "not checked" out loud: silence about verification is what let a wrong
      * answer read as a finished task.
      */
    def summary: String = (passed, check) match
      case (Some(true), Some(c)) =>
        val extra = if rounds > 0 then s" (after $rounds repair round(s))" else ""
        s"✔ check passed: $c$extra"
      case (Some(false), Some(c)) => s"✘ check FAILED: $c\n${clip(detail, 600)}"
      case (Some(true), None)     => "✔ the model-judge confirmed the result"
      case (Some(false), None)    => s"✘ the model-judge rejected it: ${clip(detail, 400)}"
      case (None, _)              => "⚠ not checked — the task has no machine-checkable criterion"

  private def clip(s: String, n: Int): String = if s.length <= n then s else s.take(n) + "…"

  private def env(name: String): Option[String] = sys.env.get(name).map(_.trim).filter(_.nonEmpty)

  def enabled: Boolean = !env("NADIA_VERIFY").exists(Set("0", "off", "false", "no"))

  /** Repair rounds after the first attempt. Two, like the Rust one and like `rozum launch`: a
    * model that has not converged in two is not converging, and each round is a full turn.
    */
  def rounds: Int = env("NADIA_VERIFY_ROUNDS").flatMap(_.toIntOption).getOrElse(2)

  /** The check for this task, decided BEFORE the run so the run cannot influence it. Precedence:
    * what the task makes checkable, then the workspace's floor, then nothing.
    */
  def derive(client: ModelClient, task: String, workspace: Path): Option[String] =
    if !enabled then None
    else
      Verify.deriveCheck(client, task) match
        case Some(c) if Verify.isHallucinatedCargoCheck(c, workspace, task) => None
        case Some(c)                                                        => Some(c)
        case None                                                           => Verify.cargoFloor(workspace)

  /** Check a finished attempt. Returns the report and, when a repair is warranted, the message for
    * the agent's next round.
    *
    * `finished` is whether the agent stopped of its own accord: a run that exhausted its budget has
    * a better explanation than a judge's opinion, so the semantic tier does not run for it.
    */
  def check(
      client: ModelClient,
      task: String,
      workspace: Path,
      cmd: Option[String],
      finished: Boolean
  ): (Report, Option[String]) =
    cmd match
      case None if !enabled || !finished => (Report(), None)
      case None =>
        Verify.judge(client, task, workspace) match
          case Verify.Verdict.Pass => (Report(passed = Some(true)), None)
          case Verify.Verdict.Fail(reason) =>
            (
              Report(passed = Some(false), detail = reason),
              Some(s"A reviewer judged the task NOT accomplished: $reason\n\nFix it and say what you changed.")
            )
          case Verify.Verdict.Unknown(_) => (Report(), None)
      case Some(c) =>
        val (passed, output) = Verify.runCheck(c, workspace)
        val report = Report(check = Some(c), passed = Some(passed), detail = if passed then "" else output)
        (report, Option.when(!passed)(Verify.repairPrompt(c, output, workspace)))
