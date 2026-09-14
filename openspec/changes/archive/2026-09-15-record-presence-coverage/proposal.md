# Record presence coverage

## Why

The presence log records *transitions*, never *coverage*. A reader can see that game X began at
T1 and that the state changed at T2, but nothing in the log says whether the poller was alive for
the interval between them. A deploy, a Steam outage, a revoked key, an exhausted quota and an
uninterrupted three-hour session all produce exactly the same log.

That ambiguity costs nothing today, because nothing reads the log. It is the first thing in the way
of everything that will: a reader reconstructing when play happened would credit hours nobody
played, with no signal that it was guessing.

It cannot be repaired after the fact. Retention is indefinite precisely because Steam exposes no
historical presence, so a day recorded without coverage information is permanently ambiguous — the
evidence needed to disambiguate it does not exist anywhere, then or later. Every day before this
lands is a day no future reader can fully trust, which is why it is worth landing ahead of the
reader it exists to serve rather than alongside it.

## What Changes

- **Each transition records the last confirmed observation of the state it replaces.** The
  poller already reads that value inside the transaction it writes in — it is the stored
  `lastObservedAt`, the watermark the staleness check is made against. It is currently overwritten
  and discarded. Writing it onto the transition document instead is what makes the log
  self-describing: a reader comparing it against the transition's own timestamp can tell an
  interval observed continuously from one whose tail is unknown.
- **The schema version stamp becomes per-document-shape.** Transition documents go to `v: 2`.
  The current-state document keeps `v: 1`, because its shape does not change — it already carries
  `lastObservedAt`, which is its own coverage.
- **Nothing else changes.** No new write, no additional read, no change to *when* a transition is
  appended, no change to the current-state document, no change to what the logs carry.

Additive, not breaking: a reader that ignores the new field behaves exactly as one reading `v: 1`.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `cloud-presence-poller`: a new requirement that a transition records the coverage of the interval
  it closes, so poller downtime is distinguishable from continuous observation. The existing
  *Schema version stamp* requirement changes from a single value covering both document shapes to a
  version per shape.

## Impact

- **`functions/src/presence.ts`** — one field added inside the existing transaction. The value is
  already in hand there; no extra read.
- **`functions/src/steam.ts`** — `SCHEMA_VERSION` splits into one constant per document shape.
- **`functions/src/presence.test.ts`** — coverage cases against the existing `FakeFirestore`.
- **`functions/README.md`** — the recorded shape and what the field means to a reader.
- **Deploy:** `firebase deploy --only functions`. The function stays in `asia-southeast1`.
- **No Firestore rules change.** There is still no client reader, so `allow read, write: if false`
  stands and the *Client access is denied* requirement is untouched.
- **No Android change of any kind.** `functions/` is invisible to Gradle, so this cannot affect the
  app build, the APK, the test suites, or any in-flight branch.
- **No backfill and no rewrite.** Existing documents stay `v: 1` and are read as coverage-unknown
  in perpetuity. That is the honest answer — the information to fill them in never existed.

## Non-goals

- **The reader.** No consumer of this data, in the app or anywhere else. This change exists so the
  reader has something trustworthy to read when it arrives.
- **Recording per-poll liveness as its own document stream.** A heartbeat document per minute is
  43,200 rows a month to answer a question two fields on an existing document already answer, and
  it would defeat the write-on-game-change rule the log is built around.
- **Changing when a transition is appended.** Game-change-only stays exactly as specified.
- **Recovering play that happened entirely inside a poller gap.** That is unrecoverable. The point
  of this change is that the log will say so rather than silently inventing it.
