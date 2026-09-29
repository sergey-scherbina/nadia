# nadia as an okay application — one program, every screen

**Status:** spec; the platform half landed in okay 2026-09-29 (`ae49fb7ae` the specs, then
`e3fd797f9` telegram-live, `0c21077e2` identity-roster, `2f20f5a03` llm-models, `654a4f98b`
agent-fleet); `app/` is being built against it.
**Global spec:** `SPEC.md` §0 (the fourth implementation), §6 (subagents), §7 (Telegram), §10
(model management) — this file is the design those sections point at.

> **Rescoped 2026-09-29, by the operator's split with the workspace-UI session:** the SCREENS below
> are drawn by `ui/` — the workspace of `rozum:docs/specs/okay-workspace-ui.md`, one program for
> projects, rooms, agents and chats — and not by `app/`. `app/` is the SERVICE: the fleet, nadia's
> runner (the six tools, the sandbox, the prompt, the gate over okay-agent), the access roster, the
> models seam, and the state log served over the wire. What the UI consumes is written under
> `BACKLOG.md` NAD-14 (the record, the subscription, the control plane, approvals). The screen
> table, the free-text rule and the access rules below stay as the *requirements* the workspace
> meets for nadia's agents; where `ui/` draws them differently, `ui/` decides.

## Overview

The operator's goal, in their words: work on one machine, locally, with an agent that drives a
model in rozum by default (Anthropic or OpenAI when asked), that manages the models in rozum,
navigates the machine and its projects, runs tasks in them through a hierarchy of subagents —
and all of that through Telegram, the console, the web and a desktop window **as one thing**:
one state, continued from any surface, surviving restarts, later backed up, replicated, and
shared per chat or per project with another person.

This spec is the first slice: **a Telegram bot with buttons and menus, and the same program in
the console, over a hierarchy of subagents the operator controls, with access rights.** It is
written so that the web and desktop surfaces, sharing and replication are additions of a host or
a store, not a redesign.

The platform is `okay` (`../okay`, a git submodule here). Its thesis — *one application, drawn
by any host without the program knowing which* (`okay:specs/ui.md`) — is this project's
requirement stated as a library. nadia therefore owns only its business logic: what the screens
say, which tools an agent has, what a parent may delegate, who may press what. Everything with
no nadia in it (the agent loop, actors, the durable log, the Bot API client, the model catalog)
lives in `okay`, and where `okay` lacks it, `okay` is extended first.

## Interface

### Where it lives

A fourth implementation beside the three of `SPEC.md` §0, in `app/` (the service) and `ui/` (the
workspace, the UI session's):

```
build.sbt                 root sbt build: `app` depends on okay modules by ProjectRef
okay/                     git submodule → ../okay (source dependency, no publishing)
app/src/main/scala/nadia/
  agent/NadiaRunner.scala the agent: six tools + `delegate`, prompt, gate — over okay-agent's Fleet
  Main.scala              `nadia-app serve`: the store, the fleet, the roster, the models seam,
                          the wire — wiring, nothing else
app/src/test/scala/...    munit
ui/                       the workspace program and its hosts (its own build; NAD-14)
```

The six tools, the sandbox, the prompt and the gate are the ones in `scala/rozum/` — the same
policy, wrapped into an okay `Toolbox`. They are not rewritten; if they are reused verbatim the
sbt project adds `scala/rozum` and `scala/sdk` as a source directory, and that is decided at
implementation time by whichever is smaller.

### The program: screens (drawn by `ui/` — see the rescoping note above)

One okay-ui program. Every surface draws the same `Ui` value; a Telegram chat draws it as one
message with an inline keyboard, edited in place (`okay:specs/ui-telegram.md`); the console draws
it with `Terminal.host`. The screens, and what is on each:

| screen | shows | buttons |
|---|---|---|
| **Home** | the current project and model, counts of running agents | Projects · Agents · Models · Access |
| **Projects** | the configured roots (`NADIA_PROJECTS`, `:`-separated) and the directories under the one being browsed | one per entry · Up · Use this |
| **Project** | path, its running agents, the last result | Run a task · Agents here · Back |
| **Agents** | every agent: id, phase, project, one line of status | one per agent · Refresh |
| **Agent** | task, phase, step, current tool, elapsed, children, the gate's report, the result | Pause/Resume · Stop · Kill · Tell · Parent · Children |
| **Models** | the provider (rozum by default), the catalog with the resident one marked | one per model: Use · Load · Unload · Provider |
| **Access** | the owner, the invited users and what each may do | Invite · Revoke |

**Free text is the message to the current context.** With an agent selected it is `tell`; with a
project selected and no agent, it starts a task there; with neither, the reply says what to select
first. This is the same rule on every surface, and it is what makes a chat usable without opening
a menu for every step.

**Telegram commands** (`setMyCommands`, so they appear in the client's menu): `/start` (Home),
`/projects`, `/agents`, `/models`, `/help`. Each opens its screen; the buttons do the rest. There
is no command that a button cannot also do — the command table of `SPEC.md` §4.2 stays the
REPL's; the app's vocabulary is the screen.

**Progress reaches the chat without asking.** An agent's card is edited in place as its status
changes (throttled: at most one edit per two seconds per message, because the Bot API rate-limits
edits and a 4B model makes a tool call a second); its result and the gate's verdict are posted as
a new message, so they are not lost when the card moves on.

### The hierarchy: subagents

`SPEC.md` §6 is the protocol. What this spec fixes:

- **An agent is an okay actor** (`okay-actor`: typed mailbox, supervision, `spawnChild`). The
  fleet is the root actor; each agent is its child; a delegated subagent is that agent's child.
  A crashed child reports to its parent as a tool error, never as a cascade (`Supervise.Stop`).
- **The parent delegates itself.** The parent's toolbox has one tool beyond the six:
  `delegate(task, subdir?, budget?)` → runs a child to completion in the same workspace (or a
  subdirectory of it) and returns the child's final text and the gate's report. Budget is deducted
  from the parent's remaining steps. This is the seventh tool `SPEC.md` §2 allows: a hierarchy is
  impossible with the six, not merely inconvenient.
- **Every operator action is also a message to the actor**: `tell`, `pause`, `resume`, `stop`
  (finish the current tool, then halt), `kill` (abort; the workspace is released). The app's
  buttons and the REPL's commands send the same messages; nothing agent-side knows a surface.
- **Status is a value** — `Status(id, parent, task, workspace, phase, step, lastTool, elapsed,
  children, report, result)` — so a screen, a JSON endpoint and a test read the same thing.

### The state: one log

- Everything the app must remember is appended to an `okay-persist` `Topic` in a `FileStore`
  under `~/.nadia/app/` (`NADIA_STATE` overrides): agent records (spawned / status changed /
  finished), the conversation turns of every agent, the access roster, and each chat's current
  context (project, selected agent). The projection the screens read is a fold of that log.
- **Restart restores.** Agents that were running come back as `Interrupted` with their transcript,
  and can be resumed as a fresh run given the task and the last gate output (the rule `SPEC.md` §3.1
  already states for a repair). Ids keep counting.
- Backup, replication and sync are the log's properties, not the app's: `okay-persist` already
  replicates a topic and `okay-blob` copies closed segments. **Not wired in this slice** — but the
  reason the state is a log rather than a JSON file per agent (the Rust one) is so that they can be.

### Access

- A person is an `okay-security` `Principal`. The Telegram user id is the only identity in this
  slice; the console is the owner.
- The owner is `NADIA_TELEGRAM_OWNER` (a user id). A message from anyone else is answered with one
  line saying so and nothing else happens — no menu, no state. `Invite` (owner only) adds a user id
  with a role: `viewer` (may open every screen, press nothing that changes state), `operator` (may
  run and steer agents in the projects the owner names), and the roster is in the log.
- The policy is one `okay-security` `Policy`: `(principal, action, resource) → Permit/Deny`,
  consulted by `update` before every state-changing event. The screen a viewer sees has no
  buttons they may not press — a button that answers "denied" is a bug.

### Models

`SPEC.md` §10 defines the seam (`okay-llm` `Models`): a **catalog** every provider has, and a
**residency** and a **store** only local hosts have. The Models screen draws whatever the provider
offers: a hosted provider shows a list with `Use`; rozum shows `Use`, `Load`, `Unload`, and the
resident one marked. Selecting a provider (`rozum`, `anthropic`, `openai`) is an `okay-agent`
`Provider` swap; the default is rozum at `ROZUM_GATEWAY_URL` / `OPENAI_BASE_URL`, `localhost:8080`
when neither is set — the same resolution `scala/rozum/Gateway.scala` does today.

## Behavior

Access:
- [ ] a message from a user id that is not the owner and not invited is answered with one line
      and creates no state
- [ ] a viewer's screen carries no state-changing button; the same screen for the owner does
- [ ] `Invite`/`Revoke` are owner-only and survive a restart

Screens:
- [ ] `/start` shows Home with the four buttons; pressing `Projects` edits the same message
- [ ] browsing a project root never leaves it (`resolveWithin`); `Up` at the root is not offered
- [ ] free text with a project selected and no agent starts a task there; with an agent selected
      it is `tell`; with neither it says what to select

Agents:
- [ ] a task started from the chat appears in `Agents` with a phase, and its card is edited as it
      runs, no more than one edit per two seconds
- [ ] `Stop` finishes the current tool and halts; `Kill` aborts; both are reflected in the card
- [ ] a parent given a task that names two independent parts delegates twice; each child's steps
      are deducted from the parent's budget; the parent's result carries both children's
- [ ] a child that crashes shows in the parent's transcript as a tool error; the parent continues
- [ ] the gate's report reaches the chat as its own message, whatever the stop reason

State:
- [ ] restart with two running agents: both come back `Interrupted` with their transcripts, ids
      continue from the last, and the chat's selected project is still selected
- [ ] the console and the bot started against the same state directory show the same Agents list

Models:
- [ ] `Models` against rozum lists the catalog with the resident model marked; `Load` on another
      model switches residency and the mark moves; `Unload` clears it
- [ ] `Models` against Anthropic lists the catalog and offers `Use` only — no `Load`/`Unload`
      button exists on that screen
- [ ] `Use` on a model changes the model every new agent is started with; running agents keep theirs

Same program, every host:
- [ ] the Ui tree for each screen is asserted equal whether rendered for Telegram or the terminal
      — the test drives `update` with the same events and compares the values, no network

## Out of scope

- The web and desktop hosts. They are `Wire.serve` + `Wire.client` and `okay-desktop` around the
  same program, and are the next slice, not this one.
- Sharing a chat or a project with another person by link, and a shared conversation. The access
  roster is the hook; the sharing is its own spec.
- Backup, replication, cloud sync of the state log. The log makes them possible; nothing here wires
  them.
- Telegram webhooks (long polling only), files and media in either direction, groups and topics.
  Private chats only in this slice.
- Loading weights directly into this process (no gateway). The Models seam does not preclude it;
  rozum is the default and the only local host implemented here.
- The REPL and batch modes of the three existing implementations — untouched.

## Design

What goes **into `okay`** (each its own spec there, by that repository's protocol, before code):

| in okay | module | what |
|---|---|---|
| agent hierarchy | `okay-agent` | `Fleet`: agents as supervised actors with budgets, `delegate` as a tool, `Status` as a value, the messages of `SPEC.md` §6; sessions (`Turn` history) as a topic |
| models seam | `okay-llm` | `Models`: `Catalog` (OpenAI `/v1/models` shape), `Residency`, `Store`; adapters `openAi`, `anthropic`, `ollama`, `rozum`; id equality (`SPEC.md` §8.5) |
| identity | `okay-security` | a channel identity bound to a `Principal` (Telegram user id, later web session), a roster with roles, persisted as a topic |
| chat host | `okay-telegram` | throttled in-place edits for a live card; `setMyCommands` from a screen table |

What stays **in nadia**: the screens, the six tools and their policy, the prompt, the gate, who
may press what, the wiring. The budget for the whole of `app/` is the size of `scala/rozum/`
today (about 500 lines); if it grows past that, something in it is platform code in the wrong
place.

The three existing implementations stay as they are. This one differs from them on the same axis
`SPEC.md` §0 names — how much sits underneath — and has the most underneath, which is exactly why
it is the one that grows the surfaces.

## Decisions

- **The parent delegates itself** — chosen because a hierarchy an operator has to assemble by hand
  is a list, not a hierarchy; and because it is the one case that meets §2's bar for a seventh
  tool. Rejected: operator-only spawn (the Rust reference) — kept there, since that one has no
  model-driven delegation and the spec does not require it of a batch runner.
- **One program drawn by hosts, not a bot with handlers** — chosen because the operator's
  requirement is the same state on every surface, and a handler per surface is the shape that
  drifts (rozum's Telegram bridge and its REPL already have two command tables). Rejected: extend
  rozum's `com.rozum.telegram` bridge with keyboards — it is text commands over HTTP to `nadia
  serve`, a third state store, and not on the okay stack.
- **A log, not a file per agent** — chosen because restart, backup and replication are then one
  mechanism `okay-persist` already has. Rejected: `~/.nadia/.agents/<id>.json` as the Rust one
  does — restores, but cannot be replicated or shared without inventing a second mechanism.
- **Owner by Telegram user id, roles from a roster** — chosen because it is the smallest thing
  that is not "anyone who finds the bot". Rejected for now: OIDC login, invite links — they belong
  to the sharing slice.
- **Models seam modelled on two de-facto standards, not one** — see `SPEC.md` §10.
- **Private chats only** — a group is a different access model (who in the group is the
  principal?) and belongs with sharing.

## Results

Not implemented yet.
