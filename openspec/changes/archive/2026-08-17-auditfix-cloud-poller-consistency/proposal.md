## Why

`recordObservation` reads the player document, decides whether the game changed, and
commits with a batch (`presence.ts:64`, `:69`, `:83-105`). A batch is atomic but it is not
isolated — it carries no assertion that the state read at `:64` still holds at `:105`. Two
overlapping invocations can both read the same previous state, both conclude the same
transition is new, and both write.

**The exposure is different from what the audit describes, and smaller — but it is real.**
The audit attributes the risk to Cloud Scheduler redelivery. `index.ts:33` sets
`retryCount: 0`, which removes function-level retries, so redelivery is not the main path.
The actual overlap window is structural and sits in plain sight:

```
  index.ts:24   schedule: "* * * * *"     ← a new invocation every 60s
  index.ts:30   timeoutSeconds: 60        ← an invocation may run for 60s
```

An invocation that runs long — a slow Steam response inside its timeout — is still alive
when the next one starts. Both then read the same `players/{steamId}` document. Nothing
in the code or the configuration prevents it.

**A second, separate defect: the idempotency comment is false.** `presence.ts:76-78` states
that keying the presence document by observation time "makes the write idempotent: Cloud
Scheduler delivers at least once, and a redelivery for the same instant overwrites rather
than appends." But the key comes from `observation.t`, which `steam.ts:58` mints as
`new Date()` at the start of each fetch. No two invocations ever produce the same key, so a
redelivery appends rather than overwrites. The mechanism described does not exist.

That comment matters more than the average wrong comment. Retention here is indefinite by
design because Steam exposes no history, so a duplicate transition record is permanent, and
the next person to reason about this file will trust the stated guarantee.

## What Changes

- **Transition recording becomes a Firestore transaction.** The read of current state and
  the write of the new state become one atomic, isolated operation, so a concurrent
  invocation either sees the committed transition or retries against it.
- **Successful observations advance a durable ordering watermark.** Same-game polls update
  `lastObservedAt` without appending history, so a newer unchanged observation can prevent an
  older stalled transition from rolling the current state backward.
- **The presence document key stops depending on a fabricated timestamp.** Keyed instead by
  something two invocations observing the same transition agree on, so a duplicate write
  genuinely overwrites rather than appending. Design covers the options and their costs.
- **The false comment is replaced by an accurate one**, stating what is actually guaranteed
  and under which conditions.
- **Overlapping invocations are prevented where the platform allows it**, so correctness
  does not rest solely on the transaction retrying.
- **Behavioural tests arrive with the fix.** `auditfix-verification-coverage` establishes the
  test harness for `functions/` and adds a test asserting today's duplicate behaviour as a
  known defect; this change inverts that expectation.

## Capabilities

### Modified Capabilities

- `cloud-presence-poller`: require that transition recording is atomic with respect to the
  state it was decided from, and that recording the same observation more than once cannot
  produce more than one transition record.

## Impact

| Path | Change |
|---|---|
| `functions/src/presence.ts` | `db.batch()` → `runTransaction`; ordering watermark; document key; comment corrected |
| `functions/src/steam.ts` | observation timestamp semantics, if the key changes |
| `functions/src/index.ts` | invocation-overlap controls |
| `functions/src/*.test.ts` | duplicate-delivery expectation inverted |

**Deployment constraints, both non-negotiable and both already documented in CLAUDE.md**:
Firestore is in `asia-southeast1` permanently and the function must deploy to the same
region; retention is indefinite and no TTL may be introduced. Neither is affected by this
change, and both should be re-read before touching this file.

**The persona-state exclusion must survive untouched.** `isMaterialChange` deliberately
ignores `personastate` because Steam cycles idle accounts through away and snooze on its
own, which previously fragmented continuous sessions. It is a load-bearing constraint with
a documented history. A transaction rewrite is exactly the kind of change that quietly
"tidies" a comparison function.

**Fully independent of the Android changes.** Separate toolchain, separate deploy, no shared
code. Can proceed in parallel with anything except `auditfix-verification-coverage`, which
it wants first for the test harness.

**Not addressed here**: Firestore security rules (still deny-all client access, correct
until a client reader exists), and whether the app should read this data at all — the app
must continue to work with no network and no cloud.
