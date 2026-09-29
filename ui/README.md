# nadia/ui — the workspace, drawn by any host

The prototype of `rozum:docs/specs/okay-workspace-ui.md`: ONE program — projects,
rooms, agents, chats, switching between them with the draft and the reading
position kept — written as a pure `view`/`update` over okay-ui, and drawn by a
terminal, a chat, or any thin client over the wire. Nothing in `Workspace.scala`
knows which.

```
Model.scala      the "what": World (what the daemons say) · Context per item (what
                 switching keeps) · Workspace (focus + contexts + world)
Program.scala    view(layout)(state) and update(feed)(state, event) — one of each
Feed.scala       where the world comes from, and the doors back: InMemory · Rozum (the
                 meeting daemon's REST on :8401: rooms, roster, presence, transcript,
                 post) with AgentSources — NadiaServe (`nadia serve`: list, tell,
                 pause/resume/stop, spawn) and ControlApi (control-serve: model
                 participants and coders, stop)
Shared.scala     ONE session, many devices: the state cell every host folds into
                 and is told about; the journal (JSON lines, intent-first) it is
                 recovered from — so quitting the terminal and opening the chat
                 continues where you were
Main.scala       the hosts: terminal · chat (the Telegram host, driven from the
                 console) · wire (JSON lines on stdio, for a client in any language)
```

The session lives at `$XDG_STATE_HOME/nadia/workspace.jsonl` (`workspace-fixture.jsonl`
with `--fixture`; `NADIA_UI_JOURNAL` overrides; `--fresh` starts over). Focus is part
of the session, so a room opened on the phone is open on the terminal — "continue
where I left off"; a per-device focus would be a map keyed by device, a decision
to revisit with real use.

## Build

okay is not published; publish it to your machine once (its closure for okay-ui):

```
cd ../../okay
sbt okayJVM/publishLocal okayOpticsJVM/publishLocal okayAsyncJVM/publishLocal \
    okayPlatformJVM/publishLocal okayStreamJVM/publishLocal okayLexJVM/publishLocal \
    okayParseJVM/publishLocal okayCodecJVM/publishLocal okayJsJVM/publishLocal \
    okayDirectJVM/publishLocal okayWorkflowJVM/publishLocal okayPersistJVM/publishLocal \
    okayUiJVM/publishLocal
```

Then here: `sbt test` (offline, the fixture), `sbt "Test/runMain nadia.ui.Show"`
(the same state as the terminal and as a chat draw it), and — outside sbt,
because every host here reads the console and sbt reads it first —

```
./run.sh terminal --fixture                        # this terminal, the wide layout
./run.sh chat --fixture                            # the Telegram host on the console: a number presses a button
ROZUM_MEETING_TOKEN=… ./run.sh terminal            # the live daemon (rozum meetings token issue <handle>)
  # + NADIA_SERVE_TOKEN=… [NADIA_SERVE_URL=…] [NADIA_WORKSPACES=<dir of projects>]   nadia agents
  # + ROZUM_CONTROL_SESSION=<rozum_sess cookie> [ROZUM_CONTROL_URL=…]                 UCC agents, coders
./run.sh wire --fixture | …                        # Protocol lines: Hello / Tree / Patch / Event / Close
```

## What the tests prove (`WorkspaceTest`)

- **S0** the scripted host and the terminal renderer draw the same frames; the
  fixture's rooms, mentions and transcript show.
- **S2** switching rooms keeps each room's draft; `send` clears only that room's;
  a message posted while away is unread, one posted while looking is seen;
  focusing an agent speaks in its project room, addressed.
- **the seam** the same walk in a chat behind `Wire.serve` (the narrow layout,
  `Telegram.host`, scripted presses and one said line) reaches the scripted
  host's state, and the message it sent landed in the room.
- **S2, one session** (`SharedTest`): two devices on one session — the draft
  typed on the wide one is on the narrow one, the room switched on the narrow
  one is switched on the wide one, drafts stay per room; live == recovery over
  one journal, and a recovery never speaks in a room again (found by that test:
  the refold goes through a feed whose posts already happened); the file journal
  is appended intent-first, read back whole, damage dropped.
- **S4, agents** (`AgentsTest`): an agent's page shows exactly the commands its
  source gives it (a nadia agent pause/stop, a coder stop, a room agent none),
  and they move with its state — a stale press is a notice, not a call; the
  composer TELLS an agent with an inbox and MENTIONS one in a room; the form
  starts an agent in the chosen project; a recovery replays commands without
  sending them again. A room shows who is typing (the daemon's new
  `GET /rooms/{n}/presence`).
