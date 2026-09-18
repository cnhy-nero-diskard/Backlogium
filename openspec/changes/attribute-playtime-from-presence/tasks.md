## 1. Placement rule

- [x] 1.1 Add the pure placement rule in `app/domain/`, taking a diffed increase and the intervals
      covering its period and returning `SessionAction` values. Verify it has no Room, Android,
      network or clock dependency.
- [x] 1.2 Distribute minutes proportionally to confirmed span with largest-remainder rounding.
      Verify by test that the written minutes sum to the diffed increase exactly, across spans that
      total more than, less than, and equal to it.
- [x] 1.3 Place minutes only against confirmed portions of an interval, contributing no
      confirmed span for an interval whose raw interior-gap pair exceeds the placement
      tolerance. Verify by test that a partially covered interval receives minutes for its
      confirmed span only, that a fresh-tail-with-interior-gap interval exceeding tolerance
      contributes no confirmed span for any part of it despite its fresh tail, and that a
      two-interior-outage fixture (`A@t0 → A@t1 → outage → A@t10 → A@t11 → outage → A@t18 →
      B@t19`, both outages above tolerance) contributes no confirmed span, so placement
      allocates nothing from it.
- [x] 1.4 Return no placement when no interval covers the period, when spans total zero, or when the
      increase is zero. Verify by test for each, and that each falls back rather than erroring.
- [x] 1.5 Keep the final session open when its interval is still ongoing. Verify by test that a
      later observation extends it rather than opening a second session.
- [x] 1.6 Verify by test that placement never produces a session in a period no interval covers.

## 2. Wiring into the commit path

- [x] 2.1 Add placement as an optional input to `PlaytimeObservationCommitter`, leaving
      `SessionDiffer` untouched. Verify by test that a commit with no placement produces byte-identical
      results to today.
- [x] 2.2 Consult the presence record only when the period since the previous observation is long
      enough for the estimate to be meaningfully wrong. Verify by test that a short period performs
      no read.
- [x] 2.3 Verify by test that a targeted post-play fetch's supplied play instant is not overridden
      by an available placement.
- [x] 2.4 Verify by test that baselines are still read inside the caller's transaction, so the
      exactly-once crediting property is unchanged by threading placement through.
- [x] 2.5 Verify by test that a full sync completes normally, crediting every minute, while the
      reader fails on every call.
- [x] 2.6 Verify by test that when every covering interval is rejected by the gap tolerance,
      the commit still credits the full diffed increase through the unaided single-session
      attribution — byte-identical to the no-record path — rather than crediting zero or
      placing minutes on rejected evidence. Verify both the total minutes and the resulting
      session's date attribution.

## 3. Attribution and recompute

- [x] 3.1 Attribute each placed session by its own start date, with no session's minutes divided
      across dates. Verify by test that one increase spanning two dates credits both.
- [x] 3.2 Re-evaluate past dates the placement credited, through the existing daily-progress
      correction path. Verify by test that a past date's quest flips from unmet to met and is
      persisted.
- [x] 3.3 Recompute under the retroactive provenance from phase 3. Verify by test that no progress
      event is produced and the delivery baseline matches the values written.
- [x] 3.4 Verify by test that the longest streak is not lowered by a placement recompute.
- [x] 3.5 Verify by test that no game's total recorded minutes change as a result of placement.

## 4. Historical sweep

- [x] 4.1 Add the one-time re-filing action, re-placing already-recorded sessions covered by the
      presence record. Verify by test that each game's total recorded minutes are unchanged.
- [x] 4.2 Record that it has run and make repeated invocation a no-op. Verify by test.
- [x] 4.3 Leave sessions outside the record's coverage, and sessions covered only by
      intervals the placement tolerance rejects, exactly as they are. Verify by test that they
      are not rewritten on an assumption or on rejected evidence.
- [x] 4.4 Implement the reversal, returning attribution to its prior state and re-offering the
      action. Verify by test that a sweep followed by its reversal restores the prior daily progress
      exactly.
- [x] 4.5 Verify by test that the sweep produces no progress event and that an unacknowledged event
      owed by an earlier sync survives it.

## 5. Settings surface

- [x] 5.1 Offer the action in the cloud section while configured and unrun, and its reversal once
      complete. Verify it is absent when no endpoint is configured.
- [x] 5.2 State before it runs that dates, quests and streaks may change and that experience, levels
      and totals will not. Verify the stated limit matches what task 3.5 asserts.
- [x] 5.3 Report the outcome — sessions re-filed and dates affected — rather than only completion.
      Verify by test over a fixture with a known number of both.

## 6. Regression and boundaries

- [x] 6.1 Verify by test that an unconfigured app synthesizes sessions identically to before this
      change, on every path including the targeted fetch.
- [x] 6.2 Verify by test that no family-shared game is affected by placement, since it has no diffed
      increase to place.
- [x] 6.3 Run `./gradlew :gamification:test :app:testDebugUnitTest` and confirm the suite passes.
- [x] 6.4 Verify the boundary grep in `CLAUDE.md` reports no new `data.local.entity` or DAO import
      under `ui/`.

## 7. Verify on real data

- [ ] 7.1 Reproduce the motivating case end to end: leave the app unable to sync across a day
      boundary, play, then sync. Verify the play lands on the day it happened rather than on the day
      of the last sync.
- [ ] 7.2 Verify a daily quest for that day evaluates as met and the streak is unbroken, with no
      celebration shown.
- [x] 7.3 Run the historical sweep against real history, then reverse it. Verify totals are
      unchanged by both, and that daily progress after the reversal matches what it was before.
- [ ] 7.4 Compare the result against phase 2's diagnostics surface and confirm the placement matches
      the disagreement that surface predicted.
