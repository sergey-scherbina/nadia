# Backlog

Ordered by what blocks P0. Items marked **upstream** belong to a sibling repo
and are filed there; they are listed here because nadia is blocked on them.

## Blocking P0

### NAD-1 — `std.process.exec` is unbound on the standard lane (**upstream: scalascript**)

`bash` is one of the six tools and cannot be written without it.

Reproduced 2026-07-30 against `scalascript@bec79618c`, toolchain `bin/ssc`:

```
[exec, ProcessOptions](std/process.ssc)
val r = exec("echo", List("hi"), ProcessOptions(cwd = Some("/tmp"), timeout = Some(5000)))
→ ssc: unbound global: exec
```

The intrinsic is **implemented** — `runtime/std/os-plugin/src/main/scala/
scalascript/compiler/plugin/os/OsIntrinsics.scala:128`, `QualifiedName("exec")`,
with full `cwd` / `env` / `timeout` / `inheritEnv` handling — but it is
registered in the `std.os` plugin namespace while `process.ssc` declares
`package: std.process`, so the name never binds for an importer.

Everything else nadia needs resolves and runs on that lane: `std.agent`
(`agentTool`, `objectSchema`, `RunOptions`), `std.json`, `std.fs`
(`resolveWithin` correctly canonicalizes `a/../b.txt` → `/tmp/b.txt`),
`std.os` (`cwd`, `args`, `env`).

Not a lane-choice problem: `ssc-tools run-jvm` is the deprecated v1 codegen
lane (`docs/targets.md` declares it non-conforming and explicitly not to be
fixed), and it fails on far more than this.

Fix: expose the existing intrinsic under `std.process`, plus a conformance test.
Workaround if the fix is deferred: a ```scala passthrough block wrapping
`java.lang.ProcessBuilder`, confined to the JVM target — acceptable to unblock,
not acceptable to ship.

### NAD-2 — no stdin primitive for the REPL (**upstream: scalascript**)

`std.os` exposes `env` / `args` / `cwd` but nothing that reads a line from
stdin, so the interactive mode has no input source. Same shape as NAD-1: add a
`readLine` extern alongside the existing `os` intrinsics.

Workaround: ```scala passthrough (`scala.io.StdIn.readLine`) on the JVM target.

## P0

- NAD-3 — the six tools (`SPEC.md` §2) with the sandbox of §3.1–3.2.
- NAD-4 — batch CLI (`SPEC.md` §4.1), exit codes 0/1/2.
- NAD-5 — REPL (`SPEC.md` §4.2), streaming, slash commands. Blocked on NAD-2.
- NAD-6 — approval gates (§3.3) and budgets/loop-breaker (§3.4–3.5).
- NAD-7 — `rozum launch nadia` branch + first matrix row (`SPEC.md` §5).
  Cross-repo: the launch branch is a rozum change.

## P2+

- NAD-8 — subagents as actors over `std.actors` (`SPEC.md` §6).
- NAD-9 — Telegram front-end (`SPEC.md` §7).
