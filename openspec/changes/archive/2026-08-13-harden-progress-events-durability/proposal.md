## Why

`add-progress-events` shipped a durable-sounding delivery model — DataStore high-water marks
diffed against Room — but the durability only holds within a single successful write. Four gaps
survived review and now need closing before more Home celebrations build on the same pipeline:

1. `GamificationUpdater.persist()` writes Room and then `ProgressMarks` as two uncoordinated
   commits. A crash between them lets a non-earned recompute (`RULE_CHANGE`/`BACKFILL`/`RESTORE`)
   look like earned progress on the next read, and permanently loses `StreakBroken`'s
   `pendingStreakBreak` if the crash lands after the earned Room write — the one signal the
   original design claimed was always re-derivable.
2. `ProgressEventRepository` reconstructs `QuestMet` from `time.today()` only, so a quest a
   background sync completed on day N becomes unrecoverable the moment the app is first opened on
   day N+1.
3. `ProgressMarksStore` exposes only whole-object `read()`/`write()`. `persist()`'s mark write and
   `acknowledge()`'s mark write are independent stale-snapshot read-modify-write cycles that can
   race and silently clobber each other.
4. Home's streak-milestone animation still fires from transient `remember { lastStreak }` /
   `isStreakMilestone(currentStreak)` Compose state — exactly the non-durable pattern
   `add-progress-events` was built to replace for `StreakBroken` — so it neither survives process
   death nor can be deduplicated against acknowledgement.

## What Changes

- Add a durable pending-transition record to `ProgressMarks`, written before the Room write in
  `GamificationUpdater.persist()` and cleared only after the detector's result is written. A
  shared recovery routine resolves any leftover record (using the durable previous-state snapshot
  plus Room's current truth) before `persist()` starts new work and before
  `ProgressEventRepository` derives pending events, so a crash mid-persist can never surface a
  phantom event and can never lose a genuine `StreakBroken`.
- Replace `QuestMet` reconstruction's `time.today()` dependency with a scan for the earliest
  unacknowledged quest-met date past `lastQuestCelebratedDate`, so a quest earned on any past day
  remains deliverable, and multiple pending days deliver oldest-first rather than one hiding
  another.
- Add `ProgressMarksStore.update(transform)`, a single atomic edit backed by one DataStore
  `edit {}` transaction in production. Reimplement `GamificationUpdater.persist()`'s mark write and
  `ProgressEventRepository.acknowledge()` on top of it, always computing the new value from the
  transform's live parameter rather than an outer snapshot, so acknowledgement and recompute can no
  longer race into a lost update.
- Migrate Home's streak-milestone celebration onto `ProgressEvent.StreakMilestone`, following the
  existing `StreakBroken` pattern: a dedicated pending slot on `HomeUiState`, the celebration
  animation triggered by its presence, and acknowledgement only after the animation completes.
  `LevelUp`'s existing Compose-state trigger is unchanged — out of scope for this change.
- Add regression tests for interrupted non-earned persistence, cross-day quest delivery,
  acknowledge/recompute races, stale-write streak-break resurrection, and milestone delivery/replay
  across Home recomposition and process death.

## Capabilities

### New Capabilities

(none — this hardens the existing pipeline rather than introducing a new one)

### Modified Capabilities

- `progress-events`: the delivery model changes from "high-water marks are always re-derivable"
  to a documented durable pending-transition record; `QuestMet` delivery changes from
  "reconstructed against today" to "reconstructed against the earliest unacknowledged date";
  acknowledgement changes from a snapshot read-modify-write to an atomic transform.
- `app-ui`: the streak-milestone Home animation's trigger changes from transient Compose state to
  the durable `ProgressEvent.StreakMilestone` pending-event pattern already documented for the
  streak-broken overlay.

## Impact

- `app/src/main/java/com/example/backlogium/domain/ProgressMarks.kt`,
  `ProgressMarksStore.kt`, `ProgressEventDetector.kt`, `GamificationUpdater.kt`
- `app/src/main/java/com/example/backlogium/data/local/SettingsDataStore.kt`,
  `data/repo/DataStoreProgressMarksStore.kt`, `data/repo/ProgressEventRepository.kt`
- `app/src/main/java/com/example/backlogium/ui/home/HomeViewModel.kt`, `HomeRoute.kt`,
  `HomeScreen.kt`
- New/updated JVM unit tests under `app/src/test/java/com/example/backlogium/domain/` and
  `data/repo/`; no Room migration (DataStore preference keys only); no change to `:gamification`.
