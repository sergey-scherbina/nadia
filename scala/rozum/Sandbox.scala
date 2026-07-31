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
    confinement: Confinement = Confinement.select(requested = true),
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
    val argv = confinement match
      case Confinement.Seatbelt => List("/usr/bin/sandbox-exec", "-p", seatbelt, Sandbox.shell, "-lc", command)
      // Under a container runtime the jail is the image, its mounts and its network — set
      // before this process existed and not adjustable from inside it. There is nothing to
      // wrap the command in, which is the point: see `Confinement`.
      case Confinement.Runtime | Confinement.Open => List(Sandbox.shell, "-lc", command)
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

  /** `bash -lc` is the contract with the model — it is what the tool description promises,
    * and what makes pipes and `&&` work. A base image without bash is a misconfiguration,
    * but degrading to `sh` beats refusing to start.
    */
  val shell: String =
    List("/bin/bash", "/usr/bin/bash", "/bin/sh").find(p => Files.isExecutable(Paths.get(p))).getOrElse("/bin/sh")

  def at(dir: String): Either[String, Sandbox] =
    val p = Paths.get(dir)
    if !Files.isDirectory(p) then Left(s"workspace root $dir is not a directory")
    else Right(Sandbox(p.toRealPath()))

/** What is actually containing `bash` — as opposed to what was asked for.
  *
  * This started as a boolean, which was honest while the only deployment was a developer's
  * Mac. It stops being honest the moment the same binary runs on Linux, where the seatbelt
  * profile does not exist: a flag called `confine` that silently does nothing is worse than
  * no flag, because an operator reads it as a guarantee.
  *
  * So the value names the mechanism rather than the intent, and every front-end prints it.
  * There are three, and they are genuinely different promises:
  *
  *   - `Seatbelt` — macOS `sandbox-exec`. Writes outside the workspace are denied by the
  *     kernel, and so is the network unless `--allow-net`. The agent enforces this itself.
  *   - `Runtime` — inside a container. The jail is the image, its mounts and its network
  *     namespace, all fixed before this process started. Usually *stronger* than the
  *     seatbelt, and entirely out of the agent's hands: `--allow-net` cannot switch on a
  *     network the runtime did not give it, and cannot switch off one it did.
  *   - `Open` — a bare Linux process, or `--no-confine`. The workspace is the working
  *     directory and commands have a timeout. Nothing else is true, and the front-ends say
  *     so out loud rather than leaving it to be inferred.
  */
enum Confinement:
  case Seatbelt, Runtime, Open

  /** One line, for the operator, at the top of every session. */
  def describe(allowNet: Boolean): String = this match
    case Seatbelt =>
      val net = if allowNet then "network allowed" else "network denied"
      s"confined by sandbox-exec — writes jailed to the workspace, $net"
    case Runtime =>
      "confined by the container runtime — the image and its network are the jail; " +
        "--allow-net has no effect in here"
    case Open =>
      "NOT confined — `bash` has this user's full access; only the workspace cwd and a timeout apply"

object Confinement:

  /** Seatbelt is macOS-only, and the binary has to actually be there. */
  val seatbeltAvailable: Boolean =
    System.getProperty("os.name").toLowerCase.contains("mac") &&
      Files.isExecutable(Paths.get("/usr/bin/sandbox-exec"))

  /** Several signals, because no single one covers Docker, containerd and Kubernetes.
    *
    * Every one of them is a *positive* marker that something put us in a container. That
    * asymmetry is deliberate: a false negative downgrades the banner to `Open`, which
    * understates the containment and costs nothing but a warning, while a false positive
    * would claim a jail that is not there. Guessing from, say, what PID 1 looks like would
    * fail in the second direction, so it is not done. `NADIA_IN_CONTAINER` — which this
    * project's own image sets — is the escape hatch for a runtime none of these recognise.
    */
  val inContainer: Boolean =
    sys.env.get("NADIA_IN_CONTAINER").exists(v => v == "1" || v.equalsIgnoreCase("true")) ||
      sys.env.contains("KUBERNETES_SERVICE_HOST") ||
      Files.exists(Paths.get("/.dockerenv")) ||
      Files.exists(Paths.get("/run/.containerenv")) || // podman
      Try(Files.readString(Paths.get("/proc/1/cgroup"))).toOption
        .exists(s => s.contains("docker") || s.contains("kubepods") || s.contains("containerd"))

  def select(requested: Boolean): Confinement =
    if !requested then Confinement.Open
    else if seatbeltAvailable then Confinement.Seatbelt
    else if inContainer then Confinement.Runtime
    else Confinement.Open
