# Changelog

## feat: MCP servers as tools, and `help` / `?` that answer properly
Completed: 2026-08-01

Two additions, in all three implementations, spec first (`SPEC.md` §2.1, §4.2).

**MCP — the seventh tool is not built, it is connected.** The bar for a seventh
built-in tool is untouched; what changes is that a run can borrow tools nadia
does not define, does not ship and is not responsible for. `--mcp <name>` (or
`--mcp-all`) against the ecosystem's `mcpServers` config, so a file the operator
already has works unchanged; `nadia mcp list [--probe]` shows what is configured
and what each server actually serves.

Five of its rules are decisions rather than defaults, and each is in the spec
with the reason attached:

- **Opt-in per run.** A config that merely exists adds nothing. Six tools cost
  ~1.5–2k schema tokens per request and one server can add a dozen more — the
  same tax `rozum launch --lean` exists to undo. The operator decides when to
  pay, not the filesystem.
- **`mcp__<server>__<tool>`**, so the six can never be shadowed and two servers
  stay apart.
- **Gated exactly like `bash`.** A server is an arbitrary program; treating its
  tools as safer because they have tidy names would be backwards.
- **Outside the jail, and said out loud** at every connect. The path jail and the
  seatbelt confine nadia, not a separate process it started.
- **A named server that will not start ends the run**, before the loop, with its
  name. A run that silently lost half its tools produces a confidently wrong
  answer. A server that dies *mid*-run is the opposite: its tools answer "server
  `x` is gone", which the model can act on, and the six keep working.

Only stdio. A `url` entry is refused **by name** rather than skipped, because an
operator who saw no error would conclude it was connected.

The plumbing differs by what is underneath: rmcp in Rust, `std.mcp.client` in
ScalaScript, and in Scala 3 a hand-written JSON-RPC client
(`scala/sdk/McpClient.scala`, ~120 lines) — which is the implementation's whole
point, since there is nothing below it to ask. That client reads on a thread into
a queue rather than blocking, so a server that accepts and then says nothing
cannot wedge a turn, and it drains the child's stderr, because a full pipe stops
a server after a few kilobytes of its own logging and looks like a protocol bug.

One divergence, stated rather than hidden: ScalaScript's `Transport.Spawn` cannot
pass `env` to the child, so an entry that sets one is refused there instead of
started without it — a server missing its token fails later, further away, and
looks like the server's fault.

**`help` and `?`.** Bare, and with a command name: `help` lists every command with
its *format* (`/tell <id> <message>` — the arguments are what a user does not
know) and one line; `help tell` adds the paragraph that says what is load-bearing
about it. An unknown name gets the names, not the page, because a page hides the
answer to a typo. `nadia help` from the shell prints the same usage as `-h`.

A person who types `help` at a prompt is asking the program; answering with a
model turn spends seconds and a few thousand tokens to say what nadia already
knows. The match is on the whole line, so `help me refactor this` is still a
message for the model.

Each implementation now renders this from a **command table** rather than a
string literal next to the dispatcher. That was the actual defect: two lists that
must agree and therefore eventually don't — the ScalaScript help documented two
commands out of the four it accepted.

Verified live against the same resident Qwen3.5-4B, all three: `nadia run … --mcp
rozum` connected the real `rozum mcp-proxy`, listed its seven tools under the
prefix, and the model called `mcp__rozum__meeting.submit` — the message arrived in
the meeting room from each implementation in turn. Unit tests cover the config
shape, the refusals, the selection errors, the prefixing and the help rendering
(Rust 51, Scala 41 incl. 11 new, ScalaScript exercised through the REPL).

Measured while doing it: `std.mcp.client` documents `Feature.McpClient` as
jvm/js-only, which reads as "not under `ssc run`". It connects and calls fine on
the v2 VM — noted in `src/mcp.ssc` so the next reader does not design around a
limit that is not there.
