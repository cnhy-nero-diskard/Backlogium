# Proposal

## Why

[#188](https://github.com/cnhy-nero-diskard/Backlogium/pull/188) resolved [#158](https://github.com/cnhy-nero-diskard/Backlogium/issues/158) by shipping setup recovery and the optional history choice. On current `master`, one pending stage can still hold up later selected stages, library-import readiness relies on a sync timestamp, and history consent and recomputation lack durable request provenance.

## What Changes

- Let later independent setup stages start once the foreground wait settles. Keep each pending stage recoverable by its exact work identity after Continue or restart.
- Record per-operation library poll results and durable, same-account baseline confirmation. Accept a confirmed empty library and preserve existing confirmation through failed refreshes.
- Persist explicit history-import consent and carry its provenance through commit, recovery, and derived recomputation. Let users continue to Home while an admitted import remains recoverable in Settings.
- Freeze imported historical offsets using canonical credited minutes, including recovered play with unknown timing. Verify that importing and recovering XP preserves both existing tracked credit and prior imported offsets.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `first-run-setup` — Bounded foreground waits, independent stage admission, and recovery of multiple admitted stages.
- `onboarding-credentials` — Account-bound recovery of a consented import and durable exit while it is pending.
- `steam-sync` — Results attributable to individual polls and durable library baseline confirmation.
- `playtime-backfill` — Baseline admission, canonical credited-minute accounting, and durable request and recomputation provenance.
- `app-settings` — Reconciled setup status and shared history-import readiness and recovery.

## Impact

Work builds on the current Android setup coordinator, sync and Room boundaries, history operation, and onboarding and Settings surfaces. Scope excludes HLTB refresh tooling, achievement watching, cloud re-filing changes, and a new playtime writer. See [implementation overlap](implementation-overlap.md) for upstream contracts and [acceptance coverage](acceptance-coverage.md) for current verification.
