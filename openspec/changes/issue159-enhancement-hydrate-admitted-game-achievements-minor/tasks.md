# Tasks

## 1. Admission identity and durability

- [ ] 1.1 Capture owned arrivals and automatic shared inserts in the committing transaction. Verify first sync, restore, account replacement, repeat observation, manual shared import and shared-to-owned conversion create no duplicate work.
- [ ] 1.2 Add pending-work persistence and recovery with migration and restart coverage, including a crash between the admission commit and the scheduler wake.

## 2. Bounded coordinated refresh

- [ ] 2.1 Drain eligible admissions under an explicit per-run cap and retry budget using a durable, constrained, unique serialized work chain. Schedule continuations for remaining eligible rows and at the next retry deadline; preserve wakes racing with an empty-queue check.
- [ ] 2.2 Enqueue more than three times the configured cap in one commit, process the first chunk, then simulate no app launch, library sync, or new admission; verify FIFO progress to completion over bounded executions. Cover process death between chunks, offline and Retry-After delay with a fake clock, concurrent wakes, cancellation, and account replacement.
- [ ] 2.3 Reuse the per-game merge coordinator and classify catalogue, personal state and unavailable results independently. Verify overlapping refresh, first-unlock semantics and last-good data.

## 3. Integration

- [ ] 3.1 Exercise an owned and an automatically admitted shared game through the normal app paths and verify achievement hydration without a second playtime poll or an increased inline Steam sync budget.
