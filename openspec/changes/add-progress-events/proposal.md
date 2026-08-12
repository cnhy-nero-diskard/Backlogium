# Progress Events

## Why

The engine computes progress; nothing in the app can tell when progress *happened*. `PlayerProfile`
stores level, current streak, and longest streak as state, and every surface observes that state.
A player who levels up while the app is closed opens it to a level that, as far as the UI can tell,
was always what it is. There is no moment to mark, because a moment is a transition and the app
only ever sees positions.

Today that gap is worked around rather than closed. `domain/StreakMilestone.kt` derives a
"celebrate this" answer from the streak value alone, which makes the celebration a pure function of
state — so it re-fires every time Home recomposes on day 7. It has no memory of having already
been seen, because there is nowhere to keep that memory.

The gap is about to get expensive. A streak-broken overlay, a level-up flourish, and the planned
haptic-feedback capability are all consumers of the same missing thing: *a derived value changed,
here is what it was, here is what it is, and it has not yet been shown to the player.*

There is a second problem, and it is the one that makes this non-trivial. Not every change to a
derived value is something that happened *to the player*. `GamificationUpdater.persist()` is
reached by four callers, and only one of them represents earned progress:

| Caller | What actually happened |
|---|---|
| `SteamSyncWorker` | the player played games |
| `UpdateRuleConfigUseCase` | the player edited `xpPerMinute` |
| `PlaytimeBackfillUseCase` (apply / clear) | Steam history was imported or removed |
| `BackupMergeEngine` | a snapshot was restored |

A backfill import can raise the level by twenty in one write. Celebrating that is not a rounding
error in the UX — it is the feature actively lying about what the player did. Any event stream
built without provenance is wrong on its first real use.

## What Changes

- **`GamificationUpdater.persist()` takes a required `RecomputeSource`.** Every write of derived
  values declares whether its changes were earned. This is deliberately a required parameter and
  deliberately on `persist` rather than `recompute`, because `BackupMergeEngine` calls `persist`
  directly — a future caller that bypasses `recompute` still cannot compile without answering the
  question. **BREAKING** for every existing call site, which is the point.
- **A `ProgressEvent` stream**, derived at persist time by diffing the stored profile against the
  values about to replace it. Events: `LevelUp(from, to)`, `QuestMet(date)`,
  `StreakMilestone(days)`, `StreakBroken(previousLength)`.
- **Events are emitted from `persist`, never from `compute`.** The settings confirmation dialog runs
  the real computation against a candidate `RuleConfig` and discards it; emitting from `compute`
  would fire a phantom level-up every time a player opened that dialog. The existing preview/commit
  split is what keeps the stream honest.
- **Pending events are high-water marks in DataStore**, not rows in a new table: the highest level
  celebrated, the highest streak milestone celebrated, the date of the last celebrated quest, and
  the streak length of the last shown break. A consumer presents the event and advances the mark.
- **Multi-level jumps collapse into one event.** Crossing three levels in a single sync yields
  `LevelUp(4, 7)`, not three events. The mark records where the player is, not every threshold
  passed on the way.
- **`domain/StreakMilestone.kt` folds into the stream**, gaining the "already shown" memory it
  cannot currently have. The interval rule itself is preserved.
- **One visible consumer ships with it**: a one-time "your streak was broken" overlay on Home,
  shown once per break and never again.

## Capabilities

### New Capabilities
- `progress-events`: the derivation, provenance rules, and once-only delivery of player-facing
  progress transitions — which recompute sources may produce events, what each event carries, how
  a jump across several thresholds collapses, and the guarantee that a consumed event never
  re-fires across process death.

### Modified Capabilities
- `app-ui`: Home gains the one-time streak-broken overlay.

The `gamification` spec is deliberately **not** modified. It scopes itself to the pure engine —
"it performs no I/O, no networking, and no persistence — callers supply inputs and persist
outputs." Provenance is a property of the persisting caller, not of the engine, so the requirement
belongs in `progress-events` rather than widening a spec that earns its clarity from that
boundary.

## Impact

- **Affected code (new):** `domain/ProgressEvent.kt` (the sealed event vocabulary and
  `RecomputeSource`); `domain/ProgressEventDetector.kt` (the pure diff — old profile + new result +
  source → events, JVM-testable with no Room); a marks accessor on `SettingsDataStore`; a
  `ProgressEventRepository` exposing the pending stream and the acknowledge call; the overlay
  composable.
- **Affected code (modified):** `GamificationUpdater.persist` (emit after write);
  `SteamSyncWorker`, `UpdateRuleConfigUseCase`, `PlaytimeBackfillUseCase` (both call sites),
  `BackupMergeEngine` (declare source); `HomeViewModel` and `HomeScreen` (observe and acknowledge);
  `domain/StreakMilestone.kt` (retained as the interval rule, no longer the celebration trigger).
- **No Room migration and no new table.** Four preference keys carry the whole mechanism. An events
  table would buy ordering and history that no consumer needs, at the cost of a schema version.
- **Nothing derives new values.** The detector reads what the engine already computed and compares
  it to what was already stored. The on-device engine remains the sole author of derived values.
- **Offline and cloud-free.** The stream is local state read from Room and DataStore; nothing here
  reads or requires Firestore.
- **Deliberately excludes haptics.** The follow-up `add-haptic-feedback` change consumes this
  stream as its earned-moment source; splitting keeps the event vocabulary reviewable against the
  gamification spec rather than arriving as a subcomponent of a UI-polish change.
