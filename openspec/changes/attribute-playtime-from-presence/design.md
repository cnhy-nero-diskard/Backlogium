## Context

See proposal.md — Why.

The original design predates the archived `recover-missed-playtime` and
`choose-cloud-presence-refile-range` changes. Their requirements are already normative in
`playtime-recovery`, `steam-sync`, `cloud-presence-reader` and `app-settings`. This reconciliation
retains those contracts rather than restoring the earlier all-or-nothing session estimate.

`SessionDiffer` remains the quantity authority. The existing `PlaytimeAttributionPlanner` accepts
authorized minutes and validated timing candidates, and `PlaytimeObservationCommitter` is the
shared commit boundary for periodic and targeted observations. A long-gap increase can now become
sessions, frozen day-level allocations, or pending date-unknown quantity. Corrective daily progress
therefore uses the canonical credited-activity view, not just the session ledger.

The existing historical workflow already stages a user-selected range with a fixed end, confirms
optional pre-data transfer separately, retains an application receipt and supports reversal. This
change neither adds another sweep nor removes its acquisition and crash-recovery safeguards.

## Goals / Non-Goals

**Goals:**

- Validated timing places only the share it supports; uncertainty remains visible for the rest.
- Evidence-informed and unknown-time credit conserve the Steam-authorized quantity exactly.
- Cloud failure leaves the existing local recovery path available, without fabricated boundaries.

**Non-Goals:**

- Changing XP, the taper, or any game's total. Placement is a date question.
- Replacing ordinary usable short-gap monitoring or changing its eligibility rules.
- Adding a quantity detector, parallel writer, historical operation or schema migration.
- Changing implementation beyond the continuous-evidence cap fix identified in the audit.

## Decisions

### Separate the authorities: Steam says how much, the record says when

The diffed increase authorizes quantity. Confirmed non-overlapping spans provide timing capacity
and proportional weights, never additional owned-game minutes. Already-funded contributions retain
their quantity and placement and are counted once.

*Why this specific split rather than "trust the cloud's durations":* it is what makes the feature
safe against imperfect data. An outage or missed transition cannot authorize quantity. Rejecting
bad evidence and retaining an explicitly uncertain residual avoids presenting an arbitrary guess
as a confirmed gameplay boundary.

*Alternative considered:* derive sessions from the intervals directly and reconcile against the
Steam total afterwards. Rejected — that is a second detector, which `CLAUDE.md` names as
load-bearing, and reconciling two session streams with disagreeing boundaries is precisely the
logic that silently double-counts.

### Bound proportional placement and recover the residual

Use the existing recovery planner. Validate account, reader generation, ordering and coverage;
clip candidates to the retained per-game observation window, union overlapping usable spans and
subtract already-consumed spans. Unknown coverage and unconfirmed tails supply no timing. An
interior-gap pair above tolerance rejects the entire interval, including a fresh tail, because
the retained largest gap cannot locate every outage. Sub-tolerance gaps remain uncovered cuts.

For remaining authorized quantity R and total usable span S, timed placement receives at most
`min(R, floor(S / one minute))`. Distribute that bounded share with deterministic largest-remainder
rounding; place the residual through the existing unknown-time recovery rules. For example,
105 authorized minutes with 35 usable minutes produce 35 timed and 70 unknown-time minutes,
not 105 minutes squeezed into the observed interval.

Apply the same cap when validated continuity would otherwise open or extend a session. For
example, a 36-minute Steam increase across a fully confirmed 35-minute observation window may
credit only 35 minutes as timed; recover the remaining minute as unknown-time. Retain the
ordinary same-date short-gap estimate and its existing rounding guard when that estimate is
independently eligible without timing evidence.

Fallback retains the existing frozen recovery dates, allocation zone and uncertainty label. A
clock anomaly with no valid date window retains pending date-unknown quantity. It never invents
session boundaries or silently extends a stale open row. Sessions remain atomic by start date;
day allocations have no session start to satisfy that rule.

*Why bounded proportional allocation:* weighting conserves quantity but does not itself prove
timing. The cap prevents a small observed fragment from claiming an entire multi-day increase.

*Alternative rejected:* allocate the whole delta to any positive span or to a final catch-all
session. Both conserve quantity while overstating timing evidence. Missing timing instead stays
explicitly unknown under `playtime-recovery`.

### Placement is an optional input to the existing committer

Keep optional timing input on the existing `PlaytimeObservationCommitter` path. Both observers
already commit through it. The recovery planner, rather than a separate legacy all-delta allocator,
handles new owned-game placement and fallback together.

*Why not a separate ingest pass that rewrites sessions afterwards:* a session written with the
wrong boundaries and corrected a moment later is briefly wrong, and anything reading the ledger in
between — a recompute, a progress event evaluation, a UI observer — sees the wrong version. Placing
the minutes correctly the first time avoids an entire class of ordering question. The exactly-once
crediting story the committer documents also depends on baselines being read inside the caller's
transaction, and threading placement through it keeps that property rather than working around it.

### Fetch only when the estimate is actually poor

Retain the existing 30-minute cloud-consultation threshold and the recovery eligibility guards.
Ordinary same-date short-gap monitoring requires usable timing, a plausible delta, and no clock
anomaly or publication-delay conflict. Across midnight, a stale open row alone is not continuity
evidence. The consultation threshold is not permission to bypass those guards.

*Why:* the correction is worthless when the estimate is already good, and the sync runs every
fifteen minutes. A cloud call on every run would add work without making cloud configuration
necessary for quantity recovery. Retain pending evidence before advancing any read position,
combine retained evidence with unread pages, and prune it only after the relevant baseline commit.

### Re-use the bounded historical range workflow

Use the existing available-beginning lookup, selected effective start and fixed end. Acquire pages
in bounded user-started batches with historical progress separate from ordinary read progress;
paused, capped or failed acquisition must not apply a partial re-file. Apply only a completely
acquired and explicitly confirmed range.

Pre-data dating is separately opt-in, requires an already-imported Steam balance and an explicit
cutoff with visible timezone/instant, and transfers no more than both that balance and eligible
non-overlapping whole minutes. The disclosed closed-v1-transition estimate exception belongs only
to that transfer; unknown or legacy coverage cannot supply ordinary existing-session placement.

Retain the original session/offset deltas and effective range/cutoff in the existing receipt so an
interrupted apply resumes the same operation rather than recalculating against new balances.
Reversal restores only this operation's changes, preserves later ordinary play and independent
recovery upgrades, and is required before selecting another range or resetting a transferred import.
Unprovable legacy contribution/window mappings remain untouched by automatic recovery.

*Why reuse rather than simplify:* complete acquisition, balance accounting and replay-safe reversal
are already agreed behavior. A second sweep would lose those safeguards and duplicate state.

Settings retains the existing disclosures and reports selected/effective/covered ranges, cutoff,
session/date counts, transferred minutes and remaining imported minutes, even for zero changes.

## Risks / Trade-offs

- **A structurally wrong allocation rule** → Bounded by the separation of authorities: the total is
  always conserved; bound timed placement and label residual uncertainty rather than claiming
  that conservation alone proves the dates. Real-data comparison remains required.

- **Re-filing moves a day from met to unmet** → Possible: if play was credited to a date it did not
  happen on, correcting it removes those minutes from that date. The reversal exists for this, and
  the disclosure says quests and streaks may change rather than only improve.

- **`longest streak` is never lowered by a recompute** → An existing `app-settings` requirement, and
  the sweep must not violate it. The retroactive provenance reseeds baselines rather than moving
  high-water marks, which is the mechanism that already protects this.

- **A sync that now depends on a network call** → It does not. Placement is optional input; an
  unavailable record leaves the sync attributing minutes exactly as today. Verified by a test that
  runs a full sync with the reader failing.

- **Legacy logs lack modern coverage** → Reject them for routine placement. Only the separately
  disclosed, explicitly confirmed closed-v1 pre-data transfer may use transition timing estimates.

- **Interaction with the targeted post-play fetch** → Both commit through the same path, and a
  targeted fetch's observed play instant is already supplied by its caller. The placement input must
  not override that deliberately-supplied instant; a test covers a targeted fetch arriving while a
  placement is available.

## Migration Plan

The targeted planner fix introduces no schema or data migration.
All delta requirements are modifications of existing blocks and retain their complete scenarios;
archive must not overwrite later recovery or range safeguards with the original phase-4 wording.

**Verification:** retain prior completion history for unchanged tasks, reopen materially changed
acceptance criteria and leave the real-data checks open. Validate the delta structure, review it
against the normative specs and then use the apply workflow to audit implementation/tests. No
implementation verification is implied by this document edit.

**Rollback:** reverse a completed historical operation through its existing receipt-aware flow,
preserving independent later changes. Removing reader configuration stops cloud assistance and
clears its pending evidence without deleting credited history or disabling local recovery.
