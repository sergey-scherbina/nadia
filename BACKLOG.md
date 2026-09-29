# Backlog

## Test harness

- [x] **ssc-test-harness — SUPERSEDED 2026-08-13 and partly stale when written.** The entry said the
  ScalaScript side had no checks and that `tools.ssc` / `nadia.ssc` had "no equivalent at all";
  `src/tools-check.ssc` and `src/fsx-check.ssc` exist and pass. What was true — that three
  hand-written suites kept one contract in step by discipline — is now answered by
  `contract/gate-cases.json`: the pure rules as data, read by all three implementations. It earned
  itself on the first run by finding a hole three hand-written suites had agreed was fine (a task's
  markdown backtick stuck to the last argument, so the arity rule never fired on the matrix's own
  rpn prompt).

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

The URL construction is unit-tested against each vendor's documented shape. Two
of the three Hugging Face paths are now real: a weights repository
(`mlx-community/Qwen3.5-4B-MLX-4bit`) runs a task end-to-end through a local
gateway, and a router request with a deliberately invalid token comes back 401,
pinning the URL, the bearer header and the error reporting. What is still
unproven is a *completion* from a hosted provider — no account exists for any of
the three. No manifest here has been applied to a real
cluster. `docs/deployment.md` says so in the same words; do
not let this line disappear before a real run replaces it.

## P2+

- NAD-8 — subagents as actors over `std.actors` (`SPEC.md` §6).
- NAD-9 — Telegram front-end (`SPEC.md` §7).

### NAD-14 — the workspace UI on okay, and what `serve` owes it (rozum spec, 2026-09-28)

`rozum:docs/specs/okay-workspace-ui.md` (operator direction, in-session): ONE remote workspace over
every project, room, agent and chat, written once as a pure `State`/`view`/`update` over okay-ui and
drawn by any host — terminal, browser, Telegram, a native app. The program and its service land HERE,
in `ui/` as its own sbt build on okay (`dev.okay` `0.2.0-SNAPSHOT`, `sbt publishLocal` from `../okay`),
NOT in `scala/` — that build is the one-dependency statement and stays so. Stages S0–S6 and their gates
are on rozum's SPRINT under `okay-workspace-ui`.

What the workspace needs from nadia that `serve` does not have (Rust twin, `rozum:crates/nadia/src/serve.rs`):

- an **event stream** per agent (steps, tool calls, tokens) instead of a polled `Status`;
- an **approval hook** — a spawned agent runs on auto-approve today (`ControlGate(LoopBreaker(tools))`,
  no `Approver`); the workspace shows a tool approval as an okay `Form` (the MCP elicitation circle) and
  answers it from any host, which needs `serve` to ask and wait;
- a **persisted transcript**, so an agent's conversation is a `Chat` one can switch back to after a
  restart (`records.rs` keeps metadata and the result only).

Open with the operator whether this is in scope of the UI work or a separate lane; S5 (approvals) and
the two-way agent chat in S4 are blocked on it either way. The Telegram front-end (NAD-9, §7) is
resolved by the same spec: the bot is the workspace's Telegram host, eventually a new bot on okay, and
nothing agent-side is Telegram-specific — exactly §7's promise, kept by a different bot.

**Answer, 2026-09-29 (the agents session; split agreed with the operator: this session owns the
agent model and the control protocol, the UI session owns `ui/` as its consumer).** The number
collides with P4's NAD-14 above; this one is *the workspace one*.

The protocol is okay's, not `serve`'s: `okay.agent.Fleet` in `okay-agent` (okay master
`654a4f98b`, spec `okay:specs/agent-fleet.md`), and the UI consumes it **as a log, not as calls**
— the same log the agents are restored from, so "what happened" and "what is happening" are one
fold. The Rust `serve` is not extended.

*Module and types* (`okay.agent`): `Spec(task, workspace, budget: Budget(steps, wallMs), parent?,
model?)` · `AgentId` (opaque Long, `.n`) · `Phase` = Running | Paused | Stopping | Done | Failed |
Killed | Interrupted · `Status(id, parent, task, workspace, phase, step, lastTool, elapsedMs,
children, result, report: Option[Json])` · `Fleet.Control` = Tell(message) | Pause | Resume | Stop |
Kill · `Outcome(text, report, phase)` · `Runner` (what runs ONE agent: nadia's six tools + gate; the
UI never sees it) · `Fleet.Ctx` (what a runner asks between tool calls) · `Fleet.delegate(fleet,
parent)` (the parent's seventh tool; a child's steps debit the parent).

*In-process API* (one JVM — the nadia service): `Fleet.open(store, runner): Fleet ! Async`;
`spawn(spec): AgentId ! Async`; `send(id, Control): Boolean ! Async`; `status(id)`, `all`,
`transcript(id): Seq[Turn]`, `stepsLeft(id)`; `await(id)`; `close()`; `restore()`.

*The record — what a WorldFeed folds instead of polling.* Topic `agents` in the nadia state store
(`NADIA_STATE`, default `~/.nadia/app`, an okay-persist `FileStore`), one JSON object per record,
keyed by the agent id, appended BEFORE the fleet applies it:

| kind | fields |
|---|---|
| `spawned` | `id, task, workspace, steps, wallMs, parent?, model?, at` |
| `phased` | `id, phase, at` |
| `stepped` | `id, step, tool, at` |
| `turned` | `id, turn: {t: system\|user\|assistant\|result\|summary\|patch, text?, calls?: [{id,name,args}], call?, content?, covers?, patch?}` |
| `finished` | `id, phase, text, report?, at` |

`Status` is the fold of those (the rules are `Fleet.restore`'s: a live phase after a process end is
`Interrupted`); the transcript is the `turned` records — so an agent's conversation IS a `Chat` one
switches back to after a restart, with nothing else to persist.

*Subscribing.* `okay.persist.Streams.tail(topic, partition = 0, from, chunk)` is a
`Source[Chunk[Record]]` that follows the log as it grows: the WorldFeed drains it and folds; no
request to the agent, ever. In one process that is all. Across processes — `ui/` is its own
build and process — the nadia service serves its store with `okay.persist.Wire.Server` and the UI
opens it as a `RemoteStore` (both exist, JVM); a second process may TAIL a `FileStore` read-only
(that arrangement is the one FileStore's header describes), but must not write to it, which is why
the control plane below goes over the wire too.

*Still to land, in okay, each its own lane* (filed under P6 below as NAD-19..21; the UI's S4/S5 wait
on them):

- **`fleet-events`** — `Fleet.events(topic): Source[Fleet.Event]` (the typed decoder of the table
  above, so a feed folds values, not JSON) and in-process `fleet.events`.
- **`fleet-commands`** — the control plane as a topic `commands` the service folds: `spawn{task,
  workspace, steps, wallMs, parent?, model?, by}`, `tell{id, message, by}`, `pause|resume|stop|kill
  {id, by}`, `approve{id, seq, yes, by}`; `by` is the principal, checked by the service against the
  roster (`okay.security.Roster`, landed `0c21077e2`: owner, `operator` scoped to a project,
  `viewer`). A command refused is a record too (`refused{seq, why}`), so the UI shows why.
- **`fleet-approvals`** — `Ctx.ask(step, call): Boolean ! Async`: the runner asks before
  `write_file`/`edit_file`/`bash` (nadia `SPEC.md` §3.3, the REPL's `y/n/a`), the fleet appends
  `asked{id, seq, tool, args, at}` and parks until an `approve`; `Status` gains `asking:
  Option[Ask]`; `Control.Approve(seq, yes)`. Auto-approve is an approver that answers yes — the batch
  runner's default, never the workspace's.

*What the UI can already use today:* `okay.llm.Models` (landed `2f20f5a03`) for a Models screen —
`Catalog`/`Residency` with the rozum adapter's resident mark, `load`/`unload` over the gateway's
control routes; and `okay.telegram.Chats(…, everyMs)` + `okay.telegram.Command` (landed `e3fd797f9`)
for its Telegram host — the coalesced edits a live agent card needs, and a command menu from the
screen table.

*What this settles in this repository:* `docs/specs/app.md`'s screens are the workspace's
(`rozum:docs/specs/okay-workspace-ui.md`), drawn from `ui/`; `app/` is the SERVICE — the fleet,
nadia's runner (six tools, sandbox, prompt, gate over okay-agent), the roster, the models seam, and
the store served over the wire — and carries no screen of its own.

## P6 — the okay implementation: upstream in `../okay`

Each is a spec in `okay:specs/` first, by that repository's claim/worktree protocol, then code
there; `app/` here consumes it by `ProjectRef`. Written 2026-09-29 from `docs/specs/app.md`.

### NAD-15 — agent hierarchy in `okay-agent` (**upstream: okay**)

**Spec landed** in okay as `ae49fb7ae`: `okay:specs/agent-fleet.md`; implementation is okay's lane `agent-fleet`.

`okay-agent` has the loop, tools, context and durable journals and **no subagents**: nothing
spawns, delegates, or reports a child's status. Needed: `Fleet` over `okay-actor` — an agent as a
supervised actor with a budget, `spawn/tell/status/pause/resume/stop/kill` as its messages
(`SPEC.md` §6), `delegate` as a `Toolbox` tool whose child's steps come out of the parent's
budget, `Status` as a value, and sessions (`Turn` history) on an `okay-persist` topic so a run
resumes after a restart. Rejected: keeping the supervisor in nadia — it has no nadia in it.

### NAD-16 — the models seam in `okay-llm` (**upstream: okay**)

**Spec landed** in okay as `ae49fb7ae`: `okay:specs/llm-models.md`; implementation is okay's lane `llm-models`.

`SPEC.md` §10: `Models.Catalog` / `Residency` / `Store` as capabilities a provider may lack (a
missing one is a missing method, not a failing one); adapters `openAi`, `anthropic` (catalog),
`ollama`, `rozum` (all three; rozum's `/v1/models` + `/control/status|switch|unload|reload`);
model-id equality across `org:repo` / `org/repo` / `hf:org/repo`. `okay-llm` today has no model
listing at all.

### NAD-17 — channel identity and roster in `okay-security` (**upstream: okay**)

**Spec landed** in okay as `ae49fb7ae`: `okay:specs/identity-roster.md`; implementation is okay's lane `identity-roster`.

`okay-security` has `Principal`, `Policy`, `Capability` and no store: no users, no roles, no
binding of a channel address (a Telegram user id, later a web session) to a principal. okay-chat
wrote this as app code (`okaychat.Identity`); lift the shape: `Roster` on a topic, `bind(channel,
address) → Principal`, roles as facts the `Policy` reads.

### NAD-18 — a live card in `okay-telegram` (**upstream: okay**)

**Spec landed** in okay as `ae49fb7ae`: `okay:specs/telegram-live.md`; implementation is okay's lane `telegram-live`.

`Chats.perform` edits a message per `Act`; a running agent changes status faster than the Bot
API allows edits. Needed: a throttle per message (at most one edit per N ms, last write wins),
and `setCommands` derived from a screen table so the command menu and the screens cannot drift.

### NAD-19 — `fleet-events` (**upstream: okay**)

`Fleet.events(topic): Source[Fleet.Event]`, the typed decoder of the `agents` record, and in-process
`fleet.events`; what the workspace's WorldFeed folds (NAD-14 answer above).

### NAD-20 — `fleet-commands` (**upstream: okay**)

The control plane as a `commands` topic the service folds — spawn/tell/pause/resume/stop/kill/approve
with a `by` principal checked against the roster; a refusal is a record.

### NAD-21 — `fleet-approvals` (**upstream: okay**)

`Ctx.ask(step, call)` parks the runner until an `approve`; `asked` records; `Status.asking`;
`Control.Approve`. The REPL's `y/n/a` (SPEC §3.3) answered from any host.
