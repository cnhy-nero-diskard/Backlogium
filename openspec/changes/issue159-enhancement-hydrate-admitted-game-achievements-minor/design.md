# Design

## Context

`SteamSyncWorker` already computes the exact newly stamped app IDs during the atomic poll. `FamilySharedGameRepository` has separate automatic, manual and reversal paths. The achievement repository already coordinates detail, watch, reconciliation and ordinary sync.

## Goals / Non-Goals

Provide a durable admission handoff with bounded execution. Keep library commit latency, playtime accounting, and catalogue availability contracts intact.

## Decisions

### Capture work at the admission boundary

Use the existing arrival classification and persist pending account/game identities with the raw commit. For automatic sharing, tie the record to a successful new-row insert. A post-commit scheduler wakes the drain; startup recovery is a safety net for a missed wake, not a prerequisite for draining the tail. Scanning every zero-play row would pull first-sync, restore and old admissions into the new path.

### Keep refresh work bounded

Drain serially in admission order with a small documented per-run cap, explicit retry limit and existing retry/backoff policy. Use one durable, network-constrained unique-work chain per account/reset identity: coalesce or serialize admission wakes, and have each bounded run durably enqueue its successor before reporting success when eligible rows remain. Preserve a wake racing with the final empty-queue check so a new admission cannot be stranded. Schedule the successor for the earliest next-eligible time when remaining rows are in retry/backoff; do not retry them early. Continue bounded executions without waiting for another app start, library sync, or admission. Fence with account/reset identity at dispatch and merge, and retire obsolete work on account replacement. Check whether another eligible refresh already supplied equivalent evidence and reuse the coordinator and existing merge outcomes. Where only a catalogue is available, keep personal progress unavailable.

## Risks / Trade-offs

A missed wake must leave pending work recoverable, and a process interruption between bounded runs must not require a new user or sync trigger for progress. An interrupted or ambiguous fetch can retry, so the persisted result and event generation must remain idempotent. A permanently unavailable result is terminal for the admission cycle and normal future refresh paths remain available.

## Migration Plan

Add a forward migration for pending work if needed and start it empty. Carry no existing library into the new queue and exclude operational rows from backups. Clear obsolete rows on account reset and test released-schema upgrades.
