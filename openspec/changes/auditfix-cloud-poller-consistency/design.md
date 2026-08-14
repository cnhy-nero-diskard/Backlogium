# Design

## Context

```
  invocation A                       invocation B (60s later, A still running)
  â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€                       â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  get players/{id}   â”€â”€ gameid=440
                                     get players/{id}   â”€â”€ gameid=440
  Steam says 570
  isMaterialChange â†’ true
                                     Steam says 570
                                     isMaterialChange â†’ true
  batch.set(player, 570)
  batch.set(presence/T_A, 570)
  commit
                                     batch.set(player, 570)
                                     batch.set(presence/T_B, 570)
                                     commit

  result: two transition records for one transition, T_A â‰  T_B,
          and retention is indefinite so both are permanent
```

`schedule: "* * * * *"` with `timeoutSeconds: 60` is what makes this reachable â€” a slow
invocation is still alive when its successor starts. The audit reached the right conclusion
via the wrong mechanism (scheduler redelivery, largely closed off by `retryCount: 0`).

## Decision 1: `runTransaction` around read-decide-write

```ts
await db.runTransaction(async (tx) => {
  const snapshot = await tx.get(playerRef);
  const previous = snapshot.exists ? (snapshot.data() as StoredState) : undefined;
  if (!isMaterialChange(previous, observation)) return "unchanged";
  tx.set(playerRef, { ... });
  tx.set(presenceRef, { ... });
  return "written";
});
```

Firestore transactions in the Admin SDK use optimistic concurrency: if `playerRef` changes
between the `tx.get` and the commit, the transaction retries with fresh state. On retry, B
re-reads `gameid = 570`, `isMaterialChange` returns false, and B writes nothing. The
duplicate disappears without any deduplication logic.

**The `unchanged` path must stay inside the transaction.** Returning early before the
transaction begins would reintroduce the race for exactly the case that occurs 99% of the
time (same-game polls). The read is the thing that needs isolating, and the read happens on
every invocation.

**Cost**: an extra round trip versus a batch, on a function that runs once a minute. Not
worth discussing further.

**Rejected: a distributed lock document.** More moving parts, needs lease expiry to survive
a crashed holder, and Firestore already provides the primitive.

**Rejected: relying on `maxInstances: 1` alone.** See Decision 3 â€” useful as defence in
depth, not sufficient as the mechanism.

## Decision 2: The document key, and what "idempotent" can actually mean

Today: `presence/{observation.t.toISOString()}`, where `observation.t` is `new Date()` per
invocation (`steam.ts:58`). Two invocations observing one transition produce two keys.

Options:

| Key | Duplicate-safe | Cost |
|---|---|---|
| **A.** observation timestamp (today) | no â€” every invocation differs | none |
| **B.** minute-truncated observation timestamp | mostly â€” collides only within a minute | loses sub-minute ordering |
| **C.** `{gameid}_{since}` â€” the transition's identity | yes â€” same transition, same key | key no longer sorts by time |
| **D.** keep A, rely on the transaction | yes, in practice | key remains meaningless for dedup |

**Chosen: D as the correctness mechanism, with A's key retained** â€” and the comment
rewritten to stop claiming the key provides idempotency.

Reasoning: once the transaction is in place, a second invocation observing the same
transition writes *nothing at all*, so no second document is created and the key never has
to deduplicate anything. Changing the key would be solving a problem the transaction has
already solved, and it would cost something real â€” the ISO-timestamp key sorts
chronologically, which is how the transition log is read. C breaks that; B degrades it.

**What the honest comment says**: the transition log's uniqueness comes from the transaction
plus `isMaterialChange`, not from the document key. The key is a chronologically-sortable
identifier, nothing more. Any future change that weakens the transaction re-opens duplicates
regardless of the key.

This is worth stating explicitly because the current comment is *load-bearing
misinformation* â€” someone reading it would reasonably conclude the write path is already
safe and stop looking.

## Decision 3: Prevent overlap as defence in depth

Correctness now rests on the transaction. Overlap prevention is a second layer, and cheap:

- **`maxInstances: 1`** on the scheduled function bounds concurrent instances.
- **Reduce `timeoutSeconds` below the 60-second schedule interval.** A poll that cannot
  finish in, say, 45 seconds has nothing useful to say â€” Steam is slow or down, and the next
  poll is seconds away. This closes the structural window in Decision 1's diagram directly,
  and is the single most targeted fix available.

Both are configuration. Neither is a substitute for the transaction, and the transaction
alone would be sufficient â€” but a design where the correctness argument requires reasoning
about optimistic retries is worse than one where overlap is also just unlikely.

`retryCount: 0` stays as it is; the existing comment explaining it ("the next poll is 60
seconds away, so a retry buys nothing") is correct and remains so.

## Decision 4: Do not touch `isMaterialChange`

`isMaterialChange` excludes `personastate` deliberately. The reasoning is documented at
`presence.ts:28-36`: Steam cycles idle accounts between online, away, and snooze on its own,
which "filled half the log with idle churn and â€” worse â€” split a continuous session into
fragments when the user idled mid-game." CLAUDE.md lists it as a constraint that is expensive
or impossible to reverse.

This change moves the function's *call site* into a transaction and must not alter its logic.
Called out explicitly because a rewrite of surrounding code is precisely when a comparison
function gets "simplified" by someone who has not read the history â€” and the cost is
permanent, since deleted history is unrecoverable and fragmented sessions are not repairable
after the fact.

Its guarantee â€” no two adjacent entries share a game ID â€” is what downstream consumers rely
on to avoid merge-contiguous-runs logic. The transaction strengthens that guarantee; nothing
here should weaken it.

## Testing strategy

Building on the fake Firestore surface from `auditfix-verification-coverage`. The fake now
needs to model transaction retry â€” a `tx.get` returning stale data once, then fresh data â€” or
the concurrency tests verify nothing.

- two overlapping invocations observing the same transition produce exactly one transition
  record, with the second writing nothing
- a same-game poll writes nothing, inside a transaction as before
- a genuine game-to-game transition writes both documents and resets `since`
- game-to-offline records a transition
- **persona-state-only change writes nothing** â€” the regression test for Decision 4
- a Steam error or timeout writes nothing
- transaction retry converges rather than looping
- the duplicate-delivery test added by `auditfix-verification-coverage` now expects one
  record instead of two

## What this change deliberately does not do

- Does not alter `isMaterialChange`. Decision 4.
- Does not change the Firestore region. Permanent, per CLAUDE.md.
- Does not add a TTL or any retention bound. Explicitly forbidden â€” Steam exposes no
  historical presence, so a deleted document is unrecoverable from any source.
- Does not change security rules. Deny-all client access stays until a client reader exists
  and an auth decision is made.
- Does not make the app read Firestore. The app must work with no network and no cloud.
- Does not change the document key. Decision 2.
