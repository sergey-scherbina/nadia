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
  def str(v: ujson.Value, key: String): Either[String, String] =
    scala.util.Try(v(key).str).toOption.toRight(s"missing required string argument `$key`")

  def optStr(v: ujson.Value, key: String): Option[String] =
    scala.util.Try(v(key).str).toOption.filter(_.nonEmpty)

  def optLong(v: ujson.Value, key: String): Option[Long] =
    scala.util.Try(v(key).num.toLong).toOption
