# nadia — spec

An LLM coding agent driving a local model through the rozum gateway. Two
front-ends over one loop: a headless **batch CLI** (a drop-in row in rozum's
agentic matrix, alongside `claude` / `codex` / `opencode`) and an interactive
**REPL chat**. Plus subagents as actors and a Telegram front-end, in the Rust
implementation.

Status: **P0 shipped in three implementations** (§0). This spec was written
before any of them and remains the contract they are reviewed against
(`/spec-dev`) — where two disagree, this file decides.

## 0. Three implementations, one spec

nadia exists three times on purpose, and the three differ in exactly one axis: how much
sits underneath them.

| | Where | What is underneath | Role |
|---|---|---|---|
| **Rust** | `rozum:crates/nadia` | `rozum-agent` (loop, budgets), `rozum-gateway` (client) | The executable reference. Shipped first — Rust already had the process and stdin primitives the tools need. |
| **ScalaScript** | `src/*.ssc` | `std.agent` (loop, streaming, retry, schemas) | Dogfoods the language. The thinnest of the three: the SDK carries Contracts 1–3. |
| **Scala 3** | `scala/*.scala` | nothing but the JDK and one JSON library | Carries all three contracts itself. |

The Scala 3 one exists to answer a question the other two cannot: *how much of an agent is
the framework?* It has no SDK under it — the model client, the loop and the tools are all
in the file tree — and it lands in about 700 lines including its tests. That number is the
useful output.

All three implement *this* spec — same six tools, same safety model, same CLI surface.
A divergence between them is a bug in one of them, and this file decides which.

## 1. Position in the stack

nadia is a *thin app leaf*. It owns the domain — tools, prompts, UX, safety
policy — and nothing else:

```
nadia (THIS)      tools (handler+schema) · prompts · CLI/REPL · approval · subagents
   │ uses
std.agent         ModelClient · AgentLoop · ToolRegistry · SchemaDerivation ·
   │ consumes      EndpointPool/retry · Transcript · MCP bridge      [scalascript]
rozum gateway     stateless: POST /v1/chat/completions (tools, SSE) → tool_calls | text
   │ below SPI     + per-family tool rendering/parsing + constrained decoding
local MLX         the model
```

The three contracts are specified in `rozum:docs/specs/integration.md`; the
scalascript-side SDK that implements them is `scalascript:specs/agent-sdk.md`
(`runtime/std/agent.ssc`, P0–P2 shipped). nadia does not re-derive any of it.

### 1.1 Non-goals (explicitly out of scope)

- **Per-family tool formats.** nadia emits neutral OpenAI-form JSON
  (`name` / `description` / `parameters`). Rendering that into the syntax a
  model family was trained on (Qwen `<tool_call>`, GLM `<arg_key>`, DeepSeek
  `<｜tool▁sep｜>`, harmony) and parsing the reply back is the **gateway's**
  job — `rozum:crates/rozum-core/src/serving.rs`, plus constrained decoding for
  argument validity. A second parser here would be a second source of truth and
  the exact class of bug where a gateway defect reads as a model defect.
- **Model hosting, residency, admission.** rozum owns it.
- **The agent loop itself.** `std.agent.runAgent` / `runAgentStream` own it.

## 2. Tool set

Six tools. The bar for adding a seventh: it must enable a task class that is
impossible — not merely more convenient — with these six.

| Tool | Parameters | Result |
|---|---|---|
| `read_file` | `path`, `start_line?`, `end_line?` | file text (line-numbered) |
| `write_file` | `path`, `content` | `{written, bytes}` |
| `edit_file` | `path`, `old_string`, `new_string` | `{replaced: 1}` |
| `list_dir` | `path` | entries with `dir` flag |
| `grep` | `pattern`, `path?`, `glob?` | matching `path:line:text` |
| `bash` | `command`, `timeout_ms?` | `{stdout, stderr, exit_code}` |

Rationale for the shape (`rozum:docs/specs/integration.md` Contract 3):
high-level, atomic, deterministic; strict schemas with `required` and no
free-form escape hatches; every error a sentence the model can act on.

`edit_file` replaces **exactly one** occurrence. Zero matches or more than one
is a `ToolError` naming the count — never a silent partial edit. This is what
keeps a small model from rewriting a whole file to change one line.

Schema budget is a first-class constraint: six tools ≈ 1.5–2k tokens of schema
per request. Measured baseline for comparison — Claude Code ships ~33 tools /
~4.9k schema tokens; `rozum launch --lean` cuts that 84%. For a 4B model every
extra tool dilutes selection.

### 2.1 Result formatting

Tool results are JSON. `read_file` returns text with line numbers because
`edit_file` needs the model to quote `old_string` exactly, and line numbers make
the model's own reads verifiable in the transcript.

Large outputs are truncated at a byte budget with an explicit
`"truncated": true` marker plus the byte count — never silently.

## 3. Safety model

No agent loop performs a side effect of its own; every effect goes through a
nadia handler, and every handler validates first. That is what makes it safe to
point a small, unpredictable local model at a filesystem — and it holds in all
three implementations, whether the loop comes from an SDK or sits in the same
file tree.

1. **Path jail.** Every `path` argument resolves through
   `std.fs.resolveWithin(root, rel)`, which canonicalizes and returns `None` for
   anything escaping the workspace root. `..`, symlinks, and absolute paths
   outside the root are refused with a `ToolError`, not clamped.
2. **Exec confinement.** `bash` runs with `cwd` = workspace root, a hard
   `timeout` (default 120s), and — on macOS — under `sandbox-exec` with a
   profile confining writes to the root. Network is **off** by default
   (`--allow-net` opts in).
3. **Approval gates.** In REPL mode, `write_file` / `edit_file` / `bash` prompt
   before executing (`y` / `n` / `a` = always-for-this-session). In batch mode
   inside a scratch workspace they auto-allow: the matrix harness gives each
   task its own temp dir.
4. **Budgets.** `maxSteps` (default 24 batch / 12 per REPL turn), wall-clock
   ceiling, per-call `max_tokens`. Exhaustion returns a partial result — the
   loop never runs away.
5. **Loop breaker.** Beyond budgets, halt on the known repetition signatures
   (`rozum:docs/specs/` loop-breaker work): the same `(tool, arguments)` pair
   ≥4 times in the last 12 calls, and edit-churn on one file. A 4B model that
   has lost the thread does not stop on its own.

## 4. Modes

### 4.1 Batch (headless)

```
nadia run "<task prompt>" [--workspace DIR] [--model ID] [--gateway URL]
                          [--max-steps N] [--allow-net] [--json]
```

Runs the loop to a final answer, prints it, exits `0` on `Done`, `1` on budget
exhaustion, `2` on transport/gateway failure — matching what the harness already
distinguishes. `--json` emits the full `AgentResult` (text, operations,
transcript, stop) for post-hoc analysis.

### 4.2 Interactive (REPL)

```
nadia            # or: nadia chat
```

Line-based, streaming token output, compact one-line rendering per tool call
(`⏺ edit_file src/main.rs → replaced 1`). Each user turn runs a full agent
sub-loop; the transcript persists across turns in one process.

Slash commands: `/help`, `/tools`, `/model`, `/steps N`, `/approve auto|ask`,
`/transcript`, `/clear`, `/quit`. Phase 2 adds `/agents`, `/spawn`, `/pause`,
`/resume`, `/stop`.

**Resolved dependency:** ScalaScript had no stdin primitive, which made this mode
inexpressible there. Reported as scalascript#76 and fixed upstream in
`862a19adb` as `std.os.readLine(): Option[String]` — `None` at EOF, so an empty
line and a closed pipe stay distinguishable. See `BACKLOG.md` NAD-2.

## 5. Matrix integration

`rozum:scripts/bench/agentic.sh:135` selects agents by `AGENTS=` and requires
each to be a CLI on `PATH`, invoked per task through `rozum launch <agent>`
(`:836`), which wires the gateway URL and per-agent flags. Entry therefore costs
exactly three things:

1. `nadia` on `PATH`, honoring the batch contract above and working in `cwd`;
2. a `nadia` branch in `rozum launch` (gateway base URL + model id);
3. `AGENTS=nadia scripts/bench/agentic.sh`.

The matrix is already parameterized; no harness change is needed. Success
criterion for P0 is a full matrix row on `greet build fix test debug rpn
wordcount multibug` against Qwen3.5-4B, read as an N-run pass rate, not a
single cell.

## 6. Subagents as actors (P2)

An agent is an actor: mailbox, supervised lifecycle, addressable by id.
`std.actors` (Erlang-style supervision, bounded mailboxes, cluster registry)
supplies the primitive, so nadia adds only the protocol:

```
spawn(task, workspace, budget) → agentId
tell(agentId, message)          → steer a running agent
status(agentId)                 → step, current tool, tokens, elapsed
pause(agentId) / resume(agentId)
stop(agentId)                   → graceful: finish current tool, then halt
kill(agentId)                   → immediate; releases the workspace
```

A parent may spawn children, giving a hierarchy. Each child gets its own
workspace and its own budget, deducted from the parent's. Supervision policy:
a crashed child reports to its parent as a tool error, not a cascade.

## 7. Telegram front-end (P3)

A third client over the same protocol as §6 — the bot maps commands onto the
actor messages, so nothing agent-side is telegram-specific. rozum already runs
the bridge shape (`com.rozum.telegram`, per-room ACL rosters, in-chat
management); nadia reuses that pattern rather than inventing another.

## 8. Phasing

| Phase | Content | Done when |
|---|---|---|
| P0 | 6 tools, sandbox, batch CLI, REPL | matrix row green vs claude/codex |
| P1 | streaming REPL, approval gates, loop breaker | interactive session usable daily |
| P2 | subagents as actors + `/agents` commands | parent drives 2 children to completion |
| P3 | Telegram front-end | same protocol, no agent-side changes |
