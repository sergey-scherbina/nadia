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
Feed.scala       where the world comes from: InMemory (in memory) · Rozum (the
                 meeting daemon's REST on :8401)
Main.scala       the hosts: terminal · chat (the Telegram host, driven from the
                 console) · wire (JSON lines on stdio, for a client in any language)
```

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
