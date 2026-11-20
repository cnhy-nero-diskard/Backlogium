## Context

See `proposal.md` for motivation and issue traceability. This is cross-cutting persistence, scheduling, and onboarding work, so the conditional design artifact applies.

The current setup registry wraps library sync, artwork, and the shared HLTB dataset. `SetupCoordinator` isolates runner exceptions, but `WorkStageRunner` returns a stored failure when a job enters retry backoff. One active-stage/work-id marker cannot retain multiple pending jobs once observation advances. Recovery is only loaded through `SetupViewModel.prepare()`, not at startup. Onboarding hides per-stage Retry; `setupStagesUi()` reads the completed run's selection even when edits update the next-run selection.

`SteamSyncWorker` sometimes returns scheduler success after a not-performed domain operation. Its raw transaction already commits `steamId` and `lastSyncAt`, including an explicitly confirmed empty response, but the shared history-import use case does not test readiness. Import currently holds only `DerivedStateWriteCoordinator`, writes game offsets and the profile flag in separate transactions, and recomputes afterward. Account change, raw sync, and cloud re-filing use sync coordination before derived coordination. Backup recovery already supplies a useful Room-marked pending-recompute pattern, but its `RESTORE` source cannot silently stand in for an explicit `BACKFILL` request.

Current main specs and source are authoritative. The still-active `attribute-playtime-from-presence` change shares sync and Settings files, but its delta requirement names differ from this change. Preserve the already-landed placement, imported-play transfer, reversal, and post-play protocols; do not reapply older planning assumptions.

## Goals / Non-Goals

**Goals:**
- Make scheduler state, domain outcome, foreground observation, and first-run journey separate concepts with independent persistence and tests.
- Restore every admitted operation by exact identity, without treating stale results as current or requiring the user to remain on a screen.
- Keep one import implementation and one derived-value author, with a coherent account-scoped raw snapshot and recoverable recomputation.
- Keep Compose and ViewModels on domain/repository models and preserve the incumbent Material 3 checklist presentation.

**Non-Goals:**
- No global sync scheduler rewrite, new frequent polling loop, bypass of backoff, or cancellation of unrelated work.
- No fourth default-selected setup stage for import. Import is a separate explicit consent decision, not implied by ticking a fetching stage.
- No cloud acquisition, dated pre-install history, achievement-watch implementation, broad progress-bar project, or navigation/insets redesign.
- No requirement that all jobs finish before Home becomes usable.

## Decisions

### 1. Model foreground settlement separately from operation completion

Replace the single `running/finished` interpretation with a foreground-attempt model and per-stage operation observations. The foreground attempt owns an immutable selected set and registered admission order; it does not hold a mutex across the full lifetime of a background retry. Each row projects the latest operation state independently.

```text
foreground attempt: choose -> admit in order -> observe briefly -> settled
                                         |                       |
                                         |                       +-> edit next selection / retry / continue
                                         v
operation: never run -> queued/waiting -> running -> succeeded
                               ^            |       failed / cancelled
                               +-- retry scheduled

first run: credentials -> setup -> history choice -> complete/deferred -> Home
```

An admitted queued/blocked job or a retry scheduled after an attempt immediately settles that stage's foreground wait while preserving its real operation state. A running in-screen stage remains observed until terminal state, explicit Continue/do later, or a bounded foreground observation budget (initial implementation: 120 seconds measured monotonically, injectable in tests). Budget expiry is not job failure or cancellation. Detached jobs settle their foreground admission once an exact job association is durable and continue under worker-owned notifications. Later independent selected stages are admitted in registry order after foreground settlement; detached work can overlap.

Explicit Continue records the new journey phase and ends the wait, not the admission intent. Already-selected remaining stages are still admitted in order; merely leaving does not deselect them. Errors in observation are recorded per stage with a recovery explanation and cannot leave the foreground claim pinned indefinitely. Storage/admission errors must not claim durable success.

**Alternatives rejected:** observing every job until terminal reintroduces an offline trap; retaining `Failed` for backoff leaves false durable results; a fixed sleep followed by a success badge invents completion.

### 2. Persist one latest attempt record per stage and reconcile outside UI lifetime

Extend the setup store with a versioned record keyed by stable stage id: opaque attempt generation, setup cohort id, owning account identity, requested/admitted work identity and unique name, latest operation state/reason, and foreground-settlement information. Retain only the latest stage association; existing diagnostic facilities hold investigation evidence rather than an unbounded second work history. Registry projection still tolerates unknown/removed stages.

Persist the admission intent before enqueue; obtain an exact admission handle after the existing scheduler's enqueue operation completes. The handle must distinguish the newly requested job from a live job retained by `KEEP`, even if it finishes quickly. Never choose an arbitrary historical finished record as the new attempt. Use a narrow scheduler/runner admission seam with an explicit reuse result, rather than duplicating unique-work policies in setup. Recovery of an interrupted admission reconciles its recorded request identity and unique name before any retrigger. If no admitted operation can be established, mark recovery required and offer an explicit new request; do not loop indefinitely or guess success.

The coordinator maintains observation jobs keyed by stage id. Every callback verifies stage generation, work identity, and active account; superseded callbacks are discarded. On a subsequent same-stage request, reattach existing live work according to its policy rather than cancelling/replacing it. No unrelated operation is cancelled to speed up onboarding. Manual and periodic Steam unique names remain separate; existing raw-commit deduplication remains authoritative.

After account-change startup recovery succeeds, eagerly load/reconcile setup records on the application scope, independent of whether onboarding is owed. Reconcile again on Settings entry. WorkManager remains durable execution; this startup hook restores observation and remaining saved admissions, not a new polling service. A user who already chose to continue remains in Home on cold launch.

**Alternatives rejected:** a single active marker loses previous pending associations; latest-by-unique-name guesses can attribute old results; screen-owned observers cannot reconcile after explicit exit and process death; inventing setup-owned data-fetch workers duplicates existing operations.

### 3. Keep last results and next-run selection distinct

During a foreground attempt, render its immutable selection and disable selection editing. At settlement, expose a separate next-run selection, initially empty for a new deliberate run, while retaining all reconciled row states. Checkbox state and `start()` consume the same pending selection; `finished` never forces checkbox state back to the previous attempt.

Onboarding and Settings use the same recovery affordances after settlement: Failed/Cancelled/Recovery required offer Retry; Succeeded offers deliberate Run again; queued/running/retry-scheduled rows show their real status and a progress/reobserve action, not a misleading immediate Retry now. A re-request for pending work attaches under its existing policy. Serialize admission claims and reject duplicate taps at the coordinator boundary, not only with UI flags.

The initial onboarding selection can record deselected stages as skipped. Subsequent subset runs and per-stage retries replace only requested records and cannot overwrite successful siblings as skipped. Summaries say that the foreground checklist has settled and list pending operations; reserve all-work completion language for all-terminal records.

**Alternative rejected:** simply enabling the hidden Retry buttons retains both the backoff misclassification and the completed-selection bug.

### 4. Add attributable poll results and explicit confirmed-baseline evidence

Expose a compact domain result for each library-sync operation: committed (including confirmed empty), not performed with reason (privacy/unconfirmed response, missing credentials, account admission), or recoverable failure. Bind results to exact worker/account identity through persisted worker output or a durable operation-result record; do not use the profile's latest error as a result for a particular job. `ENQUEUED` with attempts remains retry scheduled. For new jobs, record any evidence needed to classify a crash after raw commit but before worker output; legacy finished jobs without sufficient domain evidence become historical/unknown rather than fabricated success.

Introduce explicit local confirmed-baseline evidence (account plus confirmation timestamp) written in the accepted raw library transaction, including `game_count: 0`. Readiness is Confirmed for the active account with no pending account reset; otherwise Unknown/Needs sync. Failed refreshes preserve existing confirmation. Credentials and nonempty local rows are not readiness evidence. Existing `lastSyncAt` supports investigation, but a generic/restored timestamp is not itself sufficient provenance for a new import.

Reset confirmation with account-owned state. Do not copy new local confirmation from an unverified backup or import transient setup/job identities as backup state. Upgrading an unimported legacy library initializes readiness conservatively until an accepted poll; completed imports remain completed without requiring another poll. Surface this one-time readiness requirement explicitly rather than silently refusing an import.

HLTB dataset acquisition remains independent: it can store the shared dataset without a library, and later lookup uses the stored dataset. Artwork snapshots the available inventory; if sync was deferred and there are zero items, report completed with zero available items plus a re-run-after-sync explanation, not that a populated library was downloaded. Neither case is a cascading failure. This change does not add silent dataset checks or automatic artwork downloads beyond explicit setup requests.

**Alternatives rejected:** scheduler success mistakes intentional no-ops for completed imports; a nonempty-row predicate rejects valid zero-game baselines and accepts uncertain restored data; inferring readiness from an unrelated poll result races attribution.

### 5. Use a durable first-run phase, not the setup worker's completion flag

Represent account-owned first-run state as `SETUP`, `HISTORY_CHOICE`, `IMPORT_REQUESTED`, or `COMPLETE/DEFERRED`, with an explicit owed/not-owed projection for the Home takeover. Claim it durably after credential persistence, before showing setup. Continuing or declining setup atomically advances to the history phase. Completing setup work only updates operation records; it cannot clear the journey. Only an explicit history skip/continue or a reconciled successful import completes/defers it.

Add a history-choice step to `OnboardingViewModel` without changing the credential step count or repeating verification. The history surface is reached even when initial setup was declined. It shows baseline readiness, Import and Skip/do later, or Already imported and Continue. Missing baseline offers a route back to setup recovery, not an implicit network fetch. Native Back navigates to setup review without changing operation results; exiting the first-run flow uses explicit Skip/do later so consent and phase changes are durable.

When import has been requested, offer Continue/do later even while its operation/recomputation is pending. Record the journey as deferred, not the operation as cancelled; Settings reports pending recovery. An unanswered choice resumes on cold launch. Completed/deferred choices and previously configured users do not reopen on upgrade. Replace the old `firstRunSetupActive` projection compatibly: an old true claim maps to SETUP; an absent claim on a configured install maps to not owed. Do not infer a new owed flow merely from a completed setup record or an incomplete background operation.

**Alternatives rejected:** letting `completeRun()` release takeover skips the new decision on cold launch; keeping the old claim without a phase repeats the repaired completed-checklist trap; treating import as default setup work violates explicit consent.

### 6. Make the existing import operation transactional and recoverable

Keep `ProfileRepository.importSteamHistory()` as the shared domain entry, widening its result from Boolean to explicit states such as Imported, Already imported, Needs baseline, Recompute pending/Failed, and Superseded. Onboarding and Settings consume domain import state instead of separate local in-flight truths. Persist consent/request identity before launching the existing operation so a kill before launch can replay the request. Settings confirmation uses the same request path; merely visiting either surface never creates consent.

At import/recovery boundaries acquire `SteamSyncCoordinator` before `DerivedStateWriteCoordinator`, matching account reset and re-filing. Under the account admission barrier, revalidate the expected account, pending reset marker, one-time flag, and confirmed baseline. Use one Room transaction to read all relevant lifetime totals and tracked session minutes, set frozen offsets with the existing formula, set `playtimeBackfilled`, and record pending BACKFILL recomputation. Concurrent raw sync commits cannot advance one part of this snapshot while the other part is read. Keep network work and cross-store writes outside the transaction. Preserve credited shared-to-owned conversion offsets and manual/shared evidence rather than overwriting them as a side effect of tightening import; add an explicit fixture for that existing invariant.

After commit, run the existing gamification/versioned progress protocol with `RecomputeSource.BACKFILL`; clear the pending marker only when that protocol finalizes successfully. Extend the existing pending-import recovery pattern with source/request provenance so history recovery does not use backup's `RESTORE` source or a later `SYNC` source to announce administrative imported XP as earned progress. Every recompute path must recognize pending administrative recomputation before clearing its marker; a concurrent sync cannot consume the marker with the wrong presentation baseline. If both backup and history pending state exist, preserve administrative/silent semantics and resolve from the committed current raw state, never recapture offsets.

Raw committed/pending is distinct from fully complete. Repeat requests resume pending recomputation first; they never refreeze from growing Steam totals. Account reset clears/fences requests and confirmation. Serialize UI requests through the shared coordinator, but rely on raw transaction and one-time state for idempotence. Preserve the existing import reset, tracked sessions, high-water streak, and applied-cloud-transfer reset guard; adapt its shared coordination only where needed to avoid races with the tightened import.

**Alternatives rejected:** a Compose/ViewModel busy flag does not survive death; separate offset/flag writes can strand an import; reversing lock order can deadlock against sync/reset; calling the import again after raw commit silently returns a no-op with stale XP.

### 7. Diagnose before claiming the original cascade fixed

Record stage id, opaque attempt/work identity, unique work name, reused-versus-new admission, scheduler state/attempt count, foreground settlement reason, and attributable domain reason using the existing diagnostic approach. Never log API keys, raw account identifiers, or personal library payloads. Existing structured Steam run diagnostics supply network/commit evidence; do not add an unbounded parallel telemetry system.

Exercise setup against periodic/manual sync, live monitoring and post-play handoff, offline transitions, backoff, real failures, and cold restart. Keep diagnostic conclusions separate from the confirmed defects; inability to reproduce one reported cascade does not invalidate the independently reproducible recovery fixes.

## Risks / Trade-offs

- [More than one admitted operation can remain pending] -> Persist per-stage ownership and bound observers to the fixed registry; stop superseded observers and use the same worker policies.
- [Observation timeout could be mistaken for failure] -> Explicit pending copy, no error badge from time alone, and tests with an injected monotonic budget.
- [Pruned/missing WorkManager records cannot prove completion] -> Recovery-required explanation and an explicit request, not inferred success or automatic duplicate work.
- [Room/DataStore/WorkManager cannot form one transaction] -> Durable intent before side effects, exact identity reconciliation, monotonic attempt ownership, and fault-injection at each boundary.
- [Import's new locking could deadlock or block sync too long] -> Preserve sync -> derived -> Room ordering, use bulk queries and local transactions, and avoid network under those locks.
- [Administrative XP could become earned progress during recovery] -> Persist recompute provenance and integrate every marker-clearing path with the existing non-SYNC progress protocol.
- [Legacy unimported users need a confirming poll] -> Clear Needs sync copy; never unset completed imports, synthesize baseline evidence, or force onboarding on configured users.
- [Scope shares sync and Settings with other changes] -> Compare delta requirement names before apply, preserve current cloud re-filing/placement contracts, and coordinate edits rather than recreating older paths.

## Migration Plan

1. Land milestone A with versioned setup records, result/baseline evidence, and compatible domain projections. Preserve old stage ids/outcome decoding. Convert the existing active marker into the corresponding latest attempt when its association can be established; preserve terminal historical outcomes as historical, not live work. A legacy backoff failure without a job id stays historical until explicit recovery; do not guess its associated job.
2. Add minimal Room migration(s) for local baseline confirmation and pending recompute provenance, with defaults that do not grant readiness or create a request. Update field-scoped DAO writes, reset, backup/restore boundaries, schema fixtures, and migration tests together; no destructive fallback migration.
3. Land milestone B's phase/consent migration and shared import path, mapping old unfinished first-run claims to SETUP and leaving configured users with no claim out of onboarding. Preserve completed import flags, frozen offsets, and cloud receipts. Validate startup ordering after account recovery.
4. Run unit, migration, UI and device recovery scenarios, re-record affected screenshot goldens with the UI change, and validate delta specs before sync/archive. Close #158 only after the acceptance evidence, not when this planning change is created or merged.
5. Roll back only to a compatible build retaining the migrated Room version/readers. If the new UI must be disabled, stop new admissions/consent while keeping existing WorkManager work and pending-import recovery intact; do not delete imported offsets, markers, or stage evidence to simulate rollback. Never change historical failed records to success without evidence.

## Open Questions

- The original report's app build, device/Android version, exact failing stage, and error text are unavailable. These refine reproduction coverage, not the selected architecture or acceptance contract.
- The 120-second foreground observation budget and final localized copy can be tuned after device UX verification without changing the distinction between pending work and terminal outcomes.
