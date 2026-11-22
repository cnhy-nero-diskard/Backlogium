# Tasks

## 1. Admission identity and durability

- [ ] 1.1 Capture owned arrivals and automatic shared inserts in the committing transaction. Verify first sync, restore, account replacement, repeat observation, manual shared import and shared-to-owned conversion create no duplicate work.
- [ ] 1.2 Add pending-work persistence and recovery with migration and restart coverage, including a crash between the admission commit and the scheduler wake.

## 2. Bounded coordinated refresh

- [ ] 2.1 Drain eligible admissions under an explicit per-run and retry budget. Verify FIFO tail progress, cancellation, Retry-After, and connectivity recovery with a fake clock.
- [ ] 2.2 Reuse the per-game merge coordinator and classify catalogue, personal state and unavailable results independently. Verify overlapping refresh, account changes, first-unlock semantics and last-good data.

## 3. Integration

- [ ] 3.1 Exercise an owned and an automatically admitted shared game through the normal app paths and verify achievement hydration without a second playtime poll or an increased inline Steam sync budget.
