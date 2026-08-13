## Why

`harden-progress-events-durability` closed the crash-durability gaps in the progress-event
pipeline, but review of the resulting PR found that two of its guarantees still rest on
assumptions the implementation cannot enforce:

1. **A live persist is indistinguishable from a crashed one.** The single-slot `PendingTransition`
   WAL is recoverable after process death, but nothing serializes the protocol that writes it.
   `resolvePendingTransition()` treats "a pending transition exists" as "the call that wrote it is
   dead" — which is also exactly the state of a `persist()` sitting between its WAL write and its
   Room write. A foreground consumer can therefore clear a live persist's recovery record and
   consume its transition on its behalf, against a Room state that is only half written. Two
   overlapping `persist()` calls have the same shape from the other direction: each captures
   "previous state" from a profile row the other is replacing, and each clears a WAL record the
   other wrote, so one provenance's recovery state can be attributed to — or destroyed by — the
   other's. The individual writes are atomic; the *protocol* is not.

2. **`pendingEvents` derives from a pair it knows is half-committed.** Between the WAL write and
   the finalize, Room and the marks deliberately describe different logical versions of state.
   That is what makes recovery possible, and it is not a valid thing to diff — yet the repository
   diffs it on every tick, so an in-flight non-earned write reads as earned progress for as long as
   it is in flight.

3. **Quest earnedness is inferred from history rather than recorded.** `QuestMet` delivery is
   reconstructed as `DailyProgress.questMet == true && date > lastQuestCelebratedDate`. A stored
   met row is evidence of nothing: it may predate progress-event tracking, or have become met
   because a rule change loosened the threshold, or already have been celebrated. Because the
   non-earned reseed also *regresses* `lastQuestCelebratedDate` to null whenever today's quest is
   unmet, a rule change on an unmet day can make a whole account's celebrated quest history
   deliverable again.

## What Changes

- Introduce `ProgressTransitionCoordinator`, a process-wide (`@Singleton`) serialization boundary
  shared by `GamificationUpdater.persist()`, `resolvePendingTransition()`, and any future path that
  mutates transition recovery state. The critical section covers the whole protocol — resolve prior
  pending transition, capture previous state, write the WAL, perform the Room writes, finalize the
  marks, clear the WAL — so a second persist cannot enter until the first has finalized, and a
  recovery pass can only ever observe a WAL whose owner is gone.
- Stop deriving reconstructed events in `ProgressEventRepository.pendingEvents` while
  `marks.pendingTransition != null`. A pending transition marks the Room/marks pair as temporarily
  non-derivable; derivation resumes when finalization or recovery clears it.
- Replace historical quest inference with `ProgressMarks.pendingQuestDates: Set<LocalDate>` —
  explicit, durable identity for quests that were earned and not yet acknowledged. A date enters the
  set only when an earned recompute flips that day's quest from unmet to met, and leaves it only
  when that exact date is acknowledged. Non-earned sources add nothing and cancel nothing, first
  initialization seeds it empty, and `lastQuestCelebratedDate` becomes monotonic so no baseline
  reset can revive acknowledged history.
- Add interleaving regression tests that suspend a `persist()` at a named phase and run a recovery
  pass, a foreground consumer, or a second `persist()` while it is stopped there; plus provenance
  tests for every quest path (earned, recomputed-met, pre-tracking history, acknowledged).

## Capabilities

### New Capabilities

(none — this hardens the existing pipeline rather than introducing a new one)

### Modified Capabilities

- `progress-events`: the durability model gains two documented properties — the persist/recovery
  protocol is serialized within a process, and a pending transition marks the derived-value/marks
  pair as temporarily non-derivable. Quest delivery changes from "reconstruct earnedness from
  historical `DailyProgress` rows" to "deliver explicitly recorded earned quest identity", with
  provenance applied at the point the pending set is mutated.

## Impact

- `app/src/main/java/com/example/backlogium/domain/ProgressTransitionCoordinator.kt` (new),
  `PendingTransitionRecovery.kt`, `GamificationUpdater.kt`, `ProgressEventDetector.kt`,
  `ProgressMarks.kt`
- `app/src/main/java/com/example/backlogium/data/repo/ProgressEventRepository.kt`,
  `data/local/SettingsDataStore.kt` (one new preference key, `pending_quest_dates`)
- New JVM tests `ProgressTransitionProtocolTest`, `QuestEventProvenanceTest`,
  `ProgressEventTestSupport`; updated `ProgressEventRepositoryTest`,
  `ProgressEventDetectorTest`, `PendingTransitionRecoveryTest`, `GamificationDaoFakes`
- No Room migration; no change to `:gamification`; no change to `compute()`'s no-side-effect
  guarantee, required provenance, atomic marks updates, presentation priority, or Home's durable
  milestone/streak-break consumption.
