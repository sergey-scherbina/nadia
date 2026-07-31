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

### NAD-2 — ~~no stdin primitive for the REPL~~ **FIXED upstream, verified 2026-07-31**

`std.os.readLine` landed in `scalascript@862a19adb`, with exactly the surface the
report asked for:

```scala
extern def readLine(): Option[String]   // None at EOF
```

Verified here against a toolchain rebuilt to `fa335ba23` (**not** the stale one —
that mistake is NAD-1), all three branches distinguished:

| input | result |
|---|---|
| `printf 'sergiy\n' \|` | `Some("sergiy")` |
| `< /dev/null` (EOF) | `None` |
| `printf '\n' \|` (bare Enter) | `Some("")` — *not* EOF |
| `printf '  padded  \n' \|` | `Some("  padded  ")` — terminator stripped, spaces kept |

The third row is the whole reason the report argued for `Option`: an empty line and
a closed pipe are different events. (nadia's own approval gate conflated exactly
those two and auto-approved everything at EOF until it was found by driving the
REPL — same defect, our side.)

Scope note, where upstream corrected the report: it proposed implementing on
`int`, `js`, `jvm`, `native`. They measured instead — `std/os` does not resolve on
js or jvm at all (`envOrElse` fails there), so there was nothing to add `readLine`
to. It ships where `env` works: `int` and `native`/v2.

Reported as [scalascript#76](https://github.com/sergey-scherbina/scalascript/issues/76)
through their `user-report` form (`POLICY.md` P-3.10 makes it the front door of the
inbound queue); triage merged it with the board entry into one.

**NAD-5 is unblocked** — the ScalaScript REPL can now be written.

## P0

Status below is for the **ScalaScript** implementation. The Rust twin
(`rozum:crates/nadia`) has NAD-3, NAD-4, NAD-5 and the budget/loop-breaker half
of NAD-6 done and verified end-to-end on Qwen3.5-4B; port against it, and where
the two disagree, `SPEC.md` decides.

- [x] NAD-3 — the six tools + sandbox — `src/tools.ssc`. Path jail via
  `fs.resolveWithin`, `bash` via `process.exec` with a timeout.
- [x] NAD-4 — batch CLI — `src/nadia.ssc`. Verified end-to-end on Qwen3.5-4B:
  wrote the file, verified it with `bash`, exit 0.
- [x] NAD-5 — REPL on `os.readLine`, `/help` and `/tools`, EOF exits.
- NAD-6 — approval gates (§3.3) and budgets/loop-breaker (§3.4–3.5).
- NAD-7 — first matrix row (`SPEC.md` §5). **No launcher change needed**: the
  Rust twin established that `rozum launch` already exports `OPENAI_BASE_URL`
  and `ROZUM_GATEWAY_URL` to every agent it starts, so an agent that reads
  those and normalizes the `/v1` suffix is wired by the existing contract.
- NAD-10 — token streaming in the REPL (`SPEC.md` P1). Both sides have what
  they need: `std.agent` has `runAgentStream`, and the Rust twin now streams via
  `rozum-agent`'s `AgentObserver`. Port the rendering, not the mechanism.

### NAD-11 — `std.agent` cannot resume a transcript (**upstream: scalascript**)

`runAgent` always starts from `[system, user]`, and nothing public accepts an
existing conversation — `AgentResult.transcriptJson` comes back but has nowhere to
go. So each REPL turn here is independent: the agent has no memory of the previous
one, and the gateway re-prefills instead of reusing its KV prefix.

Exactly the gap the Rust twin had; fixed there by `run_agent_conversation`
(`rozum:crates/rozum-agent/src/agent.rs`), which is the same loop entered with a
supplied message list — strictly additive, `runAgent` delegates to it. The same
shape would work upstream. Not filed yet.

## P4 — containers and hosted providers

Shipped: the image, `deploy/k8s` · `deploy/aws` · `deploy/gcp`, and
`--provider local|openai|huggingface|bedrock|vertex` (`SPEC.md` §8,
`docs/deployment.md`).
What is left, and what was deliberately not done:

### NAD-12 — no SigV4; Bedrock needs a static API key

nadia authenticates to Bedrock with a bearer token (`AWS_BEARER_TOKEN_BEDROCK`),
which means the **task role is not what grants model access** on ECS or EKS. A
key has to exist as a secret, be rotated, and be mounted.

The native AWS answer is SigV4 against the ambient role, and Google's equivalent
is already implemented — `GoogleToken` asks the metadata server, so on GKE and
Cloud Run there is no key material at all. AWS deserves the same and does not
have it. Roughly 100 lines of canonical-request + HMAC chain; the reason it is
not here is that it cannot be verified without an account, and a signing
implementation that has never produced a valid signature is worse than an
honest gap.

### NAD-13 — the Rust implementation has no image

The image packages the Scala 3 implementation, because its runtime is a JVM and
one library. The Rust one is the reference and the one with subagents and the
HTTP control surface — the two things that would actually justify a long-running
container rather than a Job — but its build needs the whole rozum workspace, so
its Dockerfile belongs in that repository and not this one.

### NAD-14 — no live run against Hugging Face, Bedrock or Vertex

The URL construction is unit-tested against each vendor's documented shape. The
Hugging Face path goes further — a live request with a deliberately invalid token
reaches the router and comes back 401, which pins the URL, the bearer header and
the error reporting — but no account exists for any of the three, so no request
has been answered with a completion. No manifest here has been applied to a real
cluster. `docs/deployment.md` says so in the same words; do
not let this line disappear before a real run replaces it.

## P2+

- NAD-8 — subagents as actors over `std.actors` (`SPEC.md` §6).
- NAD-9 — Telegram front-end (`SPEC.md` §7).
