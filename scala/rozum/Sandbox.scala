package nadia.rozum

import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** The blast radius: every path a model supplies and every command it asks for goes
  * through here first.
  *
  * Two mechanisms, deliberately independent. The path jail decides *where* a tool may
  * touch; the exec confinement decides what a shell may do once it is running. Neither
  * subsumes the other — a command is free to write wherever the shell can reach, which is
  * exactly why `bash` gets its own containment rather than relying on argument checking.
  */
final case class Sandbox(
    root: Path,
    allowNet: Boolean = false,
    confine: Boolean = Sandbox.seatbeltAvailable,
    timeout: java.time.Duration = java.time.Duration.ofSeconds(120)
):

  /** Resolve a model-supplied path against the workspace, or refuse.
    *
    * Escapes are REFUSED, never clamped. A jail that strips `..` turns `../secrets` into
    * `<root>/secrets` and writes to a path nobody asked for — a silent wrong answer in
    * place of a loud one.
    *
    * The file need not exist yet (`write_file` creates it), so the canonical form is the
    * deepest existing ancestor plus the remaining tail, normalized. That closes both
    * escapes a naive `root.resolve(rel)` leaves open: `..` climbing out, and a symlinked
    * ancestor pointing elsewhere.
    */
  def resolve(rel: String): Either[String, Path] =
    if rel.trim.isEmpty then Left("path is empty")
    else
      val candidate = Paths.get(rel) match
        case p if p.isAbsolute => p
        case p                 => root.resolve(p)
      val normalized = realAncestor(candidate).normalize
      if normalized.startsWith(root) then Right(normalized)
      else Left(s"path $rel is outside the workspace ($root) — refused")

  /** Canonicalize as much of the path as exists, keep the rest lexically. */
  private def realAncestor(p: Path): Path =
    var existing = p
    var tail = List.empty[Path]
    while existing != null && !Files.exists(existing) do
      Option(existing.getFileName).foreach(n => tail = n :: tail)
      existing = existing.getParent
    val base = Option(existing).map(e => Try(e.toRealPath()).getOrElse(e)).getOrElse(p)
    tail.foldLeft(base)((acc, n) => acc.resolve(n))

  /** Run one shell command inside the workspace.
    *
    * The command is handed to `bash -lc` verbatim: a model needs pipes, redirection and
    * `&&` to be useful, and an allowlist of argv[0] does not survive `cargo test 2>&1 |
    * tail -20`. Containment is therefore the sandbox profile and the timeout — deciding
    * whether a shell string is safe by parsing it is a game you lose.
    */
  def exec(command: String, timeoutOverride: Option[java.time.Duration] = None): Exec =
    val limit = timeoutOverride.getOrElse(timeout)
    val argv =
      if confine then List("/usr/bin/sandbox-exec", "-p", seatbelt, "/bin/bash", "-lc", command)
      else List("/bin/bash", "-lc", command)
    val pb = ProcessBuilder(argv.asJava).directory(root.toFile)
    pb.redirectInput(ProcessBuilder.Redirect.from(java.io.File("/dev/null")))
    val proc = pb.start()
    val out = readAll(proc.getInputStream)
    val err = readAll(proc.getErrorStream)
    val finished = proc.waitFor(limit.toMillis, TimeUnit.MILLISECONDS)
    if !finished then proc.destroyForcibly()
    Exec(
      stdout = out.get,
      stderr = err.get,
      // A killed child has no exit value; 124 is what `timeout(1)` reports, so the model
      // sees the convention it was trained on rather than a nadia-ism.
      exitCode = if finished then proc.exitValue else 124,
      timedOut = !finished
    )

  /** Drain a stream on its own thread — a child that fills the pipe buffer blocks forever
    * if nobody is reading, and that deadlock outlives the timeout because the timeout is
    * waiting on the same process.
    */
  private def readAll(in: java.io.InputStream): java.util.concurrent.Future[String] =
    java.util.concurrent.CompletableFuture.supplyAsync(() => String(in.readAllBytes, "UTF-8"))

  /** Deny writes outside the workspace; deny network unless opted in. Reads stay open:
    * confining them aborts dyld before the child runs, and buys little — the threat here
    * is an agent mangling the machine, not exfiltration from a process that already has
    * the operator's shell.
    *
    * `/tmp` is deliberately absent. It is world-writable and shared, and on macOS it is a
    * symlink to `/private/tmp`, so allowing either allows both. The toolchain does not
    * need it: `$TMPDIR` is a private `/var/folders/…` path.
    */
  private def seatbelt: String =
    val home = sys.env.getOrElse("HOME", "/Users")
    val tmp = sys.env.getOrElse("TMPDIR", "/var/folders")
    val cargo = sys.env.getOrElse("CARGO_HOME", s"$home/.cargo")
    val net = if allowNet then "" else "(deny network*)"
    s"""(version 1)(allow default)(deny file-write*)
       |(allow file-write* (subpath "$root") (subpath "$cargo") (subpath "$tmp")
       |                   (subpath "/private/var/folders") (literal "/dev/null")
       |                   (literal "/dev/stdout") (literal "/dev/stderr"))
       |$net""".stripMargin

final case class Exec(stdout: String, stderr: String, exitCode: Int, timedOut: Boolean)

object Sandbox:
  /** Seatbelt is macOS-only; on anything else the confinement flag is a promise that
    * cannot be kept, and pretending otherwise is worse than admitting it.
    */
  val seatbeltAvailable: Boolean = System.getProperty("os.name").toLowerCase.contains("mac")

  def at(dir: String): Either[String, Sandbox] =
    val p = Paths.get(dir)
    if !Files.isDirectory(p) then Left(s"workspace root $dir is not a directory")
    else Right(Sandbox(p.toRealPath()))
