# Safety

What stops a small, unpredictable model from damaging the machine it is working on — and,
for each mechanism, the failure that shaped it. Every incident below was found in this
project, most of them by running the thing rather than by reading it.

The one structural property everything else rests on: **the loop never performs a side
effect.** Every effect goes through a handler, and every handler validates before acting.
There is exactly one place where "the model asked for X" becomes "X happened", which is
what makes the rest of this document possible to write.

## The path jail

Every model-supplied path is resolved against the workspace root and **refused** if it
lands outside. The file need not exist yet, so the canonical form is the deepest existing
ancestor plus the remaining tail, normalized — which closes both escapes a naive
`root.join(rel)` leaves open: `..` climbing out, and a symlinked ancestor pointing
elsewhere.

**Refused, never clamped.** A jail that strips `..` turns `../secrets` into
`<root>/secrets` and writes to a path nobody asked for. That is a silent wrong answer where
a loud refusal was available, and the model can act on a refusal.

## Exec confinement

`bash` runs with the workspace as its working directory, under a hard timeout, and — on
macOS — inside a `sandbox-exec` profile that denies writes outside the workspace and denies
network unless `--allow-net` is passed.

Two decisions in that profile are deliberate:

**Writes are confined; reads are not.** Confining reads aborts dyld before the child ever
runs, and buys little: the threat model is an agent mangling the machine, not exfiltration
from a process that already has the operator's shell.

**`/tmp` is not on the allowlist**, and this is the incident. The first version allowed
`/private/tmp` so the toolchain could use temp files — and on macOS `/tmp` is a symlink to
`/private/tmp`, so allowing either allows both, handing the agent a world-writable channel
straight out of the workspace. It was caught by the test that asserts a write outside the
root *fails*, which is the only kind of test that could have caught it. The toolchain does
not need it: `$TMPDIR` is a private `/var/folders/…` path.

## Approval gates

In interactive mode the three mutating tools — `write_file`, `edit_file`, `bash` — ask
before running: `y` / `n` / `a` (always, for this session). Reads are never gated, because
prompting for `read_file` trains the operator to hit `y` without looking, which is how the
one prompt that mattered gets waved through too.

An edit prompt shows both sides of the change, bounded, because "replace `<first line>`"
cannot answer *is this the right replacement*.

A denial is returned to the model as a tool error telling it not to retry and to propose
something else. A silent halt would leave it unable to respond at all.

### The gate that approved everything

The first version read an empty line as *allow* — reasoning that pressing Enter means "go
ahead". `read_line` also returns an empty string at **EOF**. So the moment stdin was
exhausted — a pipe, a closed terminal, a script — every subsequent write and command was
approved automatically.

It was found by driving the REPL for real: piping `n` at a `write_file` prompt, and
watching the file appear anyway. Now only an explicit `y`/`yes`/`a`/`always` allows; EOF and
a bare Enter both refuse, the prompt spells the default as `[N]o`, and the decision is a
pure function so both cases are pinned by tests.

The same defect — end-of-input indistinguishable from empty input — had been written up for
a sibling project days earlier. Knowing about a class of bug does not stop you writing it;
a test does.

## Budgets

`maxSteps` (24 by default), a wall-clock ceiling, and a per-call token cap. Exhaustion
returns a **partial result** rather than throwing: a budget is a stop condition, not a
failure, and the operator still wants to see what was done.

The batch exit codes are a contract with rozum's benchmark harness: `0` finished, `1` budget
exhausted, `2` gateway or transport failure. Conflating the last two is how a dead gateway
gets recorded as a bad model.

## The repetition guard

Budgets bound the damage; they do not address what a small model actually does, which is
re-issue an identical call **after an identical result** — most often an edit whose
`old_string` is not in the file, read as "try again" rather than "the premise is wrong".

The guard fires when the same call with the same arguments returns the same result four
times in a twelve-call window, and it fires as an error the model reads rather than as a
halt.

### Both halves of that sentence are load-bearing

The first version matched on the call alone. That is also the signature of the **verify**
step of fix → test → fix: an agent told to check its work re-runs `cargo test` on purpose,
byte-identically, and the output differs each time because the files changed underneath it.

Measured on rozum's matrix (Qwen3.5-4B, 8 tasks × 2 reps × 4 agents), the call-only form cut
**11 of 16 cells for nadia and 6 of 16 for codex, and none of claude's**. The skew was the
tell: not that two agents loop and one does not, but that their prompts ask them to verify
in a way the signature read as churn. On `multibug` it cut the agent off mid-repair; adding
the result comparison turned that cell from FAIL to PASS.

The same defect existed in rozum's own gateway-side guard and is fixed there too
(`BUG-014`), with the same evidence.

## Prompt as a mechanism

Not everything that governs behaviour is code. The system prompt originally said *"if the
command failed, the task is not finished"* — which anchors verification on exit status, and
a program can be completely wrong and exit 0.

That is exactly what happened on the `wordcount` benchmark task: the model wrote a
comparator that sorted by word length and never looked at the count, `cargo run` exited 0
printing visibly unsorted output, and the agent treated the clean exit as success and re-ran
the same command instead of reading it. The prompt now says exiting 0 proves nothing and
asks for the output to be compared against the task value by value. That task went from
**0/2 to 4/4** on the same model and the same gateway build.

Small samples on both sides — but the mechanism matches the observed failure exactly, and
the cost is visible and expected: those runs take two to three times longer, because the
agent now actually iterates instead of declaring victory on the first clean exit.

## What is not defended against

Stated plainly, because a safety document that implies more coverage than it has is worse
than none:

- **A prompt-injecting repository.** Content the agent reads can instruct it. Nothing here
  distinguishes "the file says X" from "the user asked for X".
- **Network egress when `--allow-net` is on.** That flag turns the network back on wholesale.
- **Anything outside macOS.** The seatbelt profile is macOS-only; elsewhere `bash` gets the
  workspace as its cwd and a timeout, and nothing more. `Sandbox.seatbeltAvailable` says so
  rather than pretending.
- **A malicious model.** The mechanisms here assume a *weak* model, not an adversarial one.
  Every refusal is a message the model could route around if it were trying to.
