# Changelog

## bug026-ports — the rule reaches all three implementations
Completed: 2026-08-06

Yesterday's fix (an `expect` the task never states is not a criterion) landed in the Rust nadia and
`rozum launch`, which share `rozum-agent::verify`, and nowhere else. The Scala 3 and ScalaScript
nadias derive their own checks from the same schema and would still have demanded an invented
answer. `SPEC.md` §3.1 is a contract for three implementations, and a contract kept in one of them
is not a contract.

`taskStates` now exists in all three, with the same tests. Scala 3's own suite caught the hole in
its scripted-derivation test: it had been passing a STUB task text ("rpn calculator") whose
expectation appeared nowhere in it — exactly the shape the rule forbids — so the test was fixed to
state what it expects, which is what a real task does.

## gate-e2e — the two ported gates meet a model, and two defects meet the light
Completed: 2026-08-04

The Scala 3 and ScalaScript gates were closed on 9 unit tests and 18 pure contract rules; neither
had ever run against a model. The first end-to-end run failed a correct program, and the second
found a check that was derived and then thrown away.

**BUG-018 — the gate failed correct work.** For "cargo run -- 3 4 must print 7" the derived check
ran `cargo run -q -- '3 4'`: both numbers as one argument. The program was right — `cargo run -- 3 4`
printed 7 by hand — and the gate spent both repair rounds and reported FAILED. The schema could not
express arity, and stripping the task's delimiting quotes (correct on its own) had removed the only
signal that distinguishes one quoted argument from two bare ones. Asking the model for a LIST
instead traded the bug for its mirror image, measured on the RPN task: it split `"3 4 + 2 *"` into
five arguments. So arity now comes from the TASK — `shellLex` + `taskArgvFor` read the argument list
out of the task's own punctuation, and the model is left with what it is good at: which example, and
what it should print.

**BUG-019 — the run with the most doubt got the least verification.** A run that stopped for any
reason other than "finished" skipped the check it had already derived and printed, and reported
`⚠ not checked`. Measured: an RPN attempt exhausted its steps and left a program that printed
nothing for the invocation the task named; the operator was told the task had no machine-checkable
criterion. The check now runs whatever the stop reason was, while the judge still stands down and
no repair round is spent on an agent with no budget. The exit code follows in both directions: a
run that satisfied the criterion and then ran out of steps exits 0 and says so.

**Both ports were already right about BUG-019** — only the Rust reference had the early break.
Implementing a contract twice is a way of reading it that review is not.

Verdicts, all hand-checked in the workspace afterwards: Rust `sum` ✔ 9 s, `rpn` ✔ 62 s;
Scala 3 `sum` ✔ 12 s (was 59 s and a false FAILED); ScalaScript `sum` ✔ 13 s on a freshly rebuilt
toolchain, because the one warning that side prints is the one this project has already been
burned by ignoring. The budget-exhausted path was proven directly, both halves: wrong artifact →
`✘` with what it printed, rc=1; right artifact → `✔`, rc=0.

## feat: total fs reads (`nadia.fsx`), and the tool-surface defect they uncovered
Completed: 2026-08-04

`std.fs`'s failure behaviour is **undocumented and backend-dependent** —
`specs/std-fs-os.md` maps `listDir` to `Files.list` / `fs.readdirSync` /
`fs::read_dir` and says nothing about a missing path, and those three do not
agree: two raise, one returns a `Result`. So every caller must remember to guard,
and that convention held here 12 times out of 13. The miss was not random: it was
in a diagnostic, code reached only after something else has already failed — one
way being that the workspace is gone. Partial operations get used as if they were
total in exactly the code that runs when things are already wrong.

`src/fsx.ssc` gives those reads a contract: `entriesOf` → `[]`, `textOf` →
`Option`, `textOr(default)`, `isDirSafe`/`isFileSafe` never raise. Deliberately
**not** total by default — a `[]` for a missing directory hides a typo, so the
caller picks and the pick is visible at the call site, the same reasoning the path
jail uses when it refuses rather than clamps.

**The defect this found is the real result.** `read_file` and `edit_file` guarded
with `exists`, which is **true for a directory** — so a model that asked to read a
directory killed the agent with `Is a directory` instead of getting a sentence it
could act on, and `SPEC.md` §2 says a tool error is the next prompt, never an
exception. The Rust and Scala 3 implementations of the same spec answer with a
tool error there, because their fs calls are total by construction; only this one
raised. Fixed, and the error now says *which* mistake it was — `is a directory,
not a file` vs `no such file`. `exists`-then-read is gone with it: that pair is
also a race with the model's own `bash`.

Two contract runs, since this side has no test harness: `src/fsx-check.ssc` (16
cases, including a directory that disappears between two calls — the measured
shape) and `src/tools-check.ssc` (12 cases: a directory where a file was meant, a
missing path, a path outside the jail, and the happy paths, because a tool that
errors on everything would pass all the rest).

**Filed upstream by their procedure**, not fixed in their tree: `scalascript`
`INBOX.md` `std-fs-failure-contract` (`ccd7a5e4d`) via `scripts/inbox-add`
(POLICY.md P-3.10), routing left to their triager (P-3.11), and raised in their
room because a change to a shared contract belongs there (P-5.1). Two asks: state
the failure behaviour per function and per backend — a documentation change with a
cross-backend correctness consequence and no runtime cost — and consider total
variants alongside the partial ones, which is only asking that `fs` get the
principle `std.json` and `resolveWithin` already have.

## feat: the verify gate in the other two implementations
Completed: 2026-08-04

`SPEC.md` §3.1 was written when only the Rust implementation had the gate, and it
said so plainly: "the ScalaScript and Scala 3 ones do not have it yet. That is a
gap in them, not an option." This closes the gap, and the section no longer needs
the caveat.

**Scala 3** — `scala/sdk/Verify.scala` (generic: a `ModelClient` and a directory,
nothing about nadia or a gateway) plus `scala/rozum/Gate.scala` (policy: rounds,
wording, opt-out, exit code). Same split as the Rust one, for the same reason: the
primitives are shared and the policy belongs to the application. Nine tests, each
a deliberate twin of a Rust one — where they disagree, one of them is a bug.

**ScalaScript** — `src/gate.ssc` over `std.agent`, ~150 lines against Scala 3's
~230 for the identical contract, and the difference is exactly what `std.agent`
and `std.process` already provide. The same finding §0 records for the loop, now
for the gate.

Both accuracy rules the Rust one paid for are in from the start: a symmetric pair
of delimiting quotes is not part of the argument, and a project the model left one
level down is NAMED in the repair prompt rather than accommodated by moving the
check into it.

**How each proves its share.** Rust and Scala 3: unit tests. ScalaScript has no
test harness in this repository, so `src/gate-check.ssc` runs the same rules and
exits non-zero when one changed — and it earned its keep on the first run, finding
that `listDir` raises on a missing directory while `misplacedProject` runs on
exactly the failure path where the workspace may be gone. A diagnostic that throws
while diagnosing is worse than none. The missing harness is recorded in
`BACKLOG.md` rather than papered over.

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
