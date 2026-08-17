## 1. Prerequisites and constraints

- [x] 1.1 Confirm `auditfix-verification-coverage` has landed, so the `functions/` test harness and the fake Firestore surface already exist
- [x] 1.2 Re-read the cloud constraints in `CLAUDE.md` before editing: region `asia-southeast1` is permanent, retention is indefinite and no TTL may be added, rules stay deny-all
- [x] 1.3 Re-read `openspec/specs/cloud-presence-poller/spec.md` so the transaction rewrite preserves every existing requirement

## 2. Make transition recording transactional

- [x] 2.1 Replace the `db.batch()` in `presence.ts:83-105` with `db.runTransaction`, moving the `playerRef` read at `:64` inside it
- [x] 2.2 Keep the `unchanged` early return inside the transaction — moving it outside would leave the most common path racy
- [x] 2.3 Write both the player document and the presence document via the transaction handle
- [x] 2.4 Preserve the existing `since` reset semantics exactly: reset only on a transition, never on an unchanged poll
- [x] 2.5 Confirm the returned `WriteOutcome` still distinguishes `unchanged` from `written` for the caller and the logs

## 3. Do not alter the comparison

- [x] 3.1 Leave `isMaterialChange` logically unchanged, including the deliberate exclusion of `personastate`
- [x] 3.2 Verify the reasoning comment at `presence.ts:28-36` survives the edit intact
- [x] 3.3 Add the persona-state-only regression test so a future tidy-up of this function fails loudly

## 4. Correct the idempotency documentation

- [x] 4.1 Delete the claim at `presence.ts:76-78` that keying by observation time makes redelivery idempotent — the key is minted per invocation at `steam.ts:58`, so it never repeats
- [x] 4.2 Replace it with an accurate statement: uniqueness comes from the transaction plus `isMaterialChange`, and the ISO key exists only to sort chronologically
- [x] 4.3 Note in the comment that weakening the transaction re-opens duplicates regardless of the key, so the next reader understands what the guarantee rests on
- [x] 4.4 Keep the document key as it is (design.md Decision 2) — changing it would solve a problem the transaction already solves and would cost chronological sorting

## 5. Close the overlap window

- [x] 5.1 Set `maxInstances: 1` on the scheduled function in `index.ts`
- [x] 5.2 Reduce `timeoutSeconds` below the 60-second schedule interval, with a comment explaining that a poll which cannot finish in time has nothing useful to report
- [x] 5.3 Leave `retryCount: 0` and its existing comment alone — the reasoning there is still correct
- [x] 5.4 Confirm the reduced timeout still accommodates a normal Steam response with margin, using observed latency rather than a guess

## 6. Tests

- [x] 6.1 Extend the fake Firestore surface to model transaction retry — a `tx.get` returning stale data once, then fresh — or the concurrency tests assert nothing
- [x] 6.2 Test: two overlapping invocations observing one transition produce exactly one record, with the second writing nothing
- [x] 6.3 Test: same-game poll writes nothing; genuine game-to-game transition writes both documents and resets `since`; game-to-offline records a transition
- [x] 6.4 Test: persona-state-only change writes nothing
- [x] 6.5 Test: a Steam error or timeout writes nothing and leaves no partial state
- [x] 6.6 Test: transaction retry converges rather than looping
- [x] 6.7 Invert the duplicate-delivery expectation added by `auditfix-verification-coverage` from two records to one, and remove the comment marking it a known defect

## 7. Deploy and verify

- [x] 7.1 Run `npm --prefix functions run build` and `npm --prefix functions test` and confirm both pass
- [x] 7.2 Deploy to `asia-southeast1` and confirm the region matches Firestore's
- [x] 7.3 Observe production logs across several polls and confirm the `unchanged` path still dominates, which is the signal that `isMaterialChange` was not disturbed
- [x] 7.4 Confirm no TTL policy exists on the presence collection
- [x] 7.5 Run `openspec validate auditfix-cloud-poller-consistency`
- [x] 7.6 Record in the commit message that the overlap window came from `timeoutSeconds` equalling the schedule interval, since that is the non-obvious part of the diagnosis
