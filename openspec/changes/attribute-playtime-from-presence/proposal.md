# Place playtime from recorded presence

*Originally phase 4 of the cloud poller integration. This in-flight change is reconciled with
the subsequent agreed recovery and historical-range requirements; it is not a second detector
or a replacement for those capabilities.*

## Why

The original defect was crediting several days of Steam play to the last successful sync's date.
The agreed specs now address that through bounded timing evidence and honest unknown-time recovery;
this change must retain those guarantees while closing its remaining validation work.

## What Changes

- **Steam authorizes quantity; validated evidence informs timing.** New timed placement is bounded
  by the remaining authorized minutes and whole confirmed minutes of non-overlapping usable span.
  Deterministic proportional rounding applies only to that bounded share.
- **Residual minutes use `playtime-recovery`.** Missing, rejected or insufficient cloud evidence
  does not create a timestamped fallback session or lose a minute. Ordinary usable short-gap
  monitoring keeps its existing behavior without requiring cloud reads.
- **One increase can credit several dates** through timestamped sessions and/or frozen unknown-time
  allocations. Session minutes remain atomic by their start date; allocations have no invented start.
- **Re-use the existing historical re-file workflow**, including a selected bounded range, fixed
  end, complete acquisition before application, explicit confirmation, optional separately confirmed
  pre-data transfer from already-imported Steam minutes, and resumable application and reversal.
- **Corrections remain silent and quantity-conserving**, re-evaluating affected old and new dates,
  protecting longest-streak records and retaining previously owed progress events.

The delta specs modify existing requirements and retain their later safeguards. They do not
re-introduce requirements already present in the normative specs as additions. Placement-only
corrections do not change per-game credited totals, XP or levels under unchanged rules and inputs.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `steam-sync`: align the placement and date-attribution scenarios with bounded evidence plus
  unknown-time recovery, preserving the current quantity and concurrency contracts.
- `cloud-presence-reader`: reconcile the existing read/placement/re-file requirements with partial
  evidence, safe fallback, complete historical acquisition and independent subsequent corrections.
- `app-settings`: retain the existing range/cutoff, disclosure, progress and reversal contract when
  presenting re-filing, including interruption and zero-change outcomes.

## Impact

- **Existing on-device recovery path** — use `PlaytimeAttributionPlanner` and
  `PlaytimeObservationCommitter`; no second quantity detector or parallel session writer.
- **Timing acquisition** — retain the existing account/reader-generation fences, durable pending
  evidence and serialized readers. Network work stays outside the baseline/session transaction.
- **Daily progress** — correct sessions and allocations through the existing corrective recompute,
  rather than assuming the session ledger alone contains all credited activity.
- **Historical operations and Settings** — re-use the existing range acquisition, apply receipt,
  imported-balance accounting and reversal machinery.
- **Scope of this reconciliation** — audit the existing implementation and fix the planner's
  continuous-evidence path so it cannot bypass the whole-minute cap. Avoid a periodic placement
  read at the 30-minute boundary when every positive owned-game delta has an independently usable
  ordinary estimate. Fix receipt-based reversal so dates created solely by the re-file do not
  leave empty daily-progress rows and affected dates are recomputed. No schema migration, new cloud endpoint or new user operation is
  needed. Revised acceptance checks remain open until verified.

## Non-goals

- Changing Steam-authorized totals, XP rules, levels or the taper.
- Requiring cloud configuration or connectivity for local recovery.
- Splitting an existing timestamped session's minutes across dates.
- Changing family-shared quantity derivation or the separately disclosed legacy pre-data transfer.
- Automatically rewriting legacy sessions whose funded contribution/window cannot be proved.
