## 1. Schema versions

- [x] 1.1 Split `SCHEMA_VERSION` in `functions/src/steam.ts` into one exported constant per document
      shape — current-state at `1`, presence transition at `2` — each documented with the shape it
      stamps and the rule that a shape change bumps only its own version. Verify
      `npm --prefix functions run build` typechecks.
- [x] 1.2 Remove `v` from the in-memory `Observation` interface and from the object `steam.ts`
      returns, since an observation is not a document and has no shape version (design.md — Drop `v`
      from the in-memory `Observation`). Verify the build typechecks and `npm --prefix functions test`
      passes with any `steam.test.ts` assertion on that field removed rather than retargeted.
- [x] 1.3 Update the three write sites in `functions/src/presence.ts` to stamp the constant matching
      the document each one writes. Verify `npm --prefix functions test` passes, including the
      existing assertion that the current-state document is version `1`.

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
- [x] 3.4 Add a test asserting the current-state document's version is unaffected by the transition
      shape change, covering the *An unaltered shape keeps its version* scenario.
- [x] 3.5 Run `npm --prefix functions test` and confirm every scenario in
      `specs/cloud-presence-poller/spec.md` has a corresponding passing test.

## 4. Log hygiene

- [x] 4.1 Confirm no log line was added or changed by this work, and that the new field never reaches
      log output. Verify the boundary grep from `functions/README.md` is silent:
      `grep -rnE "firebase-functions/logger|console\." functions/src/ --exclude=safeLog.ts --exclude="*.test.ts"`

## 5. Documentation

- [x] 5.1 Update the recorded-shape block in `functions/README.md` to show `prevLastObservedAt` on
      the transition document and the two independent version numbers. Verify the documented shape
      matches what the tests assert is written.
- [x] 5.2 State in `functions/README.md` what the field means to a reader — including that an absent
      value means unknown coverage, covering both pre-existing `v: 1` documents and a first
      transition. Verify the text names the mechanism rather than only the field.

## 6. Deploy and verify in production

- [x] 6.1 Build and deploy: `npm --prefix functions run build` then
      `firebase deploy --only functions`. Verify the deployed function is still in
      `asia-southeast1` and that no `firestore.rules` deploy was included.
- [x] 6.2 Confirm `poll ok` heartbeats resume after the deploy (`npm --prefix functions run logs`),
      establishing the pipeline is healthy before any data is judged.
- [ ] 6.3 Start a game, let a transition record, then stop it. Verify the appended transitions carry
      `v: 2` and a `prevLastObservedAt` within roughly one polling interval of their own `t`.
- [ ] 6.4 Verify the current-state document still reads `v: 1` and is otherwise unchanged, and that a
      stretch of unchanged polls appended nothing.
- [ ] 6.5 Record the deploy-window lapse if one was captured — a game running across the redeploy
      produces a short unobserved tail, which is correct behaviour and worth noting so the first
      production coverage gap is not investigated as a bug.
