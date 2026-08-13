## 1. Event vocabulary and provenance

- [x] 1.1 Add `domain/RecomputeSource.kt`: an enum with `SYNC`, `RULE_CHANGE`, `BACKFILL`, and `RESTORE`, documenting that only `SYNC` means the changes were earned through play
- [x] 1.2 Add `domain/ProgressEvent.kt`: a sealed interface with `LevelUp(from, to)`, `QuestMet(date)`, `StreakMilestone(days)`, and `StreakBroken(previousLength)`, plus the documented presentation priority order
- [x] 1.3 Add a `priority` ordering helper over `ProgressEvent` and a comparator that sorts a pending list into presentation order

## 2. Pure detector

- [x] 2.1 Add `domain/ProgressEventDetector.kt`: a pure function taking the delivery marks, the previously stored level/streak/quest state, the newly computed values, the recompute source, and today, returning `List<ProgressEvent>` plus the marks to write
- [x] 2.2 Implement earned detection for `SYNC`: level rise, streak crossing a milestone interval, today's quest first met, and streak falling to zero from a positive value
- [x] 2.3 Implement threshold collapse: one `LevelUp` carrying the mark and the new level; one `StreakMilestone` carrying the highest interval multiple reached
- [x] 2.4 Implement non-earned reseeding: for every source other than `SYNC`, emit nothing and set all marks to the newly computed values, including downward
- [x] 2.5 Implement first-persist seeding: when no profile previously existed, emit nothing and seed the marks regardless of source
- [x] 2.6 Reuse `isStreakMilestone` / `STREAK_MILESTONE_INTERVAL_DAYS` from `domain/StreakMilestone.kt` as the interval rule; update its KDoc to record that it is no longer the celebration trigger
- [x] 2.7 Add JVM unit tests for the detector covering every scenario in `specs/progress-events/spec.md`, as a table of transitions

## 3. Delivery marks

- [x] 3.1 Add four preference keys to `SettingsDataStore`: `last_celebrated_level`, `last_celebrated_streak_milestone`, `last_quest_celebrated_date`, `last_streak_broken_date`, grouped and documented as presentation state rather than user settings
- [x] 3.2 Expose a `ProgressMarks` domain model and a suspend read plus a suspend write on `SettingsDataStore`, keeping the storage type inside `data/`
- [x] 3.3 Add `data/repo/ProgressEventRepository.kt` exposing pending events as a `Flow<List<ProgressEvent>>` derived from the marks and the stored profile, and an `acknowledge(event)` that advances only that event's mark

## 4. Wire provenance through persist

- [x] 4.1 Add a required `source: RecomputeSource` parameter to `GamificationUpdater.persist`, and thread it through `recompute`
- [x] 4.2 Run the detector inside `persist` after the profile upsert succeeds, then write the returned marks
- [x] 4.3 Confirm `compute` remains free of any emission or mark write, and add a test asserting that computing a candidate config produces no events and no mark change
- [x] 4.4 Update `SteamSyncWorker` to declare `SYNC`
- [x] 4.5 Update `UpdateRuleConfigUseCase` to declare `RULE_CHANGE`
- [x] 4.6 Update both `PlaytimeBackfillUseCase` call sites to declare `BACKFILL`
- [x] 4.7 Update `BackupMergeEngine`'s direct `persist` call to declare `RESTORE`
- [x] 4.8 Add tests asserting each source's emit-or-reseed behaviour against a fake profile DAO

## 5. Streak-broken overlay on Home

- [x] 5.1 Expose the highest-priority pending event on `HomeViewModel` as ui state, mapping domain models only
- [x] 5.2 Add the streak-broken overlay composable to `ui/home/`, naming the lost streak length, dismissible, non-blocking
- [x] 5.3 Honour `rememberReducedMotion()` so the overlay appears without motion when animations are disabled
- [x] 5.4 Acknowledge the event on dismissal, after presentation, so a crash mid-present re-shows rather than loses it
- [x] 5.5 Suppress the overlay for a player who has never held a streak

## 6. Verification

- [x] 6.1 Run `./gradlew :gamification:test :app:testDebugUnitTest`
- [x] 6.2 Run `./gradlew assembleDebug`
- [x] 6.3 Verify the repository-boundary invariant still passes: `grep -rn "^import .*\(data\.local\.entity\|SettingsDataStore\)" app/src/main/java/com/example/backlogium/ui/ --exclude-dir=diagnostics` reports no new breaches
- [x] 6.4 Manually verify on device: break a streak, confirm the overlay appears once, kill and relaunch, confirm it does not reappear
- [x] 6.5 Manually verify no phantom celebration: open the rule-change dialog with a raised XP rate, cancel, confirm nothing fires
