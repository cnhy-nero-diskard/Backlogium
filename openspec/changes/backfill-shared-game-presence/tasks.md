## 1. Provenance

- [x] 1.1 Add a `RecomputeSource` value for play observed retroactively, documented with why it is
      non-earned despite being earned play, and why it is distinct from `BACKFILL`. Verify
      `./gradlew :app:testDebugUnitTest` passes with the new value handled everywhere the enum is
      exhaustively matched.
- [x] 1.2 Verify by test that a recompute under the new source produces no progress event and sets
      the delivery baseline to the values written, in both the raising and lowering direction.
- [x] 1.3 Verify by test that the new source does not cancel an unacknowledged event owed by an
      earlier sync, and that a later sync still produces events measured from the reseeded baseline.

## 2. Ingest rule

- [ ] 2.1 Add the pure ingest in `app/domain/`, taking phase 2's intervals and emitting observations
      for the existing deriver rather than session rows directly. Verify it has no Room, Android or
      clock dependency.
- [ ] 2.2 Admit an interval only where the named game's stored source is presence-derived. Verify by
      test that an owned-game interval and an unknown-game interval each produce no action at all.
- [ ] 2.3 Clamp a partially covered interval to its confirmed portion, discard in full an
      interval whose raw interior-gap pair exceeds the ingest tolerance, and treat an
      unknown-coverage interval conservatively. Verify by test for continuous, observed-until,
      unknown, and fresh-tail-with-interior-gap coverage, where the gapped interval credits
      nothing despite the fresh tail, and by test over a two-interior-outage fixture
      (`A@t0 → A@t1 → outage → A@t10 → A@t11 → outage → A@t18 → B@t19`, both outages above
      tolerance) that no unobserved span is credited.
- [ ] 2.4 Verify by test that play falling entirely inside a poller gap produces no session and no
      span inferred from surrounding intervals.
- [ ] 2.5 Verify by test that two intervals closer together than the gap tolerance fold into one
      session rather than two, matching what the on-device observer would have produced.

## 3. Position and idempotency

- [ ] 3.1 Persist an ingest position recording how far cloud observations have been folded in.
      Verify by test that re-running the same window credits no additional minutes and creates no
      additional session.
- [ ] 3.2 Verify by test that an ingest interrupted partway resumes without re-crediting what was
      already written.
- [ ] 3.3 Clear the ingest position on a configured account change, alongside the read position.
      Verify by test that an account change discards both.
- [ ] 3.4 Verify by test over an overlap fixture that play recorded by the on-device observer and
      also present in the cloud record is credited once.

## 4. Writing and recompute

- [ ] 4.1 Persist actions through the existing `SessionActionWriter`, not a parallel path. Verify by
      test that a recovered session's stored shape is identical to an on-device-derived one.
- [ ] 4.2 Recompute under the new provenance after an ingest that wrote anything, and not at all
      when it wrote nothing. Verify by test for both.
- [ ] 4.3 Re-evaluate dates the ingest credited, including past dates whose quest was already
      evaluated, through the existing daily-progress correction path. Verify by test that a past
      date's quest flips to met and that no quest event is produced.
- [ ] 4.4 Verify by test that a streak broken only by an unobserved day is recomputed as unbroken
      and that no streak event fires.

## 5. Disclosure

- [ ] 5.1 Widen the shared-game "observed, not total" remedy to point at cloud presence as well as
      the live monitor, where it is not configured. Verify the disclosure still refuses to claim the
      figure is a Steam-verified total.
- [ ] 5.2 Verify by test that a shared game whose play was recovered entirely from cloud presence
      presents its time as observed, with the disclosure intact.

## 6. Partition and regression

- [ ] 6.1 Verify by test that no owned game gains, loses or alters a session as a result of any
      ingest, and that `SessionDiffer` and the sync path are untouched.
- [ ] 6.2 Verify by test that a recovered session participates identically in XP, quests, streaks,
      history and analytics.
- [ ] 6.3 Run `./gradlew :gamification:test :app:testDebugUnitTest` and confirm the suite passes.
- [ ] 6.4 Verify the boundary grep in `CLAUDE.md` reports no new `data.local.entity` or DAO import
      under `ui/`.

## 7. Verify on real data

- [ ] 7.1 With a family-shared game in the library, play it while the app is fully closed, then
      ingest. Verify a session is recovered that the app would previously have missed entirely.
- [ ] 7.2 Verify the recovered session appears in History on the day it happened, and that no
      celebration fired for it.
- [ ] 7.3 Confirm the pre-phase-1 era of unknown-coverage intervals contributes conservatively
      rather than crediting full spans, and record what it actually contributed.
