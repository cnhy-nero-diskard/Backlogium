# Design

## Context

Use current `master` as the implementation base. [#188](https://github.com/cnhy-nero-diskard/Backlogium/pull/188) already shipped truthful backoff, exact-ID recovery for one active stage, onboarding Retry and Continue, and the explicit history choice. [#199](https://github.com/cnhy-nero-diskard/Backlogium/pull/199) preserved the October prototype and its evidence for reconciliation. The [implementation overlap map](implementation-overlap.md) identifies the current integration points.

## Decisions

### 1. Separate foreground observation from per-stage recovery

Retain the registered stage order and the schedulers' existing unique work and KEEP policies. Use a bounded, monotonic foreground observation budget, initially 120 seconds per foreground attempt. Settle a stage's foreground wait when work is queued, in backoff, durably detached, terminal, or when the user continues or the budget expires. Admit later independent selected stages after settlement, continuing in order after an explicit exit. The scheduler remains responsible for executing admitted work.

Persist the cohort selection and the latest attempt for each stage. Record account ownership, request and attempt identity, and the exact admitted work identity once established. Persist request intent before enqueueing. Recovery may resolve the association from the request identity or durable evidence of KEEP reuse, including when work finished immediately. When admission cannot be established, surface explicit recovery. Preserve historical outcomes and ignore stale callbacks by checking attempt, work, and account ownership at publication. Reconcile pending stages at startup after account recovery and when Settings opens.

Project each stage's latest state separately from the foreground selection. Keep pending statuses visible after settlement and distinct from terminal outcomes. Retain deliberate reruns, successful siblings, and detaching workers' existing notification behavior.

### 2. Confirm the library in the raw sync transaction

Add local, account-scoped evidence of acceptance for the owned-library response and an attributable result for the exact poll. Persist positive commit evidence with the raw library transaction so a stopped worker can recover it before output publication. The accepted poll may confirm zero games. A failed or refused poll retains prior trusted confirmation while reporting its own result. Readiness checks account ownership and pending account replacement. Credentials, restored or legacy timestamps, and arbitrary local game rows provide no confirmation.

Keep proof of a local commit and transient request/work identities out of backup payloads. Preserve trusted local confirmation for the same account through compatible restore and clear it on account reset. Keep achievement, post-play, and presence behavior in their existing paths.

### 3. Resume explicit import with canonical credit and provenance

Reuse the same import boundary from onboarding and Settings. Persist the request and its account ownership before starting work. Revalidate ownership at commit and during recovery. Continue/do later after consent defers onboarding while the admitted request stays recoverable. A decision that was skipped before consent creates no request.

Follow the existing sync → derived → Room lock order. In one transaction, read lifetime totals and canonical credited minutes through `SessionDao.creditedMinutesByGame()`, write the frozen offsets and completion flag, and record pending administrative recomputation. The historical candidate is `max(0, lifetime Steam minutes − canonical credited minutes)`. The canonical read covers dated sessions and unknown-time recovery, including quantities awaiting a date. Preserve previously frozen and shared-to-owned converted offsets without duplicating credited activity. Import produces offsets and derived XP through the existing writers.

A pending history recomputation keeps its `BACKFILL` source, request identity, and account. A concurrent sync, rule change, or backup recovery that completes it must preserve administrative presentation and finalize current derived state before clearing the marker. Retain the cloud reversal-before-reset guard, transferred quantities, and existing reset semantics.

## Risks and migration

- Migrate from current `master`'s Room schema and append a migration for the new evidence. The October prototype's versions 43 and 44 are already occupied upstream. Preserve existing data and verify upgrades with current schema fixtures.
- Map an existing active stage marker only when its exact work is attributable. Upgrade existing first-run phase data and leave configured installs without an outstanding journey out of onboarding.
- Preserve completed imports and their offsets without a new readiness check. Initialize ambiguous evidence as unconfirmed for unimported accounts and surface the next step.
- Keep pending requests recoverable across process death and test account replacement, mixed backup/import recovery, and commits on both sides of the import snapshot. Retain data on rollback and use a schema-compatible build.
