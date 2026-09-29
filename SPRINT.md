# Sprint — what is in flight

One row per live task. The queue below it is this repo's; upstream work is in `BACKLOG.md`
under "Upstream" and is claimed in the sibling repo by its own protocol.

| task | where | state | notes |
|---|---|---|---|
| app-spec | `docs/specs/app.md`, `SPEC.md` §0/§6/§7/§9/§10 | done 2026-09-29 | the contract for the okay implementation; review it before any code |
| okay-specs | `okay:specs/` | done 2026-09-29 | landed as okay `ae49fb7ae` (one lane, four specs); implementation lanes filed in okay `backlog.d` |
| app-build | `build.sbt`, `okay/` submodule, `app/` | done 2026-09-29 | submodule pinned at okay `ae49fb7ae`; `sbt app/test` green (1 test, 0 warnings) |
| okay-impl | `okay:` four lanes | done 2026-09-29 | telegram-live `e3fd797f9`, identity-roster `0c21077e2`, llm-models `2f20f5a03`, agent-fleet `654a4f98b` (+ test fix lane `fleet-test-race`) |
| app-serve | `app/` | in progress | NadiaRunner over Fleet (asks before writes), `Main serve` = store over the wire + the commands fold with the roster as policy; NAD-19..21 landed in okay (4bb7aec87, 526054610, ed81f9064) |

## Queue

- [x] **app-spec** — the okay implementation's contract. `docs/specs/app.md` + `SPEC.md` §0 (fourth
  row), §6 (parent delegates), §7 (bridge vs screen), §9 (P6/P7), §10 (models seam).
  Done-when: committed as `spec: app`, operator has read it.
- [x] **okay-specs** — landed 2026-09-29 as okay `ae49fb7ae` (one lane `nadia-platform`, not four:
  spec-only commits share nothing, so one claim was the honest unit). `okay:specs/agent-fleet.md`,
  `llm-models.md`, `identity-roster.md`, `telegram-live.md`; each names its implementation lane, and
  the four are filed in okay `backlog.d` (`okay-agent-okay-intent/agent-fleet`, `…/llm-models`,
  `okay-security/identity-roster`, `okay-ui/telegram-live`). Gate: `affected master staged` GREEN.
  Originally planned as:
  NAD-15 agent hierarchy (`okay-agent`), NAD-16 models seam (`okay-llm`), NAD-17 channel identity +
  roster (`okay-security`), NAD-18 throttled live card + commands table (`okay-telegram`). How: okay's
  `multi-agent` protocol — claim in `.work/active/<slug>.claim`, worktree `../okay-wt-<slug>`,
  spec in `okay:specs/<slug>.md`, its board files under `sprint.d/`. Gotcha: okay's main checkout is
  for reading and ff-merges only; never `git add -A` there. Done-when: four `spec:` commits on
  okay master, each naming this file.
- [x] **app-build** (2026-09-29: `sbt app/test` green, 1 test, 0 warnings; submodule at okay `ae49fb7ae`) — `build.sbt` at the root, `okay/` submodule, `app/` project depending on
  `okayTelegramJVM`, `okayAgentJVM`, `okayActorJVM`, `okayPersistJVM`, `okaySecurityJVM`,
  `okayUiJVM`; one munit test that compiles against them. Pattern: `../okay-chat/build.sbt`.
  Gotcha: `okay-telegram` and `okay-desktop` are not in `~/.ivy2/local` — a source dependency is
  the only road. Done-when: `sbt app/test` green with an empty test.
- [x] **app-screens** — RESCOPED 2026-09-29: the screens are the workspace UI's (`ui/`, the other
  session; `BACKLOG.md` NAD-14). A first cut of nadia's own screens (App.scala, Contexts.scala,
  TestScreens.scala, uncompiled) was set aside, not committed; the free-text rule, the viewer rule
  and the browse jail it encoded are requirements in `docs/specs/app.md` for `ui/` to meet.
- [~] **app-serve** — `NadiaRunner` (six tools + `delegate` + gate over `Fleet`), `Main` as the
  service: the store, the fleet, the roster, the models seam, `Wire.Server` for `ui/`. Then the
  okay lanes NAD-19..21 (events, commands, approvals). Done-when: `sbt app/test` green with a
  scripted-model run through NadiaRunner; the UI tails the `agents` topic over the wire.
- [ ] **app-access** — owner + roster over NAD-17; policy consulted in `update`. Done-when: the
  "Access" items are checked.
- [ ] **app-models** — the Models screen over NAD-16, rozum adapter live against a local gateway.
  Done-when: the "Models" items are checked against `rozum gateway status`.
- [ ] **app-live** — the bot against a real token and a real rozum: the acceptance run in
  `SPEC.md` §9 P6. Needs from the operator: a bot token (`NADIA_TELEGRAM_TOKEN`) and the owner's
  user id (`NADIA_TELEGRAM_OWNER`).
