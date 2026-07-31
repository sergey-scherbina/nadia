# nadia

An LLM coding agent in [ScalaScript](https://github.com/sergey-scherbina/scalascript),
driving a local model through the [rozum](https://github.com/sergey-scherbina/rozum)
gateway.

One agent loop, two front-ends:

- **batch** — `nadia run "<task>"` runs headless to completion in the current
  directory. Built to be a drop-in row in rozum's agentic matrix next to
  `claude`, `codex` and `opencode`.
- **chat** — `nadia` opens an interactive REPL with streaming output, approval
  prompts before anything writes, and slash commands.

Six tools, chosen to be the minimum that still closes real coding tasks:
`read_file`, `write_file`, `edit_file`, `list_dir`, `grep`, `bash`.

Read [`SPEC.md`](SPEC.md) first — it is written before the code and is what the
code is reviewed against.

## Why it is small

The agent loop, streaming, retry/failover, JSON-schema derivation and the MCP
bridge already exist in `scalascript:runtime/std/agent.ssc`. Rendering tools
into whatever syntax a model family was trained on — Qwen `<tool_call>`, GLM
`<arg_key>`, DeepSeek `<｜tool▁sep｜>`, harmony — and parsing the reply back
already exists in the rozum gateway, together with constrained decoding that
makes malformed arguments impossible rather than unlikely.

So nadia is the leaf: tools, prompts, safety policy, and the user interface.

## Three implementations

| | Run it | State |
|---|---|---|
| Rust | `nadia run "…"` (`rozum:crates/nadia`) | Reference. Subagents, HTTP control surface, Telegram. 8/8 on the rozum matrix. |
| ScalaScript | `ssc run src/nadia.ssc -- run "…"` | Batch + REPL, verified on a live model. |
| Scala 3 | `scala-cli run scala -- run "…"` | Batch + REPL, multi-turn context, its own SDK. 24 tests. |

The Scala 3 one has no external SDK, so it has its own: `scala/sdk/` is the generic half —
model client, agent loop, tool type, repetition guard — in 330 lines, and `scala/` is the
domain half on top of it. That ratio is the honest measure of how much of an agent is
framework.

### Build the Scala 3 one as a binary

```bash
scala-cli --power package scala -o nadia-scala --assembly
```

## Status

**P0.** Usable. See `SPEC.md` §8 for phasing.

## Quickstart

```bash
# a rozum gateway with a tool-capable model
rozum gateway --model mlx-community:Qwen3.5-4B-Instruct-4bit --port 8080

# one task, headless, in the current directory
nadia run "add a --json flag to the CLI and a test for it"

# interactive
nadia
```

## Safety

Every effect goes through a nadia handler that validates first — the SDK itself
never touches the filesystem. Paths resolve through a workspace jail
(`std.fs.resolveWithin`); `bash` runs confined, with a timeout, and with network
off unless `--allow-net`. In chat mode, writes and commands ask before running.

## License

MIT
