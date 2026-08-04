# Development

## Building and testing each implementation

### Scala 3 — `scala/`

```bash
scala-cli test scala           # 40 tests
scala-cli run scala -- run "…" --workspace /tmp/scratch
scala-cli --power package scala -o nadia-scala --assembly
```

Needs `scala-cli` and a JDK 21. One dependency (`upickle`); HTTP is `java.net.http`,
processes are `ProcessBuilder`, files are `java.nio`.

Layout:

```
scala/
  Main.scala        package nadia         arguments in, exit code out
  sdk/              package agent         the generic SDK, no rozum and no nadia in it
  rozum/            package nadia.rozum   sandbox, six tools, prompt, gateway, front-ends
  cloud/            package nadia.cloud   provider presets and credentials (deployment.md)
```

The one rule worth enforcing by hand, because nothing checks it automatically: **nothing
under `sdk/` may name an environment variable, a tool, or a gateway.** If it does, it is not
an SDK any more.

### ScalaScript — `src/`

```bash
ssc run src/nadia.ssc -- run "…"
ssc run src/nadia.ssc -- chat
```

```bash
ssc run src/gate-check.ssc      # the gate's contract rules (SPEC §3.1); exit 0 = they hold
ssc run src/fsx-check.ssc       # nadia.fsx: the reads that must not raise (docs/specs/total-fs.md)
ssc run src/tools-check.ssc     # the tool surface: every wrong input must ANSWER, not raise
```

Those three are what this side has instead of a test suite (`BACKLOG.md` `ssc-test-harness`).
Run them before pushing anything that touches `src/`.

Needs the `ssc` toolchain from the scalascript repository. Two conventions bite here and are
written down where they bit:

- `std.agent` appends `/v1/chat/completions` itself, so it wants the **bare** origin — the
  opposite of the Rust client, which takes the base *with* `/v1`. Getting it backwards is a
  404 with an empty body.
- The loop returns `Done` / `MaxSteps` / `Error`, capitalised. Comparing against `"done"`
  silently reports every successful run as stopped.

**Rebuild the toolchain before measuring anything.** `bin/ssc` warns when it is older than
the checkout, and `SSC_NO_BUILD_CHECK=1` silences exactly that warning. Suppressing it and
then reporting a result cost this project one wrong bug report against a defect that had
already been fixed.

### Rust — `rozum:crates/nadia`

```bash
cargo test -p nadia --no-default-features       # 37 tests
cargo build --release -p nadia
cp target/release/nadia ~/.cargo/bin/nadia
```

Note that `cargo test` does **not** refresh `target/debug/nadia`. Running that binary after
a test-only build gives you the previous build, which in this session produced three
separate false diagnoses before the pattern was recognised. If you are about to invoke the
binary, `cargo build` first.

## Adding a tool

The bar is in [tools.md](tools.md): it must enable a task class that is *impossible* with
the existing six. If it clears that bar:

1. Amend `SPEC.md` §2 first. The spec is the contract all three implementations are
   reviewed against, and a tool that exists in one of them and not the spec is a divergence
   with nothing to arbitrate it.
2. Implement it in all three, or record in `BACKLOG.md` why not.
3. Write the description as a *trigger* ("call this when…"), not a summary.
4. Write the failure messages as instructions to the model.
5. Add a test for the refusal, not just the success. The refusals are the interesting part —
   every tool bug found in this project was in what a tool declined to do.

## Testing philosophy

Three kinds of test earn their place here, and they catch different things.

**Unit tests for refusals.** Path escapes, ambiguous edits, invalid regexes, missing
arguments. These are cheap and they are where the bugs were.

**Loop tests with a scripted model.** `ModelClient` is an interface precisely so the loop
can be driven without a network: budget exhaustion, transport failure, unknown tool,
unparseable arguments, and the repetition guard are all exercised in milliseconds. Before
that split the loop could only be tested by running it, which means it was tested rarely and
never for its edges.

**Live runs against a real model.** Neither of the above would have found the approval gate
that approved everything at EOF, or the verification prompt that trusted exit 0. Both were
found by sitting in the REPL and by reading a benchmark cell. Budget time for this; it finds
a different class of defect than tests do.

## House rules

**Verify against a rebuilt binary.** Every stale-artifact mistake in this project produced a
confident, wrong diagnosis. Check what you are running before you trust what it tells you.

**A measurement is a hypothesis until it repeats.** Read the matrix as a pass rate over N
runs, never as a cell. `wordcount` passed standalone and failed twice in the matrix on the
same code.

**Record what a fix cost.** The verification prompt tripled run times on some tasks. That is
the right trade, but it is only visible if someone wrote it down.

**One fact in one place.** `SPEC.md` says what must be true; these documents say how and
why, and link rather than restate. When they disagree, the spec wins and the document is the
bug.
