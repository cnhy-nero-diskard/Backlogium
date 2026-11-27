# Tasks

## 1. Genre and session patterns

- [x] 1.1 Verify the core insights prerequisite. Add bounded genre allocation and drift, with tests for multiple genres, unknown coverage, comparison eligibility, rounding and share conservation.
- [x] 1.2 Add the weekday by start-hour matrix using the established attribution. Verify overnight sessions, local zone changes and timing-free allocations.

## 2. Achievements and records

- [x] 2.1 Add bounded dated unlock and completion evidence, including prospective transition persistence if required. Verify unavailable dates, rarity snapshots, first baseline and unsupported historical completion.
- [x] 2.2 Define and compute scoped records with stable ties. Verify longest session, busiest and most varied days, per-game runs, rarest dated unlock, and returns with dated evidence.

## 3. Advanced presentation

- [x] 3.1 Integrate genre, time, achievement and record sections with metric-specific missing coverage and text equivalents. Verify narrow, large-text and offline states and re-record affected baselines.
- [x] 3.2 Exercise account and period changes through the whole view. Verify historical scope remains legible and lifetime rarity retains its independent behavior.

## Validation

- Core-insights prerequisite verified in the canonical ledger and comparison path.
- `:gamification:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` passed (53 engine + 2,594 app host tests). Lint retains existing baseline/warnings.
- All 40 `MigrationTest` instrumented cases passed on launcher-verified GPU emulator-5554, with both `ANDROID_SERIAL` and the instrumentation serial argument set.
- Analytics Roborazzi baselines recorded and reviewed, including narrow 1.5× text and all 28 accessible clock cells; selected Analytics baselines verified.
- Account-fenced Room evidence, independent lifetime rarity, retained period scope, historical navigation and replacement-account clearing covered by repository and whole-view tests. The UI fixtures run offline without Steam requests.
