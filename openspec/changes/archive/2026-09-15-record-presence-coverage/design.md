## Context

See proposal.md — Why. The constraints that shape the approach:

`recordObservation` already runs a Firestore transaction that reads the current-state document and
uses its `lastObservedAt` for the staleness check (`presence.ts`, `isStaleOrEqualObservation`). On a
material change it then overwrites that field and discards the value it read. The information this
change needs is therefore already in hand at the moment the transition is written; nothing has to be
fetched, and nothing has to be inferred.

Three inherited constraints matter here:

- **Retention is indefinite and the log is the only copy.** Steam exposes no historical presence, so
  a document written without coverage information can never be corrected. This is the reason the
  change is worth making before its consumer exists rather than with it.
- **The poller derives nothing.** *The poller does not derive sessions* forbids the poller writing
  any computed value. Coverage must be recorded as observation, not as a conclusion about one.
- **Roughly five weeks of `v: 1` transitions already exist** in the deployed database and will
  coexist with everything written from now on, permanently.

## Goals / Non-Goals

**Goals:**

- The log is self-describing: an interval's trustworthiness is readable from the documents alone,
  with no out-of-band knowledge of when the function was deployed or down.
- The reader's tolerance for what counts as a gap stays the reader's decision, not a threshold
  frozen into permanent history.
- Documents already written remain valid and readable, and say honestly that they know nothing
  about coverage.

**Non-Goals:**

- Deciding what a reader does with the value. Whether a two-minute lapse invalidates an interval is
  a consumer's judgement and is out of scope here.
- Any account of *why* observation lapsed. The log records that it did; the cause belongs to
  operational logging and the `poll ok` heartbeat.

## Decisions

### Record the replaced state's watermark, not a gap, a duration, or a flag

The field is `prevLastObservedAt`: a raw observation timestamp, the stored `lastObservedAt` of the
state the transition replaces.

*Alternatives considered:* a boolean `continuous`, a gap length in milliseconds, or a coverage
proportion. All three are rejected for the same reason, and it is not a stylistic one. Each requires
the poller to decide *how long a lapse has to be before it matters* — and that threshold would be
baked irreversibly into a permanent log at whatever value seemed right today. The equivalent
judgement on-device is `PresenceSessionDeriver.DEFAULT_GAP_TOLERANCE_MILLIS`, a tunable constant
carrying a nine-line comment arguing for its value, precisely because it is the kind of number that
gets revisited. A raw pair of timestamps lets any reader, now or in ten years, apply its own
tolerance to history it did not write.

A computed gap is also a derived value, which *The poller does not derive sessions* prohibits
outright. Two timestamps are observations; the subtraction between them is the reader's.

### Take the value from the transaction that already read it

The write uses the `previous` snapshot the transaction has already fetched. No second read, no
second query, no additional cost per invocation.

This is not only an efficiency point. Reading it inside the same transaction means the coverage
value and the transition decision describe the same stored state under the same isolation, so
*Transition recording is atomic with the state it was decided from* extends to cover the new field
for free.

*Alternative considered:* reading the watermark outside the transaction. Rejected — it could observe
a different value than the one the change decision was made against, producing a transition whose
coverage claim describes a state it did not actually replace. That is worse than no claim at all.

### Retain the largest same-game step, with no tolerance cutoff

A same-game observation that advances the watermark retains the largest
consecutive-observation step seen within the state — the stored watermark as
`coverageLapseFrom`, its own time as `coverageLapseRecoveredAt` — and a later
step replaces the retained pair only when strictly longer, with ties keeping
the earlier pair. The transition that closes the state copies the retained
pair across. The poller applies no minimum span before retaining: a
one-minute step is kept exactly like an hour-long one, and whether either
counts as a lapse is the reader's decision.

*Alternatives considered:* a fixed cutoff (retain only spans beyond N minutes); retaining
the first step; widening the pair across steps (earliest start, latest recovery);
always keeping the latest step. The cutoff is rejected as the threshold this design
exists to avoid — the decision to omit a two-minute span from permanent history is a
materiality judgement, identical in kind to writing a gap length, and it would bind
every future reader to today's tolerance. First-wins is rejected because in normal
minute-cadence operation the first retained pair is usually a harmless ~1-minute step,
which would freeze the record and make a later real outage invisible: the closing tail
watermark only proves the final stretch was fresh, so the history would read as
continuously observed. Widening is rejected because it synthesises a span no two
consecutive observations bound: a long, perfectly observed session would report its
whole length as one unobserved span. Latest-wins is rejected because each on-cadence
poll would overwrite the evidence of the lapse that preceded it, re-opening the hole
the retention exists to close.

Comparing spans to select which raw pair to keep writes no duration, gap length, or
verdict — the stored values stay raw observation timestamps, so the selection is not
a derived value under *The poller does not derive sessions*.

The accepted rule has a known limitation, stated here so it is not discovered later:
two fields cannot record every span, so smaller steps within one state are not
separately recorded. The retained pair is always a genuine consecutive-observation
step, never a synthesis — and because it is the largest such step, a reader applying
any tolerance to it reaches the same lapse verdict as if it had seen every step:
`max(gaps)` preserves the verdict, not the locations. A downstream consumer that
must exclude unconfirmed spans therefore cannot surgically exclude only the
retained span: once the retained span exceeds its tolerance it must treat the
whole replaced state's interval as unconfirmed, because a second span above the
same tolerance may have been discarded (for example `A@t0 → A@t1 → outage →
A@t10 → A@t11 → outage → A@t18 → B@t19` retains only the largest pair while both
interior outages exceed a two-minute tolerance). The
tail watermark still bounds the change itself, so no state can read as continuously
observed when its closing stretch went unseen.

### Version per document shape, not per system

`SCHEMA_VERSION` splits into one constant per shape: the current-state document becomes `2`
(it gains `coverageLapseFrom` / `coverageLapseRecoveredAt`), presence transitions become `3`
(they gain `prevLastObservedAt` plus the copied `prevCoverageLapseFrom` /
`prevCoverageLapseRecoveredAt`).

The current-state version advances here precisely because its shape changed — that is the
rule working as intended, not an exception to it. A reader branching on that document's
version learns a new field exists; with indefinite retention and an unknown number of
future shape changes, a single shared version would instead punctuate every shape's history
with versions that mean nothing to it. The cost of the split is that a reader must know
which version namespace applies to which collection — but the collections are already
distinct paths holding distinct shapes, so the version now matches a boundary that was
always there.

*Alternative considered — leave both at `1` and let readers detect the field's presence.* Rejected
as exactly what the requirement exists to prevent: "so that a reader can identify the shape without
inferring it from which fields are present."

### Absent, not null or zero, when there is no prior state

The first transition written for a player has no predecessor (`previous === undefined`), so it omits
the field entirely.

The codebase does write explicit nulls elsewhere — `gameid` and `gameName` are `null` when Steam
reports no game — so absence is a deliberate departure rather than a default. The distinction is
that a null `gameid` is a recorded fact ("observed; the answer is no game"), whereas a first
transition has no prior observation to record at all.

The decisive argument is the reader, not the semantics: a `v: 1` document expresses "no coverage
information" by the field being absent, because it predates the field. Making the first-transition
case absent too means the reader has exactly **one** branch for "nothing is known about coverage
here" rather than two that have to be kept in agreement. It is the same distinction
`SessionRepository` already draws on-device — "never had a first session" and "first session at the
epoch" are different answers.

The same rule governs the retained pair: a transition whose replaced state never saw a
same-game poll advance its watermark — a direct transition with no recovery step in between —
omits `prevCoverageLapseFrom` / `prevCoverageLapseRecoveredAt`, and a current-state document
that has never advanced past its opening observation omits `coverageLapseFrom` /
`coverageLapseRecoveredAt`. Absence of the pair alongside a present watermark is therefore
positive evidence of a clean handoff, not a gap in the record.

### Drop `v` from the in-memory `Observation`

`steam.ts` stamps `v: SCHEMA_VERSION` onto the `Observation` it builds, but `presence.ts` never
persists it: all three write sites name the version explicitly. Under per-shape versioning the field
has no single correct value, because an `Observation` is not a document and does not have a shape
version. It is removed rather than given an arbitrary one.

*Alternative considered:* keep it, meaning "the observation shape's version". Rejected — nothing
reads it, nothing writes it out, and a version number describing no persisted shape is a value that
can only ever be wrong.

### No backfill of existing documents

The five weeks of `v: 1` transitions are left exactly as they are, as are the `v: 2`
transitions and `v: 1` current-state documents written by the earlier revision of this
change: no retained pair is synthesised for any of them.

There is no honest value to write. The current-state document holds one watermark — the latest — so
the watermarks those transitions would have recorded are gone. Synthesising a plausible value would
be fabricating evidence in the one collection whose entire purpose is to be evidence.

## Risks / Trade-offs

- **A reader treats an absent field as continuous observation** → The spec states the contract
  explicitly and makes it a scenario. The field's absence is a positive signal of ignorance rather
  than a default that resembles success, and every consumer arrives after this change, so none can
  predate the rule.

- **The field invites a derived `gapMillis` companion later** → The *Coverage is recorded, never
  derived* scenario is a test rather than only prose, so the prohibition fails a build rather than a
  review.

- **Two version constants drift apart or get crossed** → Both are exported from one module and used
  at three write sites in one file; tests assert the version on each document shape independently,
  so a crossed constant fails immediately.

- **Nothing consumes this, so a regression is silent** → Unchanged from the poller's existing
  condition, and the reason the unit tests carry the weight here. The `poll ok` heartbeat and its
  metric-absence alert are untouched by this change and keep covering the pipeline itself.

- **The redeploy creates the first recorded lapse** → Expected, not a defect. The function is down
  for a poll or two during deployment, and if a game is running across it the next transition will
  honestly record a short unobserved tail. Worth recognising so the first coverage gap seen in
  production is not investigated as a bug.

## Migration Plan

No data migration — the change is additive and touches no existing document.

**Deploy:**

```bash
npm --prefix functions run build
firebase deploy --only functions
```

The function stays in `asia-southeast1`; the Firestore location is permanent and unchanged. No
`firestore.rules` deploy: there is still no client reader, so `allow read, write: if false` stands.

**Verify, in this order:**

1. Confirm `poll ok` heartbeats resume after the deploy — the pipeline is healthy before anything
   else is judged.
2. Start a game, wait for a transition, stop it. The appended transition carries `v: 3`, a
   `prevLastObservedAt` within roughly one polling interval of its own `t`, and — once a
   same-game poll has advanced the replaced state — the retained pair alongside it.
3. Confirm the current-state document reads `v: 2`, carrying the retained pair once a
   same-game poll has advanced it, and that `since` / `updatedAt` are otherwise unchanged.
4. Confirm an unchanged poll still appends nothing.

**Rollback:** redeploy the previous revision. New transitions revert to `v: 2` with
`prevLastObservedAt` but no retained pair, and current-state documents revert to `v: 1` —
both shapes the reader contract already defines. A rollback therefore costs the interior-span
resolution from that point forward and breaks nothing — including for documents written while
the new revision was live, which stay valid.

## Open Questions

- Whether a reader should surface coverage as the raw timestamp pair or as a normalised verdict
  ("observed" / "tail unknown"). Genuinely deferrable: it is the consumer's presentation decision,
  it changes nothing recorded here, and it cannot invalidate this spec or these tasks.
