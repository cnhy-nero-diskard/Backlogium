# Design — Establish the data-source seam

## Context

The app is offline-first with one data source. Steam and HowLongToBeat are read by `data/`, written
into Room, and observed by ViewModels as Flows. `:gamification` computes XP from Room-sourced inputs
and `GamificationUpdater` writes the results back.

Everything that writes Steam or HLTB data into Room is already in one layer:

- `SteamSyncWorker` — games, sessions, daily progress, and profile aggregates
- `AchievementRepository.syncGame` — achievements
- `HltbRepository.query` — completion lengths
- `PlaytimeBackfillUseCase` — the one-time history import

That is a small, well-bounded write surface. The read surface is the problem: several repositories
return Room entities, so ViewModels are coupled to the storage schema rather than to a repository
contract.

## Goals / Non-Goals

**Goals:**
- The UI depends on repository contracts, not on storage types.
- Adding a second data source later requires no UI changes.
- No behavior change, no new dependencies, no Firebase code.

**Non-Goals:**
- Firebase code, product flavors, repository interfaces, a cloud git branch, moving the engine
  server-side, restructuring Room entities.

## Decisions

- **Domain models at the repository boundary; Room entities stay inside `data/`.** Each repository
  returns its own plain data types. Enforcement is simple and checkable: no import of
  `data.local.entity.*` anywhere under `ui/`.
  *Why:* a second data source can satisfy a contract of plain Kotlin types. It cannot satisfy one
  expressed in `@Entity` classes without pretending to be Room. *Why now rather than later:* the cost
  is per-screen and roughly constant, so paying it while touching each screen anyway is far cheaper
  than a dedicated retrofit across eight screens later. It is also just better structure regardless of
  whether cloud sync ever happens, which is what makes it safe to do during feature work.

- **Settings access moves behind a repository.** `SettingsDataStore` stops being injected into
  ViewModels.
  *Why:* same boundary, same reason. It is also the smaller half of the job — two ViewModels.

- **No repository interfaces yet.** Concrete classes returning domain models are sufficient.
  *Why:* an interface with one implementor is speculative structure that has to be maintained without
  buying anything. The valuable part is the *return types*, not the abstraction. When a Firestore
  implementation exists, extracting an interface from a repository that already returns domain models
  is mechanical.

- **Product flavors, not git branches, when the time comes.** A `local`/`cloud` flavor split with
  per-flavor Hilt modules, both built in CI.
  *Why:* two long-lived branches mean every feature is implemented twice or cherry-picked, and nothing
  tells you when one variant has broken — which directly contradicts the goal of features working
  across both. Flavors make the compiler enforce it: one `LibraryScreen`, two bindings, both built
  every time. *Not now:* a flavor wrapping a single implementation is overhead with no payoff.

- **When cloud sync arrives, the boundary should be raw data, not computed results.** The backend
  polls Steam and writes games/sessions/achievements; the app mirrors those into Room; the existing
  engine computes XP on-device, unchanged.
  *Why:* the alternative — the server computes XP — means either running Kotlin server-side (Cloud Run
  or a JVM Cloud Function, not the standard Node Functions path) or reimplementing the taper, level
  curve, and rarity tiers in TypeScript. Two implementations of the XP rules that must agree forever is
  the most expensive mistake available here. Mirroring raw data keeps one engine, keeps Room as the
  local cache in both modes, keeps the UI byte-identical, and preserves the `app-ui` requirement that
  every screen renders from local state offline.
  *Recorded here, not decided by this change* — but it is the decision that determines how much the
  seam is worth, so it belongs on the record.

- **Two topologies are possible later, and only one needs the seam.** Worth distinguishing, because the
  motivating feature (the Phase 6 OBS overlay) may not need the expensive one:
  - *App pushes up:* the app keeps polling Steam and writes derived state to Firestore for the overlay
    to read. Purely additive — one more writer, nothing swapped, no flavors, credentials stay on-device.
  - *Backend polls, app reads:* buys tracking that continues when the phone is asleep or Android has
    killed the workers — a real problem for this app. This is the one that needs the seam and the
    flavors.
  *Consequence worth weighing:* backend polling means the Steam API key lives server-side.
  `EncryptedCredentialStore` currently keeps it encrypted on-device and it never leaves. That is a
  deliberate property of the current design, and moving polling server-side gives it up.

## Risks / Trade-offs

- **Mapping boilerplate.** Each repository grows an entity → domain mapper. Mildly tedious, and the
  honest cost of the boundary. Mitigated by doing it per-screen rather than as one sweep.
- **Churn against in-flight changes.** Eight UI changes are already proposed on this branch, several
  touching the same ViewModels. Doing this cleanup *within* those changes rather than as a competing
  refactor avoids conflicts entirely.
- **Speculative generality, if overdone.** The guard is the Non-goals list: domain models yes,
  interfaces and flavors no. Stop at the return types.
- **The rule decaying.** A boundary nobody wrote down gets crossed again in six months. Hence the
  README note; a lint rule or a module-level dependency restriction would be stronger, and is worth
  considering if it does decay.

## Migration Plan

None. No schema change, no persistence change, no behavior change. Purely internal types.

## Open Questions

- Would splitting `:data` and `:ui` into separate Gradle modules be worth it? That would let the build
  itself forbid `ui/` from seeing Room, rather than relying on discipline. Real benefit, real
  restructuring cost — worth revisiting if the rule proves hard to hold.
- Which topology first: push-up for the overlay, or backend polling for reliability? Not a question
  this change needs answered, but the answer determines whether the flavor split is ever needed.
