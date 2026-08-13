## 1. Durable pending-transition record

- [x] 1.1 Add `PendingTransition(source, previousLevel, previousStreak, previousTodayQuestMet, evaluationDate)` and a `pendingTransition: PendingTransition?` field to `domain/ProgressMarks.kt`
- [x] 1.2 Add dedicated preference keys for `PendingTransition` to `SettingsDataStore` (grouped with the existing progress-event keys) and encode/decode it in `progressMarksFlow`/the write path
- [x] 1.3 Add `ProgressMarksStore.update(transform: (ProgressMarks) -> ProgressMarks): ProgressMarks` to the interface; implement it on `InMemoryProgressMarksStore` via an atomic `MutableStateFlow` update
- [x] 1.4 Implement `SettingsDataStore.updateProgressMarks(transform)` as a single `context.dataStore.edit {}` block (decode → transform → encode) and wire `DataStoreProgressMarksStore.update` to it; reimplement `writeProgressMarks` in terms of it if that removes duplication
- [x] 1.5 Add a shared `suspend fun resolvePendingTransition(marksStore, profileDao, dailyProgressDao): ProgressMarks` (new file or alongside `ProgressEventDetector`): cheap null-check read, then Room reads for current state, then one `marksStore.update {}` that re-runs `ProgressEventDetector.detect()` against the recorded previous state and clears `pendingTransition`, re-checking `pendingTransition != null` inside the transform
- [x] 1.6 Rewrite `GamificationUpdater.persist()`: call `resolvePendingTransition` first; write the pending-transition record via `update {}` before the Room writes; keep the Room writes as-is; finalize via `update {}` that computes `detect()` from the transform's live `marks` parameter (never a pre-fetched snapshot) and clears `pendingTransition`
- [x] 1.7 Call `resolvePendingTransition` at the start of `ProgressEventRepository.pendingEvents` (wrap the existing `combine` in a `flow { resolvePendingTransition(...); emitAll(...) }`)

## 2. Quest delivery across day rollover

- [x] 2.1 Change `ProgressEventRepository.pendingEvents`'s `QuestMet` derivation from a `time.today()` lookup to a scan of `dailyProgressDao.observeAll()` for the earliest `questMet == true` row with a parsed date after `marks.lastQuestCelebratedDate` (or any met date if the mark is null)
- [x] 2.2 Confirm `acknowledge()`'s `QuestMet` branch still only advances the mark to `event.date` via `maxOfDate`, so acknowledging an older pending day never regresses past a newer one

## 3. Atomic acknowledgement

- [x] 3.1 Rewrite `ProgressEventRepository.acknowledge(event)` to use `marksStore.update { marks -> ... }` with the existing per-event-type `when` branches computed from the transform's live `marks` parameter, removing the separate `read()`/`write()` calls

## 4. Home streak-milestone migration

- [x] 4.1 Add `pendingStreakMilestone: ProgressEvent.StreakMilestone?` to `HomeUiState`, populated in `HomeViewModel.uiState` the same way `pendingStreakBreak` is (`pendingEvents.filterIsInstance<...>().firstOrNull()`)
- [x] 4.2 Remove `HomeScreen`'s `lastStreak`/`playStreakMilestone`/`isStreakMilestone(...)` `LaunchedEffect` and its `isStreakMilestone` import
- [x] 4.3 Drive the Streak card's `CelebrationAnimation` from `state.pendingStreakMilestone != null`, calling `viewModel.acknowledgeProgressEvent(milestone)` from its `onFinished`
- [x] 4.4 Update `domain/StreakMilestone.kt`'s KDoc: the legacy Home milestone trigger is gone; only the interval rule remains, consumed by the detector and the repository

## 5. Tests

- [x] 5.1 `GamificationUpdater`/detector test: a `RULE_CHANGE` (and `BACKFILL`, and `RESTORE`) that writes Room but never reaches the marks finalize step (simulate via a fake `ProgressMarksStore` that fails/never-completes the second `update`, or by driving `resolvePendingTransition` directly against a marks store seeded with a `pendingTransition` and a Room state that already reflects the write) produces no phantom event once resolved
- [x] 5.2 `ProgressEventRepositoryTest`: a quest met yesterday and never acknowledged is still returned by `pendingEvents` today (fake `TimeProvider` advanced by a day between write and read)
- [x] 5.3 `ProgressEventRepositoryTest`: two unacknowledged quest days both eventually deliver — acknowledging the earlier one reveals the later one
- [x] 5.4 Concurrency test: `acknowledge()` running concurrently with a recompute's marks finalize does not resurrect the acknowledged event and does not lose the acknowledgement (drive both through the same `InMemoryProgressMarksStore`/`DataStoreProgressMarksStore` instance)
- [x] 5.5 Test: a stale finalize write (computed from an old `marks` snapshot) cannot restore a `pendingStreakBreak`, level mark, milestone mark, or quest mark already cleared by a concurrent acknowledgement, given the `update {}` transform always uses its live parameter
- [x] 5.6 `HomeViewModel`/`ProgressEventRepository` test: a milestone earned while no `HomeViewModel`/Home consumer exists is present in `pendingEvents`/`uiState.pendingStreakMilestone` the next time it's collected
- [x] 5.7 Test: acknowledging a `StreakMilestone` prevents it from reappearing after `ProgressEventRepository`/`HomeViewModel` recreation sharing the same underlying store (mirrors the existing `StreakBroken` recreation test)
- [x] 5.8 Confirm existing detector/provenance/priority/first-seed tests (`ProgressEventDetectorTest`, `GamificationProgressEventsTest`) still pass unmodified in behavior, updating only what the new `persist()`/store shape mechanically requires

## 6. Verification

- [x] 6.1 Run `./gradlew :gamification:test :app:testDebugUnitTest`
- [x] 6.2 Run `./gradlew assembleDebug`
- [x] 6.3 Re-run the repository-boundary grep from `CLAUDE.md` to confirm no new breach: `grep -rn "^import .*\(data\.local\.entity\|SettingsDataStore\)" app/src/main/java/com/example/backlogium/ui/ --exclude-dir=diagnostics`
- [ ] 6.4 Manually verify on device: earn a streak milestone via a background sync while Home isn't open, confirm the animation plays on next open and does not replay after kill/relaunch
