# `nadia.fsx` — total filesystem reads, and the failure contract they carry

**Status:** implemented (`src/fsx.ssc`), used by the ScalaScript implementation.
**Upstream half:** a proposal to `scalascript` by its own procedure — this file is the evidence
that proposal cites, and it stays useful whether or not upstream takes it.

## The finding

`std.fs`'s failure behaviour is **undocumented and backend-dependent**. `specs/std-fs-os.md`
gives a mapping table — `listDir` → `Files.list` (JVM) / `fs.readdirSync` (Node) /
`fs::read_dir` (Rust) — and says nothing about what any of them does when the path is missing.
Those three do not agree: the first two raise, the third returns a `Result`.

So the contract a caller actually programs against is "whatever the host platform does", which
is not a contract. Two consequences:

1. A program that works on one backend may behave differently on another, and nothing in the
   spec would have told you.
2. Every caller must remember to guard. In this repository that convention held 12 times out of
   13 — and the miss was not random.

## Where it bit, and why that spot is not a coincidence

`misplacedProject` (the gate's diagnostic: "your project is one level down") called `listDir`
without checking the directory exists. It is reached **only when an acceptance check has already
failed** — and one of the ways a check fails is that the workspace is gone (rozum BUG-017: the
sandbox let an agent delete its own root). So the one unguarded call sat on the path where the
directory is most likely to be missing, and it turned a legible failure into an unrelated crash.

That is the general shape worth naming: **partial operations get used as if they were total in
exactly the code that runs when things have already gone wrong** — diagnostics, cleanup,
reporting — which is also the code least likely to be exercised in testing.

## What this module is

Total variants of the reads nadia performs, each stating what it does when the path is not there.
Deliberately **not** a total-by-default replacement for `std.fs`:

- **Totality hides a typo.** `listDir("scr")` returning `[]` is indistinguishable from an empty
  `src`. That is the right answer for a diagnostic and the wrong one for a tool the model drives,
  which must say *why* it got nothing.
- So the caller picks, and **the pick is visible at the call site**: `entriesOf` says "empty is
  fine here", `listDir` says "this must exist". The same reasoning the path jail uses when it
  refuses rather than clamps — the loud option is not always the right one, but silently choosing
  for the caller always is wrong.

| function | when the path is missing / wrong type | use it when |
|---|---|---|
| `entriesOf(dir)` | `[]` | you are describing a directory that may not exist |
| `textOf(file)` | `None` | the file's absence is an ordinary case |
| `textOr(file, d)` | `d` | you have a sensible default |
| `isDirSafe` / `isFileSafe` | `false` | a predicate should never raise |

Errors other than "missing" (a permission denial, an I/O fault) collapse into the same answer.
That is a real limitation and it is stated rather than hidden: `std.fs` gives us no way to tell
them apart, which is part of what the upstream proposal asks for.

## Rules

1. **Every fs read in this implementation goes through this module.** The guard becomes
   structural. A `grep` for the raw primitives outside `fsx.ssc` is the check.
2. **Tools keep their own errors.** `read_file` on a missing path must still answer the model
   "read X: no such file" — an actionable sentence, not an empty result (`SPEC.md` §2, "every
   error a sentence the model can act on"). Tools therefore use the predicates, then the raw
   read; they do not silently return nothing.
3. **The contract is checked by running it**, `src/fsx-check.ssc`, including the missing-path
   cases that raise today. This side of the project has no test harness (`BACKLOG.md`
   `ssc-test-harness`), so the check is a script that exits non-zero.

## What the upstream proposal asks for

Filed to `scalascript` through its own inbound queue (`POLICY.md` P-3.10 — `scripts/inbox-add`,
never by hand) and raised in its room, since a change to `std.fs`'s contract is a shared-contract
change (P-5.1). The routing decision (`lane:` / `area:`) is left to their triager (P-3.11).

Two asks, the first much more important than the second:

1. **State the failure behaviour in `specs/std-fs-os.md`**, per function and per backend. Today
   the table says what each maps to, not what it does when it fails — and the three backends
   disagree. This is a documentation defect with a cross-backend correctness consequence, and it
   is fixable without touching a line of runtime code.
2. **Offer total variants in `std.fs`** — `listDirOpt` / `readFileOpt`, or whatever spelling fits
   — so consumers stop each writing their own. The vocabulary already exists in that library:
   `std.json` navigation is explicitly total ("a missing key, wrong shape, or parse failure
   funnels to a Null JsonValue, never a crash") and `resolveWithin` returns an `Option`. The ask
   is to apply to `fs` a principle the library already applies to JSON and to paths.

If neither is taken, nothing breaks: this module keeps working, and the cost is that every other
consumer discovers the same thing the same way.
