## 1. Measure the exposure before designing around it

- [x] 1.1 Determine whether a `DailyProgress` row is written for calendar days with no playtime; `SteamSyncWorker.kt:200` guarantees today's row exists on every poll, so establish what happens across a day the device was off or offline
- [x] 1.2 Record the finding in design.md — it decides whether the streak gap is a live defect or one that only appears after an offline day, and therefore how loudly the fix needs announcing
- [x] 1.3 Confirm `auditfix-sync-write-integrity` has landed, so per-date crediting can use an additive SQL update inside an existing transaction rather than adding one here
- [x] 1.4 Get the owner's decision on design.md Decision 4 — whether an inflated `longestStreak` is left as banked (recommended) or corrected by a one-time migration

## 2. Canonical attribution rule

- [x] 2.1 Write the start-date attribution rule into the `steam-sync` delta spec as the single normative answer, with the rejected alternatives recorded in design.md
- [x] 2.2 Change `SteamSyncWorker.kt:196-206` from summing all deltas into one date to grouping per session start date
- [x] 2.3 Confirm `SessionDiffer` exposes each action's session `startAt`; for `Extend` and `Close`, resolve the start date from the stored open session rather than the poll time
- [x] 2.4 Apply an additive daily-progress update per affected date, including goal-minutes attribution per date
- [x] 2.5 Align `ui/history/HistoryGrouping.kt` with the same rule and remove any independent date derivation
- [x] 2.6 Audit analytics for a third independent day derivation and align it too
- [x] 2.7 Test: a midnight-crossing session credits its start date in both history and daily progress
- [x] 2.8 Test: an open session extended across midnight keeps crediting its start date on later polls
- [x] 2.9 Test: one poll producing sessions with two different start dates credits both

## 3. Re-evaluating a past day

- [x] 3.1 Confirm `GamificationUpdater.kt:127-140` already recomputes every stored day and collects `changedDays`, so crediting a past date flips its quest status without new machinery
- [x] 3.2 Test: minutes credited to a previously-unmet past date flip it to met and the change is persisted via `changedDays`
- [x] 3.3 Verify a past-day flip propagates to streaks correctly, including when it repairs what was previously a break

## 4. Densify the streak day sequence

- [x] 4.1 In `GamificationUpdater`, build a contiguous calendar sequence from the earliest stored progress date through today, synthesizing unmet entries for dates with no stored row
- [x] 4.2 Fold that sequence rather than the raw `dailyProgressDao.getAllOrdered()` result at `:125`
- [x] 4.3 Ensure synthesized days never enter `changedDays` and are never persisted
- [x] 4.4 Leave `Gamification.streak()` and every one of its existing expectations untouched
- [x] 4.5 Test: Monday and Thursday rows with grace 0 yield a current streak of 2, not 3
- [x] 4.6 Test: the same rows with grace 1 yield the grace-adjusted result, asserted explicitly rather than assumed
- [x] 4.7 Test: a single stored row far in the past does not synthesize days before it
- [x] 4.8 Test: stored record count is unchanged after a recompute that synthesized gap days
- [x] 4.9 Check the densification cost over a multi-year span is acceptable, given recompute runs on every sync

## 5. Prevent the misreading from recurring

- [x] 5.1 Add a comment to `streak_ignoresGapsBetweenDatesUsesOrderOnly` stating that order-only folding is the engine's intended pure contract per the `gamification` spec, and that calendar densification belongs to the caller
- [x] 5.2 Add a comment at `GamificationUpdater`'s densification step pointing back to the engine's purity requirement, so the next reader understands why the calendar logic lives here and not there
- [x] 5.3 Do **not** modify the engine or any existing streak expectation

## 6. Verification and close-out

- [x] 6.1 Run `./gradlew :gamification:test :app:testDebugUnitTest` and confirm green, with no changes to pre-existing streak expectations
- [ ] 6.2 Verify on real data that the displayed current streak changed only where a genuine calendar gap exists
- [x] 6.3 Confirm the `longestStreak` high-water behaviour still holds per Decision 4's outcome
- [x] 6.4 Run `openspec validate auditfix-day-attribution`
- [x] 6.5 Record in the commit message that displayed current streaks may drop, and why that is a correction rather than a regression
