# Agent Instructions

SPEC: SPEC.md
SPRINT: SPRINT.md
BACKLOG: BACKLOG.md
CHANGELOG: CHANGELOG.md
specs: docs/specs

## Before you start

Read `SPEC.md`. It is written before the code and is the contract the code is
reviewed against — if the code and the spec disagree, one of them is a bug, and
you say which. Do not add a tool, a mode, or a flag that the spec does not
describe; extend the spec first (`/spec-dev`).

## Where the pieces live

nadia is the leaf of a three-tier stack. Before implementing anything, check
whether the tier below already has it:

- `scalascript:runtime/std/agent.ssc` — model client, agent loop, tool registry,
  schema derivation, streaming, endpoint pool/retry, MCP bridge.
  Spec: `scalascript:specs/agent-sdk.md`.
- `scalascript:runtime/std/{fs,os,process,actors,http,json}.ssc` — path jail
  (`resolveWithin`), env/args, `exec` with timeout, actors with supervision.
- `rozum` — the gateway: per-family tool rendering and parsing, constrained
  decoding, model residency. Contracts: `rozum:docs/specs/integration.md`.

Duplicating any of these here is a defect, not a shortcut. In particular:
nadia never serializes tools into a model-family syntax, and never parses a
model-family tool-call envelope.

## Coordination

This project shares the `rozum` meeting room with its sibling repos. Identify
yourself once per session with `rozum meetings hello <your-handle>`, post
`working: <what>` before non-trivial work, and `done: <result>` when you stop.
Check recent messages before editing files a sibling may be holding.
