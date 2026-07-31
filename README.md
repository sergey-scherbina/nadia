# nadia

A coding agent that runs on a local model. It reads and edits files, runs commands, checks
its own work, and stops when it is done — driving a small model through the
[rozum](https://github.com/sergey-scherbina/rozum) gateway, with no API key and no network.

```bash
# a gateway with a tool-capable model
rozum gateway --model mlx-community:Qwen3.5-4B-MLX-4bit --port 8080

# one task, headless, in the current directory
nadia run "add a --json flag to the CLI and a test for it"

# or sit in it
nadia
```

On rozum's benchmark matrix — eight coding tasks, same local Qwen3.5-4B for everyone —
nadia scores **14/16** against Claude Code's 15/16, Codex's 9/16 and opencode's 2/16.
Read that as a pass rate rather than a ranking: one cell separates the top two, which is
noise at two repetitions. The interesting number is the spread, because it says the harness
around a small model matters more than the weights.

## Six tools

`read_file` · `write_file` · `edit_file` · `list_dir` · `grep` · `bash`

That is the whole surface, and the bar for a seventh is that it enables something
*impossible* with these six. Tool schemas are re-sent on every step and compete for a small
model's attention — [tools.md](docs/tools.md) has the reasoning and the measurements.

## Three implementations, one spec

| | Where | Underneath it |
|---|---|---|
| **Rust** | `rozum:crates/nadia` | `rozum-agent`, `rozum-gateway` — the reference; subagents, HTTP surface, Telegram |
| **ScalaScript** | [`src/`](src) | `std.agent` — the thinnest; the SDK carries all three contracts |
| **Scala 3** | [`scala/`](scala) | its own 323-line SDK, and under that only the JDK |

They differ in exactly one axis: how much sits underneath. The Scala 3 one exists to answer
what the other two cannot — how much of an agent is framework. The answer is that the
generic half is *smaller* than the domain half: an agent is mostly its tools and its policy,
not its loop.

All three implement [`SPEC.md`](SPEC.md), which was written before any of them. Where two
disagree, the spec decides.

## Somewhere other than your laptop

```bash
docker compose run --rm nadia run "add a --json flag and a test for it"
kubectl apply -k deploy/k8s
```

A container image, Kubernetes Jobs, ECS and Cloud Run — and the model can stay local or come
from Hugging Face, with Bedrock and Vertex there when you want them. `local` is still the
default and still credential-free.

`--provider huggingface` takes the Hub as what it is: a source of *weights* and a source of
*serving*, in one namespace. `mlx-community/Qwen3.5-4B-MLX-4bit` — the repository this
project's own model comes from — routes to your gateway, which fetches it, and needs no
token; a partner-hosted repository routes to the Hub's router and needs one. Which is which
cannot be read off the id, so nadia routes on it rather than making you know.

The interesting part is what happens to the safety model on the way. `sandbox-exec` does not
exist on Linux, so the agent stops claiming it: it names the mechanism actually in force,
and `--allow-net` reports itself as the no-op it becomes inside a container, where the agent
and the commands it runs share one network namespace. Restricting egress moves up to the
layer that owns the network — which is what `deploy/k8s/networkpolicy.yaml` is for.

[deployment.md](docs/deployment.md) has the whole of it, including which parts were run and
which are reviewed templates that have never touched a real account.

## Safety

The loop never performs a side effect. Every effect goes through a handler that validates
first, which is what makes it safe to point an unpredictable model at a real filesystem.

Paths are jailed to the workspace and escapes are **refused, not clamped**. `bash` runs
confined, with a timeout, and with the network off unless you ask. In interactive mode
writes and commands ask first. Budgets and a repetition guard stop a model that has lost the
thread.

[safety.md](docs/safety.md) documents each mechanism together with the failure that shaped
it — including the approval gate that once approved everything at end-of-input, and the
repetition guard that cut agents off mid-repair.

## Documentation

| | |
|---|---|
| [architecture.md](docs/architecture.md) | how an agent works, the three contracts, the three tiers |
| [tools.md](docs/tools.md) | the six tools, and how to write one a small model can use |
| [safety.md](docs/safety.md) | containment, and the incidents behind it |
| [operations.md](docs/operations.md) | running it, subagents, Telegram, the matrix |
| [deployment.md](docs/deployment.md) | containers, Kubernetes, AWS, Google — and what confinement means once you leave macOS |
| [development.md](docs/development.md) | building and testing each implementation |
| [SPEC.md](SPEC.md) | the contract |
| [BACKLOG.md](BACKLOG.md) | what is not done, and what is blocked upstream |

## License

MIT
