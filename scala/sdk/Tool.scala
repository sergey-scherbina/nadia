package agent

/** Contract 3 — a tool the model may call.
  *
  * `run` returns `Left(message)` for a recoverable failure. That message is not an error in
  * the exception sense: it goes back to the model as the tool result, so it is the next
  * prompt. Write it as an instruction — what went wrong, and what to do differently.
  *
  * The SDK never performs a side effect. Every effect lives in an application's handler,
  * and every handler validates first; that separation is what makes it safe to point a
  * small, unpredictable model at a real machine.
  */
final case class Tool(
    name: String,
    description: String,
    schema: ujson.Value,
    run: ujson.Value => Either[String, ujson.Value]
)

/** Building the JSON Schema a tool advertises.
  *
  * Strict by construction: `required` is explicit and `additionalProperties` is false, so a
  * model that invents a parameter is refused by the gateway's constrained decoding rather
  * than silently ignored.
  */
object Schema:
  def prop(kind: String, description: String): ujson.Value =
    ujson.Obj("type" -> kind, "description" -> description)

  def obj(props: (String, ujson.Value)*)(required: String*): ujson.Value =
    ujson.Obj(
      "type" -> "object",
      "properties" -> ujson.Obj.from(props),
      "required" -> ujson.Arr.from(required.map(ujson.Str(_))),
      "additionalProperties" -> false
    )

/** Argument access that fails the way the model needs it to.
  *
  * A missing argument must come back naming the argument — "missing required string
  * argument `path`" is something a model can act on; a type error from deep inside a
  * handler is not.
  */
object Args:

  /** A required string, accepting any JSON scalar in its place.
    *
    * A number, a boolean or null has exactly one obvious textual form, and a model that
    * answers `{"content": 4}` when asked to write the number four has not made a mistake
    * worth failing a task over. Refusing it was not strictness, it was pedantry with a
    * misleading error attached: the message said the argument was *missing* when it had been
    * supplied, so the model re-sent the same call verbatim — four times, until the
    * repetition guard stopped the run. Found by running the agent in a container against a
    * real 4B model, on a task it had otherwise already solved.
    *
    * Objects and arrays are still refused. There is no single right way to render those as
    * text, and guessing one would put invented content into a file.
    */
  def str(v: ujson.Value, key: String): Either[String, String] =
    scala.util.Try(v(key)).toOption match
      case Some(ujson.Str(s))  => Right(s)
      case Some(ujson.Num(n))  => Right(if n.isWhole then n.toLong.toString else n.toString)
      case Some(ujson.Bool(b)) => Right(b.toString)
      // `null` is not an empty string. Reading it as one would quietly write an empty file
      // where the model meant to write something and lost it.
      case None | Some(ujson.Null) => Left(s"missing required string argument `$key`")
      case Some(other) =>
        Left(
          s"argument `$key` must be a string, but a ${kind(other)} was sent — " +
            "pass the value as text"
        )

  private def kind(v: ujson.Value): String = v match
    case _: ujson.Obj => "JSON object"
    case _: ujson.Arr => "JSON array"
    case _            => "non-string value"

  def optStr(v: ujson.Value, key: String): Option[String] =
    str(v, key).toOption.filter(_.nonEmpty)

  def optLong(v: ujson.Value, key: String): Option[Long] =
    scala.util.Try(v(key).num.toLong).toOption
