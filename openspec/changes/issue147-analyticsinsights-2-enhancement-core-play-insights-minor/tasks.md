# Tasks

## 1. Bounded local derivation

- [x] 1.1 Verify the period framing prerequisite. Reuse comparable bounds and calculate scoped period comparisons and play mix with tests for zero baseline, incomplete tracking, hidden titles and total conservation.
- [x] 1.2 Derive distinct titles, first recorded activity and returns after the defined gap, plus session median and duration buckets. Verify ties, date boundaries, allocations without sessions and bounded history reads.
- [x] 1.3 Derive Focus share and evidenced quest consistency and verify historical viewing after Focus edits and unavailable daily outcomes.

## 2. Accessible insights presentation

- [x] 2.1 Add compact core sections and activity calendar with shared day selection. Verify exact dates, granularity for long windows and accessible navigation without precise chart touch.
- [x] 2.2 Exercise period switches and offline read behavior using sparse and representative activity fixtures. Verify no stale values are relabelled and re-record affected baselines.

## Validation

- 2,578 app unit tests and 53 gamification tests passed; debug APK build and Android lint passed with the existing lint baseline.
- Analytics Roborazzi baselines re-recorded and verified, including narrow 1.5× text captures for core facts and calendar navigation.
- Host Compose tests cover exact zero-minute date inspection, month navigation, weekly/monthly trends, retained snapshots and period switches. Room tests exercise local canonical reads, hidden titles and recovery-date boundaries.
