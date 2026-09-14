## Context

See proposal.md — Why.

The mechanism being changed is narrow and already well isolated. `SessionDiffer` computes a delta
per game and emits `SessionAction` values; `PlaytimeObservationCommitter` is documented as *the one
path from an observed playtime reading to stored sessions* and both the periodic poll and the
targeted post-play fetch commit through it. `previousPollAt` — the value that becomes a new
session's `startAt` — is the profile's `lastSyncAt`.

That single value is the whole defect. It is a good estimate of when play began when the app syncs
every fifteen minutes, and an arbitrarily bad one when the app has been unreachable, because
nothing else in the pipeline ever revisits it.

`dailyProgressCorrections` already exists as a pure function that recomputes daily progress from
the session ledger and returns only the dates that disagree. The machinery for "rewrite Saturday
and re-evaluate its quest" is built; nothing has needed to call it from here before.

## Goals / Non-Goals

**Goals:**

- Sessions land on the dates they happened, so quests and streaks reflect play rather than sync
  timing.
- The cloud can misplace a minute but can never create or destroy one.
- Every path degrades to today's behaviour rather than to a worse one.

**Non-Goals:**

- Changing XP, the taper, or any game's total. Placement is a date question.
- Improving boundary precision in the ordinary case. A fifteen-minute-old estimate is already
  within a useful margin; this is about the case where it is days old.
- Touching `SessionDiffer`. It computes the delta and stays exactly as it is.

## Decisions

### Separate the authorities: Steam says how much, the record says when

The diffed increase is distributed across the observed intervals. Minutes are never taken from the
intervals' own spans.

*Why this specific split rather than "trust the cloud's durations":* it is what makes the feature
safe to ship against imperfect data. A poller outage, a private profile, a missed transition — all
of them can misplace minutes under this rule, and none of them can invent one, because the quantity
being distributed was never derived from the presence record. The blast radius of every cloud-side
failure is bounded to "the right minutes on the wrong day," which is no worse than today's
behaviour and usually much better.

*Alternative considered:* derive sessions from the intervals directly and reconcile against the
Steam total afterwards. Rejected — that is a second detector, which `CLAUDE.md` names as
load-bearing, and reconciling two session streams with disagreeing boundaries is precisely the
logic that silently double-counts.

### Distribute proportionally to confirmed span, with an exact total

Each interval receives minutes in proportion to its confirmed span, with the rounding remainder
allocated so the written minutes sum to the diffed increase exactly. Confirmed span excludes
an unconfirmed tail, and is zero for an interval whose raw interior-gap pair exceeds the
placement rule's own tolerance: phase 1 retains only the largest step, so the pair preserves
the verdict but not the locations, and placing minutes against the portions outside the
retained span would still place them into a second outage above the same tolerance that the
pair does not locate.

*Why proportional:* it is the allocation that assumes least. The intervals say play happened in
these windows and roughly in these proportions; the total says how much. Proportional distribution
is the only rule consistent with both that requires no further evidence.

*Why an exact total matters:* the diffed increase is what `playtime_forever` will be reconciled
against on the next sync. An allocation that lost or gained a minute to rounding would make the
ledger disagree with Steam, and that disagreement would compound silently across every sync.
Largest-remainder allocation is the cheap, standard way to keep the sum exact.

*Alternative considered:* give each interval its own span as minutes and put the residual in the
last one. Rejected — it lets one badly measured interval absorb the entire error, and it produces a
final session whose length is an artefact of arithmetic rather than of anything observed.

*Recorded honestly:* proportional allocation is the rule this design commits to, and phase 2's
diagnostics surface exists partly to test it against real data. If the comparison shows the
intervals systematically disagreeing with the totals in some structured way, this is the decision
to revisit — it is contained in one pure function and one requirement.

### Placement is an optional input to the existing committer

`PlaytimeObservationCommitter` gains an optional placement input. Both observers already commit
through it, so both benefit without either learning that a cloud exists.

*Why not a separate ingest pass that rewrites sessions afterwards:* a session written with the
wrong boundaries and corrected a moment later is briefly wrong, and anything reading the ledger in
between — a recompute, a progress event evaluation, a UI observer — sees the wrong version. Placing
the minutes correctly the first time avoids an entire class of ordering question. The exactly-once
crediting story the committer documents also depends on baselines being read inside the caller's
transaction, and threading placement through it keeps that property rather than working around it.

### Fetch only when the estimate is actually poor

The presence record is consulted when the period since the previous observation is long enough that
the existing estimate could be meaningfully wrong. A fifteen-minute gap is not.

*Why:* the correction is worthless when the estimate is already good, and the sync runs every
fifteen minutes. Consulting on every run would be roughly a hundred requests a day to improve
boundaries that are already within the record's own resolution. The trigger is also the honest one:
the defect being fixed is a function of how long the app was unreachable.

### The historical sweep follows `playtime-backfill`'s shape exactly

Opt-in, one-time, idempotent, reversible, with an explicit disclosure.

*Why reuse that shape:* it is the same class of action — a user-initiated, retroactive change to
what derived values are computed from — and that capability already worked out the hard parts:
recording that it ran, not compounding on repeat, and returning to the prior state on reset. A
second retroactive action with different semantics would be a trap.

*Why the disclosure is a requirement rather than copy:* the natural expectation of "re-file a month
of more accurate history" is that progression improves. It will not. XP comes from cumulative
minutes per game, which are already correct. Letting that expectation stand would make correct
behaviour look broken.

## Risks / Trade-offs

- **A structurally wrong allocation rule** → Bounded by the separation of authorities: the total is
  always right, so the worst case is minutes on a wrong day, recoverable by reversing the sweep.
  Phase 2's comparison is the evidence to check the rule against before this ships.

- **Re-filing moves a day from met to unmet** → Possible: if play was credited to a date it did not
  happen on, correcting it removes those minutes from that date. The reversal exists for this, and
  the disclosure says quests and streaks may change rather than only improve.

- **`longest streak` is never lowered by a recompute** → An existing `app-settings` requirement, and
  the sweep must not violate it. The retroactive provenance reseeds baselines rather than moving
  high-water marks, which is the mechanism that already protects this.

- **A sync that now depends on a network call** → It does not. Placement is optional input; an
  unavailable record leaves the sync attributing minutes exactly as today. Verified by a test that
  runs a full sync with the reader failing.

- **The pre-phase-1 era has no coverage** → Those intervals still place minutes onto the right
  dates, which is where nearly all the value is; only the within-interval precision is uncertain.
  Since the total is fixed regardless, unknown coverage costs far less here than it does for shared
  games in phase 3.

- **Interaction with the targeted post-play fetch** → Both commit through the same path, and a
  targeted fetch's observed play instant is already supplied by its caller. The placement input must
  not override that deliberately-supplied instant; a test covers a targeted fetch arriving while a
  placement is available.

## Migration Plan

No schema change. Placed sessions are ordinary rows.

**Sequencing:** ships after phase 2 is live and its comparison has been reviewed against real data,
and after phase 3 has proven the retroactive provenance on a narrower change.

**Rollout:** placement during sync takes effect as soon as the feature is configured, and is
inherently incremental — it only affects windows where the app was unreachable. The historical
sweep is a separate, explicit, reversible action the user takes when they choose.

**Rollback:** reversing the sweep returns attribution to its prior state. Removing the cloud
configuration stops placement at the next sync and leaves recorded history alone.

## Open Questions

- What "long enough to be worth consulting" should be in practice. It is one threshold in one pure
  predicate, it changes no requirement, and the right value is best chosen from the read volume
  phase 2 actually observes.
