# Sprint — what is in flight

One row per live task. The queue below it is this repo's; upstream work is in `BACKLOG.md`
under "Upstream" and is claimed in the sibling repo by its own protocol.

| task | where | state | notes |
|---|---|---|---|
| app-spec | `docs/specs/app.md`, `SPEC.md` §0/§6/§7/§9/§10 | done 2026-09-29 | the contract for the okay implementation; review it before any code |
| okay-specs | `okay:specs/` | next | four specs in `okay` (NAD-15..18 below) — each by okay's claim/worktree protocol, before `app/` code |

## Queue

- [x] **app-spec** — the okay implementation's contract. `docs/specs/app.md` + `SPEC.md` §0 (fourth
  row), §6 (parent delegates), §7 (bridge vs screen), §9 (P6/P7), §10 (models seam).
  Done-when: committed as `spec: app`, operator has read it.
- [ ] **okay-specs** — write and commit in `../okay`, one lane each, before any `app/` code:
  NAD-15 agent hierarchy (`okay-agent`), NAD-16 models seam (`okay-llm`), NAD-17 channel identity +
  roster (`okay-security`), NAD-18 throttled live card + commands table (`okay-telegram`). How: okay's
  `multi-agent` protocol — claim in `.work/active/<slug>.claim`, worktree `../okay-wt-<slug>`,
  spec in `okay:specs/<slug>.md`, its board files under `sprint.d/`. Gotcha: okay's main checkout is
  for reading and ff-merges only; never `git add -A` there. Done-when: four `spec:` commits on
  okay master, each naming this file.
- [ ] **app-build** — `build.sbt` at the root, `okay/` submodule, `app/` project depending on
  `okayTelegramJVM`, `okayAgentJVM`, `okayActorJVM`, `okayPersistJVM`, `okaySecurityJVM`,
  `okayUiJVM`; one munit test that compiles against them. Pattern: `../okay-chat/build.sbt`.
  Gotcha: `okay-telegram` and `okay-desktop` are not in `~/.ivy2/local` — a source dependency is
  the only road. Done-when: `sbt app/test` green with an empty test.
- [ ] **app-screens** — the Ui program (`docs/specs/app.md` "screens"), tested as values: the same
  events → the same tree for Telegram and terminal. Done-when: the "Same program, every host"
  behavior item is checked.
- [ ] **app-fleet** — six tools + `delegate` over `okay-agent`'s `Fleet` (NAD-15); agent records
  and turns in the state log. Done-when: the "Agents" and "State" behavior items are checked.
- [ ] **app-access** — owner + roster over NAD-17; policy consulted in `update`. Done-when: the
  "Access" items are checked.
- [ ] **app-models** — the Models screen over NAD-16, rozum adapter live against a local gateway.
  Done-when: the "Models" items are checked against `rozum gateway status`.
- [ ] **app-live** — the bot against a real token and a real rozum: the acceptance run in
  `SPEC.md` §9 P6. Needs from the operator: a bot token (`NADIA_TELEGRAM_TOKEN`) and the owner's
  user id (`NADIA_TELEGRAM_OWNER`).
