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

## Status

**P0, in progress.** Not yet usable. See `SPEC.md` §8 for phasing.

## Quickstart (once P0 lands)

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
