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

Useful flags: `--workspace DIR` (act somewhere other than cwd), `--max-steps N`,
`--allow-net`, `--no-confine`.

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

Commands: `/help`, `/tools`, `/clear`, `/context`, `/approve ask|auto`, `/quit`.

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

Last full run (Qwen3.5-4B, 8 tasks × 2 reps, before the verification-prompt fix):

| | | |
|---|---|---|
| claude | 15/16 | 33 s per passing cell |
| **nadia** | **14/16** | **30 s** |
| codex | 9/16 | 102 s |
| opencode | 2/16 | — |

Read it as a pass *rate*, not as cells: one cell separates the top two, which is inside the
noise at two reps. The useful conclusion is not the ranking but the spread — the same model
scores 15/16 under one harness and 2/16 under another, so on a small local model the harness
is the dominant variable, not the weights.

Two caveats on that run, both since addressed: nadia was cut short by the gateway's
repetition guard in 11 of its 16 cells while claude was never cut (see
[safety.md](safety.md#the-repetition-guard)), and the one task it lost, `wordcount`, went to
4/4 after the verification prompt was fixed. A clean re-run is still owed.
