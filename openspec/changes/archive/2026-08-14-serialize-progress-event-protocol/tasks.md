## 1. Process-wide protocol coordinator

- [x] 1.1 Add `domain/ProgressTransitionCoordinator.kt`: `@Singleton` class with a private `Mutex` and `suspend fun <T> withTransition(block: suspend () -> T): T`, documenting that it is non-reentrant and only meaningful as a single instance
- [x] 1.2 Split `resolvePendingTransition` into a public wrapper taking the coordinator and an `internal suspend fun resolvePendingTransitionWithinProtocol(...)` holding the existing body
- [x] 1.3 Wrap `GamificationUpdater.persist()` in `transitionCoordinator.withTransition { … }`, moving the body to `persistWithinProtocol`, and switch its first phase to `resolvePendingTransitionWithinProtocol` so the non-reentrant mutex is not re-acquired
- [x] 1.4 Add a `transitionCoordinator` constructor parameter to `GamificationUpdater` (defaulted, like `progressMarksStore`) and a required one to `ProgressEventRepository`; no DI module change needed since the coordinator is `@Inject`/`@Singleton`
- [x] 1.5 Resolve this call's own write-ahead record in a `NonCancellable` `catch` inside `persistWithinProtocol` before rethrowing, so a caught failure cannot leave derivation suppressed for the process lifetime

## 2. In-flight transitions are not derivable

- [x] 2.1 Gate `ProgressEventRepository.pendingEvents`' reconstructed `LevelUp`/`StreakMilestone` comparisons on `marks.pendingTransition == null`, leaving the durable `pendingQuestDates`/`pendingStreakBreak` slots unconditional, with the reasoning recorded in a comment
- [x] 2.2 Keep the one-shot `resolvePendingTransition` at flow start (now coordinator-acquiring) so an abandoned record from a dead process cannot suppress derivation indefinitely

## 3. Durable pending quest identity

- [x] 3.1 Add `pendingQuestDates: Set<LocalDate> = emptySet()` to `ProgressMarks`, documenting the pending-vs-high-water split
- [x] 3.2 Add `stringSetPreferencesKey("pending_quest_dates")` to `SettingsDataStore`, encode it (removing the key when empty) and decode it oldest-first, dropping unparseable entries
- [x] 3.3 Add a shared `laterOf(stored, candidate)` helper and use it for every quest high-water advance

## 4. Provenance applied at the quest mutation

- [x] 4.1 Add `ProgressEventDetector.newlyEarnedQuestDate(...)`: edge-triggered on `!previous.todayQuestMet && current.todayQuestMet`, skipping a date already pending or at/below `lastQuestCelebratedDate`
- [x] 4.2 Under `SYNC`, add the newly earned date to `pendingQuestDates` and emit `QuestMet(today)`; leave existing pending dates intact
- [x] 4.3 In `reseed` (non-earned sources), add no pending date, cancel none, and advance `lastQuestCelebratedDate` monotonically instead of resetting it to `today.takeIf { current.todayQuestMet }`
- [x] 4.4 In `seed` (first initialization), set `pendingQuestDates` explicitly empty so pre-existing met rows are not celebrated
- [x] 4.5 Rewrite `ProgressEventRepository`'s `QuestMet` derivation as `marks.pendingQuestDates.minOrNull()`, deleting `earliestUnacknowledgedQuestDate` and the now-unused `DailyProgress`/`LocalDate` imports

## 5. Explicit, idempotent quest acknowledgement

- [x] 5.1 Change `acknowledge`'s `QuestMet` branch to remove exactly `event.date` from `pendingQuestDates` and advance `lastQuestCelebratedDate` via `laterOf`
- [x] 5.2 Confirm the `combine` no longer needs `dailyProgressDao.observeAll()`, and that `dailyProgressDao` is still required for recovery

## 6. Tests

- [x] 6.1 Add `ProgressEventTestSupport.kt`: `GatedProgressMarksStore` (hook after each atomic update), `GatedPlayerProfileDao` (hook after the Room write), `WriteAheadGate` (one-shot suspend at the WAL phase), and `testUpdater`/`testRepository`/`progressResult` helpers that share one coordinator
- [x] 6.2 Back `FakePlayerProfileDao` with a `MutableStateFlow` so `observe()` reflects writes that land after a consumer subscribed
- [x] 6.3 `ProgressTransitionProtocolTest`: recovery starting while a persist is suspended after its WAL write but before its Room write does not clear the live record, and the earned transition is still delivered by the persist
- [x] 6.4 `ProgressTransitionProtocolTest`: a consumer already collecting sees no phantom event while a `RULE_CHANGE` is suspended between its Room write and its finalize, nor after it
- [x] 6.5 `ProgressTransitionProtocolTest`: overlapping `RULE_CHANGE` and `SYNC` persists — the second cannot enter the protocol, neither record is clobbered, and the final baseline is the rule change's 24 with the sync's earned 24→30 measured against it
- [x] 6.6 `ProgressTransitionProtocolTest`: `pendingEvents` emits nothing reconstructed while `pendingTransition` is present and resumes when it clears
- [x] 6.7 `QuestEventProvenanceTest`: a quest earned by `SYNC` stays pending across date rollover and is still delivered with yesterday's date
- [x] 6.8 `QuestEventProvenanceTest`: a second sync on the same day adds no second pending date
- [x] 6.9 `QuestEventProvenanceTest`: every non-earned source that recomputes a historical row to met creates no pending quest date and produces no quest event
- [x] 6.10 `QuestEventProvenanceTest`: an acknowledged quest does not reappear after a non-earned recompute on a day whose own quest is unmet
- [x] 6.11 `QuestEventProvenanceTest`: first initialization on an account with historical met quests produces no `QuestMet` events and seeds an empty pending set
- [x] 6.12 `ProgressEventRepositoryTest`: two earned pending dates deliver oldest-first and independently; acknowledging one (including out of order, including twice) leaves the other pending; historical met rows with no pending date deliver nothing
- [x] 6.13 `ProgressEventDetectorTest`: pending-set assertions for seed, earned edge trigger, already-met no-op, earlier dates surviving a later recompute, non-earned neither adding nor cancelling, and non-earned never regressing `lastQuestCelebratedDate`
- [x] 6.14 Update `PendingTransitionRecoveryTest` and `ProgressEventRepositoryTest` for the new coordinator-taking signatures, keeping their existing assertions

## 7. Verification

- [x] 7.1 Run `./gradlew :gamification:test :app:testDebugUnitTest`
- [x] 7.2 Run `./gradlew assembleDebug`
- [x] 7.3 Mutation-check the new protocol tests: with `persist()`'s `withTransition` removed, the two race tests fail; with the `pendingTransition == null` derivation guard removed, the two suppression tests fail
- [x] 7.4 Re-run the repository-boundary grep from `CLAUDE.md` and confirm only the documented `HomeViewModel` breach is reported
- [x] 7.5 Manually verify on device: earn a quest via a background sync, leave it unacknowledged past midnight, confirm it presents with the earned date on next open and does not replay after a rule change
      *Observed on device 2026-08-14: background-sync quest presented with the earned date after rollover; no replay after a rule change.*
