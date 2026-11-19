Prior checkmarks below record completion under unchanged original criteria, not a fresh test run.
Materially revised criteria are reopened for an implementation/test audit against the normative
`playtime-recovery` and historical-range contracts. Do not check off an audit or real-data task
merely because the text now matches the specs.

## 1. Placement rule

- [x] 1.1 Audit the existing pure recovery planner rather than adding a second allocator. Verify
      it consumes Steam-authorized quantity and timing/context inputs with no Room, Android,
      network or clock dependency, and produces timed, unknown-time or pending credit as appropriate.
- [x] 1.2 Verify bounded deterministic largest-remainder placement: 105 authorized minutes with
      35 whole usable minutes yield 35 timed and 70 fallback. Cover spans above, below and equal to
      the delta; verify timed plus residual/pending minutes equal the authorized total exactly.
      Fix the continuity path so 36 authorized minutes across 35 fully confirmed minutes yield
      35 timed and one unknown-time minute. Add a regression test and verify independently usable
      ordinary same-date short-gap estimates keep their existing rounding guard.
- [x] 1.3 Place minutes only against confirmed portions of an interval, contributing no
      confirmed span for an interval whose raw interior-gap pair exceeds the placement
      tolerance. Verify by test that a partially covered interval receives minutes for its
      confirmed span only, that a fresh-tail-with-interior-gap interval exceeding tolerance
      contributes no confirmed span for any part of it despite its fresh tail, and that a
      two-interior-outage fixture (`A@t0 → A@t1 → outage → A@t10 → A@t11 → outage → A@t18 →
      B@t19`, both outages above tolerance) contributes no confirmed span, so placement
      allocates nothing from it.
- [x] 1.4 Verify no/unknown/rejected timing and zero usable span yield unknown-time recovery,
      not a timestamped fallback session; zero delta authorizes no new quantity. Verify an inverted
      date window retains pending date-unknown minutes without invented dates or quantity loss.
- [x] 1.5 Keep the final session open when its interval is still ongoing. Verify by test that a
      later observation extends it rather than opening a second session.
- [x] 1.6 Verify by test that placement never produces a session in a period no interval covers.
- [x] 1.7 Verify duplicate, overlapping, wrong-account and stale-generation candidates cannot
      multiply timing capacity; clip to the contribution window and exclude already-consumed spans.
- [x] 1.8 Verify unknown and legacy transition coverage is rejected for ordinary placement, and
      sub-tolerance interior cuts do not become continuous timing or receive timed placement.

## 2. Wiring into the commit path

- [x] 2.1 Audit optional timing input through the existing shared committer/recovery planner.
      Verify omitting cloud input uses the normative local recovery path, not the former all-delta
      single-session fallback, with no second quantity detector or session writer.
- [x] 2.2 Consult the presence record only when the period since the previous observation is long
      enough for the estimate to be meaningfully wrong. Verify by test that a short period performs
      no read.
- [x] 2.3 Verify by test that a targeted post-play fetch's supplied play instant is not overridden
      by an available placement.
- [x] 2.4 Verify by test that baselines are still read inside the caller's transaction, so the
      exactly-once crediting property is unchanged by threading placement through.
- [x] 2.5 Verify by test that a full sync completes normally, crediting every minute, while the
      reader fails on every call.
- [x] 2.6 Verify every covering interval rejected by gap tolerance credits the full delta through
      unknown-time recovery, equivalent to the no-usable-timing path. Assert frozen dates,
      uncertainty labels and no fabricated timestamped session or placement on rejected evidence.
- [x] 2.7 Verify the existing 30-minute consultation gate and ordinary short-gap guards, including
      plausible delta, same-date eligibility and continuity evidence across midnight. A stale open
      row must not make a missed interval usable. Fix periodic consultation at exactly 30 minutes
      to skip the read if every positive owned-game delta independently qualifies using its own
      retained observation boundary. Test both the skip and a mixed batch where a delta still
      needs timing. Keep the threshold and targeted fetch behavior unchanged.
- [x] 2.8 Verify cursor-advancing reads retain account/generation-bound timing evidence before
      position advancement; combine retained evidence with unread pages, preserve it after failed
      Steam commits and prune it only after the applicable successful baseline commit.

## 3. Attribution and recompute

- [x] 3.1 Attribute each placed session by its own start date, with no session's minutes divided
      across dates. Verify by test that one increase spanning two dates credits both.
- [x] 3.2 Re-evaluate past dates the placement credited, through the existing daily-progress
      correction path. Verify by test that a past date's quest flips from unmet to met and is
      persisted.
- [x] 3.3 Recompute under the retroactive provenance from phase 3. Verify by test that no progress
      event is produced and the delivery baseline matches the values written.
- [x] 3.4 Verify by test that the longest streak is not lowered by a placement recompute.
- [x] 3.5 Verify canonical per-game credited totals include timed, unknown-time and pending shares
      once and remain unchanged by placement-only corrections, with unchanged XP and levels.
- [x] 3.6 Verify corrective recompute includes both losing and receiving dates from sessions and
      allocations, persists met-to-unmet as well as unmet-to-met changes, emits no new progress
      event and preserves previously owed unacknowledged events.
- [x] 3.7 Verify delayed evidence upgrades only provably mapped unlocked contributions, conserves
      quantity on replay and leaves legacy sessions without reliable contribution/window records intact.

## 4. Historical sweep

- [x] 4.1 Audit the existing one-time historical action rather than adding a parallel sweep.
      Verify selected/effective start, fixed end and complete acquisition before apply; paused,
      capped, cancelled or failed batches must not apply partial history or set the applied marker.
- [x] 4.2 Record that it has run and make repeated invocation a no-op. Verify by test.
- [x] 4.3 Leave sessions outside the record's coverage, and sessions covered only by
      intervals the placement tolerance rejects, exactly as they are. Verify by test that they
      are not rewritten on an assumption or on rejected evidence.
- [x] 4.4 Verify receipt-aware reversal restores this operation's original rows, provenance and
      imported-balance deltas while preserving later ordinary play and independent recovery upgrades.
      Without independent changes, verify the original daily progress is restored exactly. Fix
      recompute of affected dates and remove empty rows created solely by the re-file.
- [x] 4.5 Verify by test that the sweep produces no progress event and that an unacknowledged event
      owed by an earlier sync survives it.
- [x] 4.6 Verify optional pre-data transfer requires an earlier independent Steam-history import
      and separately confirmed cutoff; bound quantity by both the imported balance and eligible
      non-overlapping whole minutes. Test the disclosed closed-v1 estimate exception only here,
      not in ordinary placement, and conserve combined credited totals, XP and levels.
- [x] 4.7 Verify interrupted apply resumes the same saved range/cutoff and original deltas after
      ledger commit but before the applied marker, without recalculating against changed balances.
- [x] 4.8 Verify historical acquisition has its own resumable position and bounded user-started
      batches; ordinary reads neither steal its progress nor double-credit overlapping pages.
      Fence stale account/reader responses and keep staged evidence isolated.

## 5. Settings surface

- [x] 5.1 Offer the action in the cloud section while configured and unrun, and its reversal once
      complete. Verify it is absent when no endpoint is configured.
- [x] 5.2 Verify confirmation shows the available beginning, valid selected/effective range,
      fixed end and separate optional cutoff with timezone/instant. Disclose uncertainty, optional
      imported-to-tracked transfer and unchanged combined credit, XP, levels and Steam totals.
- [x] 5.3 Verify acquisition progress, explicit batch continuation and completion summaries show
      selected/covered ranges, cutoff, session/date counts, transferred and remaining imported
      minutes. Include a zero-change completion and an interrupted acquisition/apply fixture.
- [x] 5.4 Verify invalid/stale bounds are rejected or refreshed before acquisition, and a completed
      action requires reversal before another range/cutoff or reset of transferred Steam history.

## 6. Regression and boundaries

- [x] 6.1 Verify an unconfigured app follows the normative local recovery behavior on periodic
      and targeted paths with no cloud request; usable short-gap monitoring remains unchanged.
- [x] 6.2 Verify by test that no family-shared game is affected by placement, since it has no diffed
      increase to place.
- [x] 6.3 After auditing revised criteria, run
      `./gradlew :gamification:test :app:testDebugUnitTest` and confirm the suite passes.
- [x] 6.4 Verify the boundary grep in `CLAUDE.md` reports no new `data.local.entity` or DAO import
      under `ui/`.
- [x] 6.5 Validate with `openspec validate attribute-playtime-from-presence --strict` and inspect
      every MODIFIED block against its existing normative counterpart to ensure no later scenarios
      or safeguards are lost. Verify only this change's existing planning artifacts were edited.

## 7. Verify on real data

- [x] 7.3 Run the historical sweep against real history, then reverse it. Verify totals are
      unchanged by both, and that daily progress after the reversal matches what it was before.
- [ ] 7.4 Compare the result against phase 2's diagnostics surface and confirm the placement matches
      the disagreement that surface predicted.
- [ ] 7.5 Re-verify real-history apply/reversal against the reconciled bounded-range and partial-
      evidence criteria, including residual unknown-time minutes and preservation of subsequent play.
      Task 7.3 records the earlier run only and does not close this revised verification.

### Deferred manual checks (not verified)

The live cross-midnight Steam scenario is not practical to run for this change. These checks
are deferred until a suitable live setup is available; automated results do not count as a pass.

- **7.1:** Leave the app unable to sync across a day boundary, play on Steam, then sync. Verify
  play lands on the day it happened rather than the day of the last sync.
- **7.2:** In that scenario, verify the daily quest is met and the streak is unbroken, with no
  celebration shown.

## Workflow follow-up

- Use the apply workflow to audit implementation and tests against the reopened criteria; this
  change authorizes the targeted fixes in tasks 1.2, 2.7 and 4.4 only. Surface any other implementation
  gaps before changing code, and mark checks complete only once verified.
- Archive only after the implementation audit and remaining active real-data checks are satisfied;
  retain the current recovery and historical-range safeguards when syncing the delta specs.
