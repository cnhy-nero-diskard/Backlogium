## 1. Threshold

- [x] 1.1 Change `PersonalPace.RELIABLE_COVERED_DATES` from 28 to 14, leaving `RELIABLE_ACTIVE_DATES`, `LOOKBACK_DAYS`, the recency half-life, and every forecast calculation untouched, and verify `./gradlew :app:testDebugUnitTest --tests "*PersonalPaceTest*"` reports only the boundary failures task 1.2 updates.
- [x] 1.2 Move `PersonalPaceTest`'s reliability boundary onto the new threshold — reliable at exactly 14 covered dates with six active, learning at 13 — and keep the existing too-few-active case at six so both halves of the rule stay independently pinned.
- [x] 1.3 Add a `PersonalPaceTest` case proving the threshold gates classification only: a profile that is reliable under 14 and would have been learning under 28 produces the same `expectedActiveDays` and `expectedGamingMinutes` as the identical history derived before the change, so no existing reliable profile's numbers move.

## 2. Weekday sample at the new floor

- [x] 2.1 Add a `PersonalPaceTest` case that pins the behaviour design decision 3 records: at exactly 14 covered dates a weekday whose two observations were both inactive yields an `activeProbability` of zero and therefore contributes no expected minutes for its occurrences in a forecast range. Name the change in a comment so the intent reads as recorded rather than accidental.
- [x] 2.2 Add a `PersonalPaceTest` case showing `typicalActiveDayMinutes` for a sparse weekday at 14 covered dates stays near the global active-day figure rather than tracking its own two-sample, confirming the existing sparse-weekday blend still carries duration.

## 3. Verification

- [x] 3.1 Run `./gradlew :app:testDebugUnitTest :gamification:test` and confirm the whole suite passes, paying particular attention to `CollectionPacingTest` and `CollectionPacingSummaryTest`, which build reliable profiles from longer histories and must be unaffected.
- [x] 3.2 Run `./gradlew assembleDebug` and confirm the debug APK still builds.
- [x] 3.3 Confirm no other consumer hard-codes the old threshold: grep for `28` under `app/src/main/java/com/example/backlogium/domain/` and the pace-consuming UI, and verify every remaining occurrence is the recency half-life or unrelated.
