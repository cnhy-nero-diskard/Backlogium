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
- Reviewed CI #839's 10 Analytics screenshot diffs and re-recorded only those Analytics baselines, including both narrow populated variants. The focused Analytics Roborazzi verification passes.
- The full 101-test local screenshot run passed all Analytics cases but reported five unchanged Library screenshot mismatches on macOS; those Library baselines were left untouched (they were unchanged in CI #839).
- Host Compose tests cover exact zero-minute date inspection, month navigation, weekly/monthly trends, retained snapshots and period switches. Room tests exercise local canonical reads, hidden titles and recovery-date boundaries.
