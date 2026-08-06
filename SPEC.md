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
| **Scala 3** | `scala/` | its own SDK in `scala/sdk/`, and under that only the JDK | Carries all three contracts itself, and separates them. |

The Scala 3 one exists to answer a question the other two cannot: *how much of an agent is
the framework?* It has no external SDK — so it grew its own, and the split is the answer:

| layer | package | lines |
|---|---|---|
| `scala/sdk/` — model client, agent loop, tool type, loop guard (Contracts 1–3) | `agent` | 323 |
| `scala/rozum/` — sandbox, the six tools, the prompt, gateway wiring, both front-ends | `nadia.rozum` | 510 |
| `scala/Main.scala` — arguments in, exit code out | `nadia` | 93 |
| tests | | 313 |

The three layers are a dependency chain, not a filing convention: `agent` knows nothing
about rozum or about nadia; `nadia.rozum` knows both; `Main` knows only how to read a
command line. The one place the split is checkable is the SDK's imports — nothing in
`scala/sdk/` names an environment variable, a tool, or a gateway.

The generic half is 330 lines. That is the whole of what `rozum-agent` and `std.agent`
provide to the other two implementations, and it is smaller than the domain code that sits
on top of it — which is the useful finding, because it is the opposite of how the tiering
usually looks in the telling.

Splitting it also bought something end-to-end testing cannot: with `ModelClient` as an
interface, the loop is driven by a scripted client in unit tests — budget exhaustion,
transport failure, unknown tool, unparseable arguments, and the repetition guard are all
exercised without a model. Before the split, the loop could only be tested by running it.

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

### 2.1 MCP tools — the seventh tool is not built, it is connected

The bar above stays where it is: nadia ships six tools and grows none. MCP is the
other answer to "I need one more" — tools nadia does not define, does not ship and
is not responsible for, connected by the operator for a run. All three
implementations MUST support the **client** direction (consume an MCP server's
tools). The provider direction (serving nadia's own six over MCP) is out of scope
here; ScalaScript gets it for free from `std.agent.mcp` and that is not a
divergence, because nothing in this spec asks for it.

1. **Opt-in per run, never ambient.** A config file that merely exists MUST NOT
   add a single tool. Servers are named on the command line (`--mcp <name>`,
   repeatable; `--mcp-all` for every server in the config). The reason is §2's
   schema budget: six tools ≈ 1.5–2k tokens per request, and one MCP server can
   add a dozen more. For a 4B model each one dilutes selection, so the decision
   to pay belongs to the operator, not to whatever file happens to be on disk.
2. **Configuration is the ecosystem's `mcpServers` object**, so an operator's
   existing file works unchanged:
   ```json
   { "mcpServers": { "rozum": { "command": "rozum", "args": ["mcp-proxy"], "env": {} } } }
   ```
   Search order: `--mcp-config <path>` → `<workspace>/.mcp.json` → `~/.config/nadia/mcp.json`.
   **stdio transport only.** An entry carrying a `url`/`type` instead of a
   `command` is **refused by name**, not skipped: an operator who configured an
   HTTP server and saw no error would conclude the tools were connected.
3. **Names are prefixed `mcp__<server>__<tool>`** — the ecosystem's convention.
   The prefix makes collision with the six impossible and keeps two servers with
   the same tool name apart; the un-prefixed name is what goes back on the wire.
4. **The approval gate does not care where a tool came from.** Every MCP call is
   gated exactly like `bash` (§3.3): asked in the REPL, auto-allowed in batch. An
   MCP server is an arbitrary program, and treating its tools as safer than
   `bash` because they have tidy names would be backwards.
5. **The jail does not extend to them, and the implementation MUST say so.** An
   MCP server is a separate process with its own access to the machine — the path
   jail and the seatbelt profile constrain nadia, not it. One line at startup
   naming each connected server and that its tools act *outside* the workspace
   jail. Silence here leaves the operator with a mental model ("nadia is
   confined") that is quietly false.
6. **A named server that will not start is a hard error before the loop begins**
   — exit 2 in batch, naming the server and its command. A run that silently lost
   half its tools produces a confidently wrong answer, which is the failure this
   project keeps paying for. Connect once at startup, never per step.
7. **A server that dies mid-run turns its tools into tool errors**, not into a
   dead run: "server `x` is gone" reaches the model as the next prompt (§2's
   rule that every error is a sentence the model can act on), and the six
   built-ins keep working.
8. **Results map to the same shape as native results** (§2.2): structured content
   when the server sent it, else the text blocks joined; `isError` → `ToolError`;
   the same byte budget and the same explicit truncation marker.
9. **Discovery is listed, not guessed.** `nadia mcp list` prints the configured
   servers; `--probe` connects and lists each server's tools with the prefix they
   will carry. In the REPL, `/tools` shows connected MCP tools alongside the six
   and `/mcp` shows the servers.
10. **Timeouts.** A connect timeout and a per-call timeout (default: `bash`'s
    120 s), so a hung server cannot wedge a turn.

### 2.2 Result formatting

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
2. **Exec confinement.** `bash` runs with `cwd` = workspace root and a hard
   `timeout` (default 120s). Beyond that, what contains it depends on where the
   agent is running, and an implementation MUST report which of the three is in
   force rather than implying the strongest:
   - **`sandbox-exec`** (macOS): writes confined to the root, network **off**
     by default (`--allow-net` opts in). The agent enforces this itself.
   - **The container runtime**: read-only root filesystem, one writable
     workspace mount, no capabilities, and whatever network the runtime chose.
     Fixed before the process starts and not adjustable from inside it —
     `--allow-net` is therefore a no-op and MUST be described as one, because
     the agent and the commands it runs share a network namespace.
   - **Nothing** (a bare Linux process, or `--no-confine`): only `cwd` and the
     timeout hold. Batch mode MUST warn on stderr when confinement was asked
     for and could not be provided.

   A boolean `confine` flag does not satisfy this clause. It cannot distinguish
   "confined" from "asked for confinement on a platform that has none", and an
   operator reads its absence as a guarantee.
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
6. **The verify gate.** Success is not the model's to declare. §3.1 covers this.

### 3.1 The verify gate — what "done" means

The prompt already tells the model to run the build and read the output, and a 4B
model does exactly that and no more: it verifies that the program *builds and
runs*, because nobody wrote down what the right answer is. Measured — a working
RPN calculator whose own printout read `4 + 4 = 7`, reported as finished, because
building and running had both succeeded.

So the acceptance criterion is written down, **before the run**, by the model
itself, and the run is judged against it:

1. **Derive.** Ask the model to formalize the task into structured data —
   `{"checkable":bool, "cargo_test":bool, "run":[{"arg","expect"}]}` — and BUILD
   the shell command from it. The model never writes shell, so it cannot inject
   any; every string it supplies is quoted into a literal.
2. **Check.** Run that command in the workspace after the agent stops. Its exit
   status decides, not the model's summary — and it runs **whatever the stop reason
   was**. A run that exhausted its budget is the one an operator has most doubt
   about; skipping its check to report "not verified" answers the wrong question
   (measured 2026-08-04: a derived check discarded unrun, reported as "no
   machine-checkable criterion", for a program that printed nothing). What a
   non-finished run does not get is a repair round or a judge — there is no budget
   to repair with, and a model's opinion about an interrupted attempt is worth less
   than the command we already have. A run that satisfied the criterion and *then*
   ran out of budget has finished the task: the check decides in both directions.
3. **Repair.** A failure goes back as the next turn carrying **the command and
   what it actually printed** — not "it failed", which is not something a model
   can act on. Bounded (default 2 rounds); a model that has not converged in two
   is not converging.

**The expectation belongs to the task too.** An implementation MUST NOT check a `expect` the task
does not state. A task can say what a program must DO without saying what it prints — read a file
and print the top three words, where the answer lives in the data — and a model asked to formalize
that will invent one: measured 2026-08-05, `a 3 / c 2 / d 2`, three lines appearing nowhere, which
the check then demanded. **No correct program can pass such a check**, and the repair rounds go
into fighting it instead of the real errors (0/4 before the guard, 3/3 after). `checkable: false`
is the right answer there and the prompt asks for it; the guard is what makes it not depend on the
model giving it. Where the expectation is dropped, the check falls back to what CAN be established
(the build, and the tests if the task asked for them).

**Arity belongs to the task, not to the model.** Where the task states an example,
the argument list is read from the task's own punctuation — `cargo run -- 3 4` is two
arguments and `cargo run -- "3 4 + 2 *"` is one — by lexing what follows `cargo run --`
with shell rules. The model says *which* example and *what output*; it is not asked to
shell-lex, and it gets it wrong in both directions when it is (measured 2026-08-04: the
two numbers merged into one argument, and the one quoted argument split into five).
Each mistake fails a program that does exactly what was asked, which is the expensive
kind of gate defect: the operator is told correct work is broken, and the model is sent
to break it.

**`N` repairs means `N + 1` checks.** Checking only before each repair leaves the last attempt
unjudged, and a run that fixed the fault on its final try then reports a failure it no longer has
(measured 2026-08-06: three runs in six). A gate whose last word is about a state that no longer
exists is worse than one that says nothing.

**A repair after a loop-break starts CLEAN.** A turn cut by the repetition guard ends with the
guard's own refusal, and a small model answers the next turn by quoting it — measured, the repair
turn made one step, zero tool calls, and its whole reply was that sentence. The next attempt gets
the task and the check output in a fresh conversation, which is all it needs; what it must not
inherit is the transcript that just ended in a refusal.

Three rules make it honest rather than theatre:

- **`checkable: false` is a valid answer.** A task with no machine-checkable
  criterion ("explain how X works", "make the message clearer") MUST NOT get an
  invented one. The failure this prevents was real: "reply with the word pong"
  became `cargo run -- pong == gnop`, and the run then failed forever against a
  check nobody asked for. An implementation MUST also drop a derived cargo check
  for a workspace with no manifest and a task that never mentioned Rust.
- **Unverified is reported as unverified.** Where nothing was checked, the run
  says so — it does not read as a pass. Silence about verification is what let a
  wrong answer look like a finished task in the first place.
- **A failed check means the task is not done**, whatever the model says. Batch
  exits non-zero (§4.1); a front-end shows the check and its output.

Where nothing deterministic exists, an implementation MAY consult a **semantic
judge** — an independent model reading the task and the code. Its *unknown* MUST
NOT count as a pass: a bounded caller can escalate or report an honest unverified
result, but it may not claim correctness it has no evidence for.

**Implemented in all three** (2026-08-04). Rust: `crates/nadia/src/gate.rs` over
`rozum-agent`'s primitives, which `rozum launch` uses too — one definition, two
consumers. Scala 3: `scala/sdk/Verify.scala` (generic) + `scala/rozum/Gate.scala`
(policy), the same split with nothing underneath it. ScalaScript:
`src/gate.ssc` over `std.agent`.

The parts above that say MUST are checked per implementation rather than by
reading: nine unit tests each in Rust and Scala 3 — deliberately twins, so a
disagreement between them is a bug in one — and, since the ScalaScript side has
no test harness in that repository, `src/gate-check.ssc`, a script that runs the
same rules and exits non-zero when one of them changed.

The three differ only where this spec is silent: how many repair rounds, the
wording shown to an operator, and which front-ends carry it.

The same measurement §0 makes for the loop, now for the gate — and it comes out
the same way:

| | generic (primitives) | domain (policy) | what is underneath |
|---|---:|---:|---|
| Rust | 456 (`rozum-agent::verify`, shared with `rozum launch`) | 197 | a workspace crate |
| Scala 3 | 244 (`sdk/Verify.scala`) | 86 | the JDK |
| ScalaScript | — | 179 (`gate.ssc`) | `std.agent` + `std.process` |

The Rust number is larger because that module is a shared library with two
consumers and carries their tests; the Scala 3 one is the honest cost of the
contract with nothing underneath it. The ScalaScript column has no generic half
at all — the model call and the process exec come from `std`, and what is left is
purely the domain. That is the finding, again: the framework is the smaller
part.

## 4. Modes

### 4.1 Batch (headless)

```
nadia run "<task prompt>" [--workspace DIR] [--model ID] [--gateway URL]
                          [--max-steps N] [--allow-net] [--json]
                          [--mcp NAME]… [--mcp-all] [--mcp-config PATH]
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

Slash commands: `/help`, `/tools`, `/mcp`, `/model`, `/steps N`,
`/approve auto|ask`, `/transcript`, `/clear`, `/quit`. Phase 2 adds `/agents`,
`/spawn`, `/pause`, `/resume`, `/stop`.

**`help` and `?` are commands, not messages.** `help`, `?`, `/help` and `/?`
(trimmed, case-insensitive) are one command with two forms:

- **No argument** — every command, one per line, as **`<format>` — `<short>`**:
  the format is the literal call shape with its arguments
  (`/tell <id> <message>`), not just the name, because the thing a user does not
  know at that moment is the arguments.
- **With a command name** (`help tell`, `? /spawn` — the leading slash is
  optional, since the user is naming a command, not calling one) — that
  command's **format**, its **short** line, and a **longer** paragraph: what it
  is for, and whatever is load-bearing about it (what `/stop` costs that `/kill`
  does not; that `/approve auto` disables the gate for the session, not the
  turn).
- **An unknown name** — say which name was not recognized and list the names
  only. Dumping the whole page in answer to a typo hides the answer.

This requires a **command table** — name, format, short, long — that both the
help and the dispatcher read. Two implementations wrote the help as one string
literal; the drift that follows (a command that works and is undocumented, or is
documented and was renamed) is the failure this clause is meant to prevent.

Why `help` and `?` and not just `/help`: a person who types `help` at a prompt is
asking the program, not the model. Sending it to a 4B model spends a turn and a
few thousand tokens to be told what the program could answer for free — and on a
local model that turn is seconds, not milliseconds. The slash prefix has to be
taught; the two words everybody already tries MUST work. The match is on the
whole line: `help me refactor this` is a message and goes to the model.

The same word works from the shell: `nadia help` prints the usage text and exits
0 — the same text as `-h`/`--help`, because a user who guesses `help` should not
be told that their guess is an unknown mode.

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

## 8. Deployment and the model source

Two axes, independent, and neither may constrain the other.

**Where the model runs.** The agent speaks one wire format — OpenAI-compatible
`POST …/chat/completions` — so a provider is a base URL plus a credential, and
nothing more. An implementation MUST support `local` (a gateway the operator
runs, **no credential**, and the default), and MAY support hosted providers by
constructing their documented URL:

| | |
|---|---|
| `huggingface` | `https://router.huggingface.co/v1`, bearer |
| `bedrock` | `https://bedrock-mantle.{region}.api.aws/v1`, bearer |
| `vertex` | `https://{loc}-aiplatform.googleapis.com/v1/projects/{p}/locations/{loc}/endpoints/openapi`, bearer |

Rules that come out of that, and that a second implementation must not
rediscover:

1. **A base URL that already carries a path is completed no further.** Only a
   bare origin gets `/v1` appended. Vertex's route ends in `…/endpoints/openapi`
   and appending to it yields a 404 that reads like a wrong project.
2. **Credentials are read from a file first**, then the environment. A file is
   not inherited by the `bash` children this agent spawns. There MUST be no
   flag that takes a key inline — that puts it in `ps` and in shell history.
3. **A credential is fetched per request, not per process.** Google's expire in
   an hour, which is shorter than a long run. An ambient identity (metadata
   server) MUST be preferred over key material where the platform offers one.
4. **A provider names where the model comes FROM; the implementation works out
   where it can be RUN.** The Hub is the case that forces this: one namespace
   holds weights to download (`mlx-community/…`, `…-GGUF`) and models a partner
   hosts, and which one a repository is cannot be read off its id. Weights MUST
   be routed to the local runtime — nobody serves them behind an API, so that is
   not a fallback but the only endpoint that could answer — and MUST NOT require
   a credential, since nothing is asked of the Hub at inference time.
5. **One model has several valid spellings, and they MUST compare equal.**
   `org:repo`, `org/repo` and `hf:org/repo` name one set of weights. A string
   comparison anywhere on this path is a defect: in rozum's gateway it warmed a
   second resident copy of the model already loaded.
6. **A model id that cannot work at the endpoint is refused before it is sent**,
   naming what to do instead — the resident-model placeholder `local` sent to a
   hosted provider is the case that exists, and it names `--model`. Where the
   implementation cannot know (a gateway may hold weights it does not
   advertise), it MUST **warn** rather than refuse or stay silent: a rozum
   gateway asked for a model it lacks answers with the one it has and labels the
   reply with the one requested, which is the only failure here invisible in the
   output.

**Where the agent runs.** A container image is the portable unit; batch mode's
exit codes (§4.1) are what makes it a Kubernetes `Job` rather than a Deployment.
Manifests live in `deploy/` and the containment flags they set are what makes
§3.2's second bullet true rather than decorative.

## 9. Phasing

| Phase | Content | Done when |
|---|---|---|
| P0 | 6 tools, sandbox, batch CLI, REPL | matrix row green vs claude/codex |
| P1 | streaming REPL, approval gates, loop breaker | interactive session usable daily |
| P2 | subagents as actors + `/agents` commands | parent drives 2 children to completion |
| P3 | Telegram front-end | same protocol, no agent-side changes |
| P4 | containers + hosted providers (§8) | a task runs to completion in a container against a gateway it does not share a machine with |
| P5 | MCP client (§2.1) + `help`/`?` (§4.2) | a task completes using a tool no implementation ships, connected by name from a config the operator already had |
