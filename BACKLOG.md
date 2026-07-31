# Backlog

Ordered by what blocks P0. Items marked **upstream** belong to a sibling repo
and are filed there; they are listed here because nadia is blocked on them.

## Upstream

### NAD-1 — ~~`std.process.exec` is unbound on the standard lane~~ **NOT A DEFECT — this report was wrong**

**Retracted 2026-07-31.** scalascript fixed this on 2026-07-30 in `f101312ed`
(*"implement `exec` on the native tier — std.process was missing from the DEFAULT
lane"*), whose commit message quotes the same `unbound global: exec` error.

The measurement below was taken against a toolchain built at `ff493301c` — i.e.
**before** that commit — with `SSC_NO_BUILD_CHECK=1` set, which silences the
launcher's own staleness warning: *"this toolchain was built from …, anything you
measure with it is the old code."* The warning existed for exactly this case and
was suppressed. Rebuild (`./install.sh --dev`) before re-testing anything here.

The original report is kept below rather than deleted: what it got wrong is more
useful than a clean file.

---

<details><summary>Original (incorrect) report</summary>

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

</details>

### NAD-2 — no stdin primitive for the REPL (**upstream: scalascript — FILED**)

`std.os` exposes `env` / `args` / `cwd` but nothing that reads a line from stdin,
so the interactive mode has no input source. Verified 2026-07-31 against
`scalascript@983b520ce`: the only occurrence of `readLine` in the tree is a
comment in `runtime/std/free.ssc:97`.

Reported upstream as **[scalascript#76](https://github.com/sergey-scherbina/scalascript/issues/76)**
via their `user-report` form, which their `POLICY.md` P-3.10 makes the front door
of the inbound queue. The form asks reporters *not* to diagnose, so the proposed
surface (`extern def readLine(): Option[String]`, `None` at EOF) and the
implementation order live in the `scalascript:BUGS.md` entry
`std-has-no-stdin-primitive` instead, which now points at #76; their triage owns
which of the two survives.

**Do not implement it here** — this is theirs to land, and a local workaround
would diverge from whatever they ship.

No workaround exists on the standard lane: a ```scala passthrough does not give
JVM interop there (`unbound global: java`), and reading stdin through
`exec` inherits no terminal.

## P0

Status below is for the **ScalaScript** implementation. The Rust twin
(`rozum:crates/nadia`) has NAD-3, NAD-4, NAD-5 and the budget/loop-breaker half
of NAD-6 done and verified end-to-end on Qwen3.5-4B; port against it, and where
the two disagree, `SPEC.md` decides.

- NAD-3 — the six tools (`SPEC.md` §2) with the sandbox of §3.1–3.2.
  Unblocked: `std.process.exec` works on the standard lane since `f101312ed`
  (rebuild the toolchain first — see NAD-1).
- NAD-4 — batch CLI (`SPEC.md` §4.1), exit codes 0/1/2.
- NAD-5 — REPL (`SPEC.md` §4.2), slash commands. Blocked on NAD-2.
- NAD-6 — approval gates (§3.3) and budgets/loop-breaker (§3.4–3.5).
- NAD-7 — first matrix row (`SPEC.md` §5). **No launcher change needed**: the
  Rust twin established that `rozum launch` already exports `OPENAI_BASE_URL`
  and `ROZUM_GATEWAY_URL` to every agent it starts, so an agent that reads
  those and normalizes the `/v1` suffix is wired by the existing contract.
- NAD-10 — token streaming in the REPL (`SPEC.md` P1). `std.agent` has
  `runAgentStream`; the Rust twin currently renders per turn, so this is the
  one place ScalaScript is ahead.

## P2+

- NAD-8 — subagents as actors over `std.actors` (`SPEC.md` §6).
- NAD-9 — Telegram front-end (`SPEC.md` §7).
