# Architecture

## The whole thing in one paragraph

A model is a stateless function: you send it a conversation and a list of tools, and it
answers with either text or a request to call a tool. An agent is a loop around that
function — call the model, run whatever it asked for, append the result, call again — plus
the judgement about when to stop. Everything else in this repository is that loop, the
tools it can reach, and the containment around them.

## Three tiers

The same shape appears in all three implementations, because it is the shape of the
problem rather than a choice:

```
app            tools · prompt · safety policy · CLI/REPL          ← what makes it THIS agent
  │ uses
agent SDK      model client · loop · budgets · repetition guard   ← generic, no domain in it
  │ speaks to
gateway        stateless HTTP: messages + tools → text | tool_calls
  │ owns
model          the weights
```

The line between the app and the SDK is worth defending. The SDK never performs a side
effect — every effect goes through a handler the app supplied, and every handler validates
first. That is not tidiness: it is the property that makes it safe to point a small,
unpredictable model at a real filesystem, because there is exactly one place where "the
model asked for X" becomes "X happened".

The line between the agent and the gateway matters for a different reason, and it is the
one most often blurred. Rendering tool definitions into the syntax a model family was
trained on — Qwen's `<tool_call>`, GLM's `<arg_key>`, DeepSeek's separator tokens — and
parsing the reply back is the **gateway's** job. An agent that also did it would be a
second source of truth, and its failures would present as model failures. This project has
paid for that confusion before; see [safety.md](safety.md) on how a gateway defect once
read as a bad model for two days.

## The three contracts

Named after `rozum:docs/specs/integration.md`, which specifies them once for every
implementation here.

**Contract 1 — the model call.** `POST /v1/chat/completions`, OpenAI form: `messages`,
`tools`, `tool_choice`, `temperature`. The reply is either `content` (final text) or
`tool_calls`. Nothing about model families appears at this layer.

**Contract 2 — the loop.**

```
messages = [system, user]
repeat within budget:
    turn = model(messages, tools)
    if turn has no tool calls:  return turn.text          ← the model is answering
    append the assistant turn (text + the calls it wants)
    for each call: result = dispatch(call); append it as a tool message
```

Two details are load-bearing. The assistant turn must be appended **before** the tool
results, with its call IDs intact — a tool result whose `tool_use_id` has no matching call
is rejected by the gateway. And all results of one turn go back together; splitting them
across messages teaches the model to stop making parallel calls.

**Contract 3 — a tool.** A name, a description, a strict JSON Schema, and a handler
returning either a structured result or *a message written for the model to read*. A tool
error is not an exception; it is the next prompt. See [tools.md](tools.md).

## Three implementations

They differ in exactly one axis — how much sits underneath them — which is why having all
three is worth the duplication.

| | Where | Underneath it | What it is for |
|---|---|---|---|
| **Rust** | `rozum:crates/nadia` | `rozum-agent` (loop, budgets), `rozum-gateway` (client) | The reference. Everything ships here first, and it is the one with subagents, an HTTP control surface and the Telegram front-end. |
| **ScalaScript** | `src/*.ssc` | `std.agent` (loop, streaming, retry, schema derivation) | Dogfoods that language. The thinnest of the three — the SDK carries all three contracts. |
| **Scala 3** | `scala/` | its own SDK in `scala/sdk/`, and under that only the JDK | Answers what the other two cannot: how much of an agent is framework. |

The Scala 3 answer, measured:

| layer | package | lines |
|---|---|---|
| `scala/sdk/` — client, loop, tool type, guard | `agent` | 323 |
| `scala/rozum/` — sandbox, six tools, prompt, gateway wiring, front-ends | `nadia.rozum` | 510 |
| `scala/Main.scala` — arguments in, exit code out | `nadia` | 93 |

The generic half is smaller than the domain half. That is the opposite of how tiering is
usually described, and it is the useful finding: an agent is mostly its tools and its
policy, not its loop.

The layering there is checkable rather than asserted — nothing under `scala/sdk/` names an
environment variable, a tool, or a gateway. When `Endpoint.fromEnv` still read
`ROZUM_GATEWAY_URL`, the SDK was a generic SDK with one project's habits baked in; moving
it to `nadia.rozum.Gateway` is what made the claim true.

## What the loop cannot do by itself

Three failures show up with a small local model that a bigger one hides, and each one has a
mechanism rather than a hope:

- **It does not stop.** Budgets (`maxSteps`, wall clock) bound the damage; they do not
  address the cause. The [repetition guard](safety.md#the-repetition-guard) does.
- **It believes exit 0.** A program can be completely wrong and exit cleanly. That is a
  prompt problem, and the prompt says so explicitly — see [safety.md](safety.md#prompt-as-a-mechanism).
- **It writes where it should not.** That is the [sandbox](safety.md#the-path-jail).

## Where to read next

- [tools.md](tools.md) — the six tools, their schemas and their refusals
- [safety.md](safety.md) — containment, and the incidents that shaped it
- [operations.md](operations.md) — running it, subagents, Telegram, the benchmark matrix
- [development.md](development.md) — building and testing each implementation
- [`../SPEC.md`](../SPEC.md) — the contract all three are reviewed against
