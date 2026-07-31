# The six tools

Every tool the agent has. There are six, and the bar for a seventh is that it enables a
task class which is *impossible* with these — not merely more convenient.

That bar is not minimalism for its own sake. Tool schemas are re-sent on every request of
every step, and they compete for the model's attention: stock Claude Code ships ~33 tools
and ~4.9k tokens of schema, which `rozum launch --lean` cuts by 84% precisely because the
extra tools were hurting a small model. Six comes to roughly 1.5–2k tokens.

## Reference

### `read_file`

```jsonc
{ "path": "string" }        // relative to the workspace root
```

Returns the file with line numbers. Line numbers are not decoration: `edit_file` requires
the model to quote text exactly, and numbering makes the model's own reads checkable in the
transcript afterwards.

Output over 8000 bytes is truncated with `"truncated": true`. Truncation is always
announced — a silent cut leaves the model confident about text it never saw.

### `write_file`

```jsonc
{ "path": "string", "content": "string" }   // the COMPLETE new contents
```

Creates or fully replaces. Parent directories are created. The description tells the model
to prefer `edit_file` for changes, because rewriting a file to alter one line silently
drops everything the model did not think to repeat.

### `edit_file`

```jsonc
{ "path": "string", "old_string": "string", "new_string": "string" }
```

**`old_string` must occur exactly once.** Zero or several matches refuse the edit and
change nothing, with a message naming the count:

```
old_string matched 2 times in src/main.rs — it must match exactly once.
Include more surrounding lines to make it unique.
```

This is the tool's entire reason for existing. A version that replaced the first match
would let a model "fix" one of five identical lines and truthfully report success — a
wrong answer that looks exactly like a right one.

Implementation note that has bitten: in Scala and Rust the obvious `replaceFirst` takes a
**regular expression** and a replacement in which `$1` means a capture group. Handing it
raw model output throws on `(a.b)` and silently splices a group for `$1`. Both are quoted;
a test edits a line containing both.

### `list_dir`

```jsonc
{ "path": "string" }        // optional, defaults to the workspace root
```

Names and a `dir` flag, sorted. Cheaper and more predictable than `bash ls`, which is why
it exists as its own tool rather than as advice.

### `grep`

```jsonc
{ "pattern": "string", "path": "string" }   // path optional
```

Regular expression, returning `path:line:text`. An invalid pattern comes back as the regex
error verbatim — something the model can fix — rather than as zero matches, which is
indistinguishable from "not found" and sends it hunting in the wrong place.

`target/`, `.git/` and `node_modules/` are skipped. Not a nicety: `target/` alone can be
hundreds of thousands of files and the walk would dominate the step.

### `bash`

```jsonc
{ "command": "string", "timeout_ms": 120000 }   // timeout optional
```

Returns `exit_code`, `stdout`, `stderr`, `timed_out`. This is how the agent builds, tests
and runs things.

The command string is passed to `bash -lc` verbatim. A model needs pipes, redirection and
`&&` to be useful, and an allowlist of `argv[0]` does not survive `cargo test 2>&1 | tail
-20`. Containment is therefore the sandbox and the timeout — see [safety.md](safety.md) —
not command parsing. Deciding whether a shell string is safe by reading it is a game you
lose.

A killed command reports `exit_code: 124`, which is what `timeout(1)` uses, so the model
sees a convention it was trained on rather than one this project invented.

## Writing a tool that a small model can use

The rules below are not style preferences; each one was measured or paid for.

**Say when to call it, not just what it does.** A description that states the trigger
("Call this when the user asks about current prices") measurably outperforms one that only
describes behaviour. The model reads descriptions as instructions.

**Make the schema strict.** `required` explicit, `additionalProperties: false`, `enum`
where the value set is closed. The gateway constrains decoding against the schema, so a
strict schema makes malformed arguments impossible rather than unlikely.

**Write errors as instructions.** A tool error is the next prompt. Compare:

```
Error: no match                                   ← the model retries the same thing
old_string matched 0 times in a.txt — re-read     ← the model re-reads
the file: the text you quoted is not there
(check whitespace and indentation).
```

**Name the missing argument.** `missing required string argument \`path\`` is actionable;
a type error from inside a handler is not.

**Be atomic and high-level.** One `post_transaction` that performs the whole double entry
beats five primitives the model must sequence correctly. This is also what sets the model
size a task needs: the more sequencing you leave to the model, the bigger the model.

**Never truncate silently.** Cap the output and say you did.
