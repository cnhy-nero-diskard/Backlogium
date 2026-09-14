## 1. Schema versions

- [x] 1.1 Split `SCHEMA_VERSION` in `functions/src/steam.ts` into one exported constant per document
      shape — current-state at `2` (it gains the retained-step pair), presence transition at `3` —
      each documented with the shape it stamps and the rule that a shape change bumps only its own
      version. Verify `npm --prefix functions run build` typechecks.
- [x] 1.2 Remove `v` from the in-memory `Observation` interface and from the object `steam.ts`
      returns, since an observation is not a document and has no shape version (design.md — Drop `v`
      from the in-memory `Observation`). Verify the build typechecks and `npm --prefix functions test`
      passes with any `steam.test.ts` assertion on that field removed rather than retargeted.
- [x] 1.3 Update the three write sites in `functions/src/presence.ts` to stamp the constant matching
      the document each one writes. Verify `npm --prefix functions test` passes, including the
      existing assertion that the current-state document is version `2` and the transition is
      version `3`.

## 2. Coverage recording

- [x] 2.1 On a material change, write `prevLastObservedAt` onto the appended transition from the
      `previous` snapshot the transaction has already read, omitting the field entirely when no
      prior state exists. Verify by unit test that a transition following a stored state carries the
      stored watermark, and that the first transition for a player omits the field rather than
      writing `null` or `0`.
- [x] 2.2 Confirm the value is read inside the existing transaction and that no second read is
      introduced. Verify against `FakeFirestore` that one invocation performs the same number of
      reads as before this change.
- [x] 2.3 Confirm the unchanged-poll path is untouched — no transition appended, `since` and
      `updatedAt` retained, `lastObservedAt` advanced. Verify the existing unchanged-poll tests still
      pass without modification.
- [x] 2.4 On a same-game poll that advances the watermark, retain that step's endpoints as
      `coverageLapseFrom` / `coverageLapseRecoveredAt` on the current-state document with no
      tolerance cutoff, keep the first retained pair across later same-game polls, and copy it onto
      the next transition as `prevCoverageLapseFrom` / `prevCoverageLapseRecoveredAt`, starting the
      new state clean. Verify by unit test that a recovery poll followed by a transition carries the
      pair, and that a direct transition omits it.

## 3. Spec coverage in tests

- [x] 3.1 Add a test for continuous observation: successive same-game polls followed by a
      different-game poll produce a transition whose `prevLastObservedAt` is no earlier than the
      preceding poll.
- [x] 3.2 Add a test for a lapse: a stored state whose watermark is well before the next successful
      observation produces a transition recording that earlier watermark, so the unobserved tail is
      identifiable.
- [x] 3.3 Add a test asserting the transition document contains no duration, elapsed time, gap
      length, or coverage proportion — only observation timestamps — so the *Coverage is recorded,
      never derived* scenario fails a build rather than a review.
- [x] 3.4 Add a test asserting each document shape carries its own version — current-state `2`,
      transition `3` — covering the *Each altered shape advances its own version* scenario.
- [x] 3.5 Run `npm --prefix functions test` and confirm every scenario in
      `specs/cloud-presence-poller/spec.md` has a corresponding passing test.
- [x] 3.6 Add a regression for a sub-three-minute lapse followed by a same-game recovery poll and a
      later transition, proving no poller-side cutoff discards the span before a stricter reader can
      judge it.

## 4. Log hygiene

- [x] 4.1 Confirm no log line was added or changed by this work, and that the new field never reaches
      log output. Verify the boundary grep from `functions/README.md` is silent:
      `grep -rnE "firebase-functions/logger|console\." functions/src/ --exclude=safeLog.ts --exclude="*.test.ts"`

## 5. Documentation

- [x] 5.1 Update the recorded-shape block in `functions/README.md` to show `prevLastObservedAt` and
      the retained pair on the transition document, the retained pair on the current-state document,
      and the two independent version numbers. Verify the documented shape matches what the tests
      assert is written.
- [x] 5.2 State in `functions/README.md` what the field means to a reader — including that an absent
      value means unknown coverage, covering both pre-existing `v: 1` documents and a first
      transition. Verify the text names the mechanism rather than only the field.

## 6. Deploy and verify in production

- [x] 6.1 Build and deploy: `npm --prefix functions run build` then
      `firebase deploy --only functions`. Verify the deployed function is still in
      `asia-southeast1` and that no `firestore.rules` deploy was included.
- [x] 6.2 Confirm `poll ok` heartbeats resume after the deploy (`npm --prefix functions run logs`),
      establishing the pipeline is healthy before any data is judged.
- [x] 6.3 Start a game, let a transition record, then stop it. Verify the appended transitions carry
      `v: 2` and a `prevLastObservedAt` within roughly one polling interval of their own `t`. (This
      verified the earlier revision, before the retained pair existed; the `v: 3` shape with the pair
      is covered by 7.3 and has not been deployed yet.)
- [x] 6.4 Verify the current-state document still reads `v: 1` and is otherwise unchanged, and that a
      stretch of unchanged polls appended nothing. (Same scoping as 6.3: the `v: 2` current-state shape
      with the retained pair is covered by 7.3.)
- [x] 6.5 Record the deploy-window lapse if one was captured — a game running across the redeploy
      produces a short unobserved tail, which is correct behaviour and worth noting so the first
      production coverage gap is not investigated as a bug.

## 7. Review follow-up: no poller-side cutoff

- [x] 7.1 Remove the lapse-retention tolerance cutoff so every forward same-game step is retained
      verbatim with first-wins carry-forward, and add the sub-three-minute regression alongside
      updated cadence and second-lapse tests. Verify `npm --prefix functions test` and
      `npm --prefix functions run build` pass.
- [x] 7.2 Reconcile the change artifacts with the implementation: delta spec (retention rule, short-lapse
      scenario, per-shape versions), `design.md` (no-cutoff decision with rejected alternatives,
      current-state `v: 2` / transition `v: 3`, pair-absence meaning, verify and rollback steps), and
      `functions/README.md` (retention wording). Verify the documented shapes match what the tests
      assert is written.
- [ ] 7.3 Build and deploy the `v: 2` / `v: 3` revision (`npm --prefix functions run build` then
      `firebase deploy --only functions`, still `asia-southeast1`, no `firestore.rules` change), then
      repeat the section 6 verification against the new shapes: transitions carry `v: 3` with the
      retained pair once a same-game poll has advanced the state, and the current-state document reads
      `v: 2`.
- [x] 7.4 Record that the retained pair preserves the verdict, not the locations: keep the
      largest-wins rule, state in the delta spec, `design.md` and `functions/README.md` that a
      downstream consumer discards the whole interval once the retained span exceeds its
      tolerance, and add the two-interior-outage regression (`A@t0 → A@t1 → outage → A@t10 →
      A@t11 → outage → A@t18 → B@t19`, both outages above a two-minute tolerance) proving the
      retained span still trips the tolerance while the second location is unrecoverable.
      Verify `npm --prefix functions test` and `npm --prefix functions run build` pass.
