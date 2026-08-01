package agent

import java.io.{BufferedReader, BufferedWriter, InputStreamReader, OutputStreamWriter}
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}
import scala.jdk.CollectionConverters.*

/** An MCP client over the stdio transport, on the JDK and nothing else.
  *
  * The other two implementations get this from a tier below them — rmcp in Rust,
  * `std.mcp.client` in ScalaScript. This one has no tier below it, which is the whole point
  * of the Scala 3 implementation (`SPEC.md` §0): it answers *how much of an agent is the
  * framework*, and the answer only means anything if the framework is actually here. So the
  * protocol is written out: newline-delimited JSON-RPC 2.0 on the child's stdin and stdout,
  * `initialize` → `notifications/initialized` → `tools/list` → `tools/call`. About 120 lines,
  * which is itself part of the finding.
  *
  * Two things are deliberate rather than incidental:
  *
  *   - **A reader thread and a queue, not a blocking read.** `InputStream.read` on a child
  *     process cannot be given a deadline, so a server that accepts the connection and then
  *     says nothing would wedge the turn forever. The thread parks on the read; the caller
  *     parks on `poll(timeout)` and gets an error it can report.
  *   - **stderr is drained.** A child whose stderr nobody reads blocks on a full pipe once it
  *     has logged a few kilobytes, and the symptom is a server that answers two calls and then
  *     hangs — which reads as a protocol bug and is not one.
  */
object McpClient:

  /** What a server advertised. `schema` goes to the model verbatim: the server owns it. */
  final case class ToolDescriptor(name: String, description: String, schema: ujson.Value)

  private val ProtocolVersion = "2025-06-18"

  /** Spawn `command args` and complete the MCP handshake, or say why not. */
  def spawn(
      command: String,
      args: List[String],
      env: Map[String, String],
      timeoutMs: Long = 20000
  ): Either[String, McpClient] =
    try
      val pb = new ProcessBuilder((command :: args).asJava)
      pb.environment().putAll(env.asJava)
      val proc = pb.start()
      val client = new McpClient(proc, timeoutMs)
      client.handshake().map(_ => client)
    catch case e: Throwable => Left(s"spawn `$command`: ${e.getMessage}")

/** One connected server. Not thread-safe: one agent loop, one call at a time. */
final class McpClient private[agent] (proc: Process, timeoutMs: Long):
  import McpClient.*

  private val out = new BufferedWriter(new OutputStreamWriter(proc.getOutputStream, "UTF-8"))
  private val incoming = new LinkedBlockingQueue[String]()
  private var nextId = 0

  thread("mcp-stdout"):
    val in = new BufferedReader(new InputStreamReader(proc.getInputStream, "UTF-8"))
    var line = in.readLine()
    while line != null do
      incoming.put(line)
      line = in.readLine()

  // Drained and discarded: a server logging to stderr must not block on a full pipe, and its
  // log is not ours to interleave with the agent's output.
  thread("mcp-stderr"):
    val in = new BufferedReader(new InputStreamReader(proc.getErrorStream, "UTF-8"))
    while in.readLine() != null do ()

  private def thread(name: String)(body: => Unit): Unit =
    val t = new Thread(() => try body catch case _: Throwable => ())
    t.setName(name)
    t.setDaemon(true)
    t.start()

  private def send(msg: ujson.Value): Either[String, Unit] =
    try
      out.write(ujson.write(msg))
      out.write("\n")
      out.flush()
      Right(())
    catch case e: Throwable => Left(s"write: ${e.getMessage}")

  /** Send a request and wait for the response with THIS id. Notifications and any
    * server-initiated traffic that arrives meanwhile are skipped, not treated as the answer. */
  private def request(method: String, params: ujson.Value): Either[String, ujson.Value] =
    nextId += 1
    val id = nextId
    val msg = ujson.Obj("jsonrpc" -> "2.0", "id" -> id, "method" -> method, "params" -> params)
    send(msg).flatMap { _ =>
      val deadline = System.currentTimeMillis() + timeoutMs
      def await(): Either[String, ujson.Value] =
        val left = deadline - System.currentTimeMillis()
        if left <= 0 then Left(s"$method: no response in ${timeoutMs}ms")
        else
          incoming.poll(left, TimeUnit.MILLISECONDS) match
            case null => Left(s"$method: no response in ${timeoutMs}ms")
            case line =>
              scala.util.Try(ujson.read(line)).toOption match
                case None => await() // not JSON: a stray line, keep waiting for ours
                case Some(v) =>
                  val isOurs = v.obj.get("id").exists(_.numOpt.contains(id.toDouble))
                  if !isOurs then await()
                  else
                    v.obj.get("error") match
                      case Some(e) => Left(s"$method: ${errorText(e)}")
                      case None    => Right(v.obj.getOrElse("result", ujson.Obj()))
      await()
    }

  private def errorText(e: ujson.Value): String =
    e.obj.get("message").flatMap(_.strOpt).getOrElse(ujson.write(e))

  private[agent] def handshake(): Either[String, Unit] =
    val params = ujson.Obj(
      "protocolVersion" -> ProtocolVersion,
      "capabilities" -> ujson.Obj(),
      "clientInfo" -> ujson.Obj("name" -> "nadia", "version" -> "0.1.0")
    )
    request("initialize", params).flatMap { _ =>
      // The notification the protocol requires after a successful initialize. It has no id
      // and no reply; a server that never receives it may refuse everything after.
      send(ujson.Obj("jsonrpc" -> "2.0", "method" -> "notifications/initialized", "params" -> ujson.Obj()))
    }

  /** Every tool the server advertises, following `nextCursor` to the end: a server with more
    * tools than one page would otherwise contribute a silently truncated set. */
  def listTools(): Either[String, List[ToolDescriptor]] =
    def page(cursor: Option[String], acc: List[ToolDescriptor]): Either[String, List[ToolDescriptor]] =
      val params = cursor.map(c => ujson.Obj("cursor" -> c)).getOrElse(ujson.Obj())
      request("tools/list", params).flatMap { result =>
        val tools = result.obj.get("tools").flatMap(_.arrOpt).getOrElse(ujson.Arr().arr).toList.map { t =>
          ToolDescriptor(
            t.obj.get("name").flatMap(_.strOpt).getOrElse(""),
            t.obj.get("description").flatMap(_.strOpt).getOrElse(""),
            t.obj.getOrElse("inputSchema", ujson.Obj("type" -> "object"))
          )
        }
        result.obj.get("nextCursor").flatMap(_.strOpt) match
          case Some(next) if next.nonEmpty => page(Some(next), acc ++ tools)
          case _                           => Right(acc ++ tools)
      }
    page(None, Nil)

  /** Call a tool. A server-reported tool error comes back as `Left`, so it reaches the model
    * as the next prompt exactly like a native tool's error — not as a dead run. */
  def callTool(name: String, args: ujson.Value): Either[String, ujson.Value] =
    request("tools/call", ujson.Obj("name" -> name, "arguments" -> args)).flatMap { result =>
      val text = result.obj
        .get("content")
        .flatMap(_.arrOpt)
        .map(_.toList.flatMap(c => c.obj.get("text").flatMap(_.strOpt)).mkString("\n"))
        .getOrElse("")
      val isError = result.obj.get("isError").flatMap(_.boolOpt).getOrElse(false)
      if isError then Left(if text.nonEmpty then text else s"$name reported an error")
      else
        // Structured output when the server sent it; otherwise the text, so a plain-text
        // tool still yields something the model can read.
        Right(result.obj.get("structuredContent").getOrElse(ujson.Str(text)))
    }

  def close(): Unit =
    try out.close()
    catch case _: Throwable => ()
    proc.destroy()

  /** Has the child gone? Its tools then answer with an error the model can act on, rather
    * than the run dying (`SPEC.md` §2.1). */
  def alive: Boolean = proc.isAlive
