# Running it

Everything here assumes a rozum gateway serving a tool-capable model. Nothing else is
required — no API key, no account, no network.

```bash
rozum gateway --model mlx-community:Qwen3.5-4B-MLX-4bit --port 8080
```

The agent finds it through `OPENAI_BASE_URL` or `ROZUM_GATEWAY_URL` (the same URL in two
spellings, with and without `/v1` — both are accepted), or `--gateway`. The model id comes
from `NADIA_MODEL` or `--model`; a gateway serving one resident model will answer to
whatever it is asked for.

## One task, headless

```bash
nadia run "add a --json flag to the CLI and a test for it"
```

Works in the current directory, prints the final answer, and exits `0` finished · `1` budget
exhausted · `2` gateway failure. `--json` prints the whole run — final text, stop reason,
step count and every tool call with its result — which is what you want when a run
surprises you.

Every run is gated: before it starts, the model formalizes the task into an acceptance check;
after it stops, that check decides. A failure comes back as the next turn carrying the command and
what it actually printed (two rounds by default). The last line says which of four happened —
`✔ check passed: <command>`, `✘ check FAILED` with the output, a model-judge verdict, or
`⚠ not checked` when the task had no machine-checkable criterion. A failed check exits 1: success
is not the model's to declare — and a run that ran out of budget *after* the check passed exits 0,
because failure is not the model's to declare either. The check runs whatever the stop reason was;
only the repair round and the model-judge need the agent to have finished. `NADIA_VERIFY=0` turns it off, `NADIA_VERIFY_ROUNDS=N` changes the
budget. Contract: [SPEC.md](../SPEC.md) §3.1.

Useful flags: `--workspace DIR` (act somewhere other than cwd), `--max-steps N`,
`--allow-net`, `--no-confine`, and `--mcp NAME` to connect an MCP server's tools for this
run ([tools.md](tools.md#the-seventh-tool-is-not-built-it-is-connected--mcp)).

## Interactive

```bash
nadia            # or: nadia chat
```

Line-based on purpose: it works over ssh, in a pipe and inside tmux without a
terminal-control layer. Text streams as the model produces it and each tool call is
announced *before* it runs, so the last line on screen is always what is happening now.

```
› fix the failing test

  ⏺ grep   fn parse
  ⏺ read_file src/parse.rs
  ⏺ edit_file src/parse.rs — 3 line(s)

  edit_file src/parse.rs — 3 line(s)
      - if n > len {
      + if n >= len {
  allow? [y]es / [N]o / [a]lways: y

  ⏺ bash   cargo test
```

Commands: `/help`, `/tools`, `/mcp`, `/clear`, `/context`, `/approve ask|auto`, `/quit`.

**`help` and `?` work without the slash**, and take a command name:

```
› help                     every command, with its format and one line
› help tell                /tell <id> <message>, what it is for, and what it costs
› ?                        the same as help
```

A person who types `help` at a prompt is asking the program, not the model — answering it
with a model turn spends seconds and a few thousand tokens to say what nadia already knows.
The match is on the whole line, so `help me refactor this` is still a message. A mistyped
command gets the list of names rather than the whole page, because a page hides the answer
to a typo. The formats and the descriptions are rendered from one table per implementation,
so the help cannot drift from what the REPL actually accepts.

Writes and commands ask before running (see [safety.md](safety.md#approval-gates)); batch
mode auto-allows, because asking in an unattended run deadlocks on a stdin nobody is at.

## Subagents

*Rust implementation only.* An agent becomes something with an identity, a workspace and a
lifecycle you can drive from outside.

```
/spawn <task>       start one on this workspace
/agents             what they are all doing
/status <id>        one of them, with its result when done
/tell <id> <msg>    give it something for its next turn
/pause <id>  /resume <id>
/stop <id>          finish the current tool call, then wrap up
/kill <id>          abort now and free the slot
```

`stop` and `kill` are different promises. A running agent sits inside the loop, which does
not return until the turn is over, so the only place to reach it is where it already yields
— tool dispatch, between every model step. `stop` therefore lands at the next tool call and
lets the agent write a closing summary; `kill` aborts the task and promises no last words.

Children share the parent's workspace by default: a subagent that cannot see the repository
cannot help with it. Two agents editing one tree can still collide — that is the operator's
call, and why `/agents` shows what each one is touching.

## The HTTP control surface

```bash
nadia serve --port 8790            # loopback only unless --token is given
```

| | |
|---|---|
| `POST /agents` `{task}` | start one → `{id}` |
| `GET /agents` | all of them |
| `GET /agents/{id}` | one, with its result when done |
| `POST /agents/{id}/tell` `{message}` | queue for its next turn |
| `POST /agents/{id}/pause` · `/resume` · `/stop` | lifecycle |
| `DELETE /agents/{id}` | kill |

It **refuses** to bind a non-loopback address without `--token`. That is not a
configuration mistake worth logging: the surface starts processes, so an open port is an
open remote-execution endpoint.

The protocol exists so a front-end does not have to be linked into the agent. That is why
there is no second Telegram bot inside nadia.

## Telegram

*Rust implementation only.* rozum's existing bot gains the same verbs, gated by the roster
that already governs the assistant in that chat:

```
/spawn <task>   /agents   /status <id>   /tell <id> <msg>
/pause <id>   /resume <id>   /stop <id>   /kill <id>
```

`/spawn` requires **both** `write` and `shell` on that roster, because that is exactly what
the agent will do on the caller's behalf; granting it to someone with only `chat` would hand
out capability the roster says they do not have. A refusal names the missing grant. Looking
at agents needs only `chat` — seeing what runs is not the same authority as starting it.

`nadia serve` is started on demand by the first command that needs it, and reused after.
Subagents live inside that process, which is also why it is not a background service: one
that restarted under them would silently lose their work.

## The benchmark matrix

nadia is a row in rozum's agentic matrix alongside `claude`, `codex` and `opencode`, on the
same local model:

```bash
AGENTS="nadia claude codex opencode" \
AGENTIC_MODELS="mlx-community:Qwen3.5-4B-MLX-4bit" \
REPS=2 scripts/bench/agentic.sh          # in the rozum repo
```

No launcher change is needed: `rozum launch` already exports the gateway URL to every agent
it starts, and nadia reads it.

Last full run (Qwen3.5-4B, 8 tasks × 3 reps, 2026-08-06 evening — every gate fix in place):

| | |
|---|---|
| **nadia** | **24/24** — every task 3/3, and **zero repair rounds in all 24 cells** |

The earlier cross-agent table (claude 15/16, nadia 14/16, codex 9/16, opencode 2/16) still carries
the useful conclusion, which was never the ranking: the same model scores 15/16 under one harness
and 2/16 under another, so on a small local model the harness is the dominant variable, not the
weights.

Two cautions this re-run bought, both about reading a matrix at all:

- **A green cell says nothing about the gate.** The harness runs its OWN verifier, so a cell passes
  whenever the code ends up correct — however badly the agent's own check behaved. A run that
  fought a bad check for both repair rounds and got there anyway is a pass in that table. To learn
  anything about the gate, read its lines (`acceptance check — …`, `✔ / ✘ / ⚠`), not the verdict.
- **Measure the binary you installed, not the one you built.** These three reps ran a two-day-old
  `nadia`, because a hand install (`cp … || cargo build …`) succeeded on a stale file. On the
  binary that carries the current gate, `wordcount` is 7/7 with the derived check `cargo build -q`
  — against 3/5 with the invented `a 3 / c 2 / d 2` the old one produced. Install with
  `rozum:scripts/install-bins.sh`, which builds first and prints what it replaced.
