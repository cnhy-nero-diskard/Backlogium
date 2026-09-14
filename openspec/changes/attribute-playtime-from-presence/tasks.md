## 1. Placement rule

- [ ] 1.1 Add the pure placement rule in `app/domain/`, taking a diffed increase and the intervals
      covering its period and returning `SessionAction` values. Verify it has no Room, Android,
      network or clock dependency.
- [ ] 1.2 Distribute minutes proportionally to confirmed span with largest-remainder rounding.
      Verify by test that the written minutes sum to the diffed increase exactly, across spans that
      total more than, less than, and equal to it.
- [ ] 1.3 Place minutes only against confirmed portions of an interval, excluding the interior
      span of an interval carrying the raw interior-gap pair. Verify by test that a partially
      covered interval receives minutes for its confirmed span only, and that a
      fresh-tail-with-interior-gap interval receives none for the interior span despite its fresh
      tail.
- [ ] 1.4 Return no placement when no interval covers the period, when spans total zero, or when the
      increase is zero. Verify by test for each, and that each falls back rather than erroring.
- [ ] 1.5 Keep the final session open when its interval is still ongoing. Verify by test that a
      later observation extends it rather than opening a second session.
- [ ] 1.6 Verify by test that placement never produces a session in a period no interval covers.

## 2. Wiring into the commit path

- [ ] 2.1 Add placement as an optional input to `PlaytimeObservationCommitter`, leaving
      `SessionDiffer` untouched. Verify by test that a commit with no placement produces byte-identical
      results to today.
- [ ] 2.2 Consult the presence record only when the period since the previous observation is long
      enough for the estimate to be meaningfully wrong. Verify by test that a short period performs
      no read.
- [ ] 2.3 Verify by test that a targeted post-play fetch's supplied play instant is not overridden
      by an available placement.
- [ ] 2.4 Verify by test that baselines are still read inside the caller's transaction, so the
      exactly-once crediting property is unchanged by threading placement through.
- [ ] 2.5 Verify by test that a full sync completes normally, crediting every minute, while the
      reader fails on every call.

## 3. Attribution and recompute

- [ ] 3.1 Attribute each placed session by its own start date, with no session's minutes divided
      across dates. Verify by test that one increase spanning two dates credits both.
- [ ] 3.2 Re-evaluate past dates the placement credited, through the existing daily-progress
      correction path. Verify by test that a past date's quest flips from unmet to met and is
      persisted.
- [ ] 3.3 Recompute under the retroactive provenance from phase 3. Verify by test that no progress
      event is produced and the delivery baseline matches the values written.
- [ ] 3.4 Verify by test that the longest streak is not lowered by a placement recompute.
- [ ] 3.5 Verify by test that no game's total recorded minutes change as a result of placement.

## 4. Historical sweep

- [ ] 4.1 Add the one-time re-filing action, re-placing already-recorded sessions covered by the
      presence record. Verify by test that each game's total recorded minutes are unchanged.
- [ ] 4.2 Record that it has run and make repeated invocation a no-op. Verify by test.
- [ ] 4.3 Leave sessions outside the record's coverage exactly as they are. Verify by test that they
      are not rewritten on an assumption.
- [ ] 4.4 Implement the reversal, returning attribution to its prior state and re-offering the
      action. Verify by test that a sweep followed by its reversal restores the prior daily progress
      exactly.
- [ ] 4.5 Verify by test that the sweep produces no progress event and that an unacknowledged event
      owed by an earlier sync survives it.

## 5. Settings surface

- [ ] 5.1 Offer the action in the cloud section while configured and unrun, and its reversal once
      complete. Verify it is absent when no endpoint is configured.
- [ ] 5.2 State before it runs that dates, quests and streaks may change and that experience, levels
      and totals will not. Verify the stated limit matches what task 3.5 asserts.
- [ ] 5.3 Report the outcome — sessions re-filed and dates affected — rather than only completion.
      Verify by test over a fixture with a known number of both.

## 6. Regression and boundaries

- [ ] 6.1 Verify by test that an unconfigured app synthesizes sessions identically to before this
      change, on every path including the targeted fetch.
- [ ] 6.2 Verify by test that no family-shared game is affected by placement, since it has no diffed
      increase to place.
- [ ] 6.3 Run `./gradlew :gamification:test :app:testDebugUnitTest` and confirm the suite passes.
- [ ] 6.4 Verify the boundary grep in `CLAUDE.md` reports no new `data.local.entity` or DAO import
      under `ui/`.

## 7. Verify on real data

- [ ] 7.1 Reproduce the motivating case end to end: leave the app unable to sync across a day
      boundary, play, then sync. Verify the play lands on the day it happened rather than on the day
      of the last sync.
- [ ] 7.2 Verify a daily quest for that day evaluates as met and the streak is unbroken, with no
      celebration shown.
- [ ] 7.3 Run the historical sweep against real history, then reverse it. Verify totals are
      unchanged by both, and that daily progress after the reversal matches what it was before.
- [ ] 7.4 Compare the result against phase 2's diagnostics surface and confirm the placement matches
      the disagreement that surface predicted.
