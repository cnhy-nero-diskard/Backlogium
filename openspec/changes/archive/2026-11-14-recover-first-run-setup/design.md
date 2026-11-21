# Design

## Context

See `proposal.md` for motivation and the three delta specs for the behavioral contract. This is an Operate-mode refinement of the existing Compose/Material 3 setup flow, not a visual redesign. `PRODUCT.md` is present; no `DESIGN.md` was found, and the Impeccable context launcher refused execution. Existing controls, typography, layout patterns, and screenshot evidence remain the visual authority.

Investigation from source, not a reproduced runtime incident:

- `WorkStageRunner` observes the exact admitted unique-work UUID, but `terminalWhileBackingOff` converts `ENQUEUED` with attempts into `Failed` while WorkManager retains the queued job.
- `SetupCoordinator` isolates exceptions and recovers an active stage's UUID and selection. Its suspending mutex also permits repeated `start` calls to queue entire runs, and its single active-stage marker assumes sequential observation.
- `SetupViewModel` maintains a pending selection, but `setupStagesUi` uses the previous run selection whenever `finished` is true. Onboarding passes `showRetry = false`, so simply enabling the controls will not make edited restarts correct.
- Setup completion releases `firstRunSetupActive` automatically. A new unresolved history choice cannot use that flag alone or process death after setup completion will bypass the choice.
- Manual sync uses `steam_sync_now` with KEEP; periodic work uses `steam_sync_periodic`. They are intentionally separate. `SteamSyncWorker` checks account admission without holding a global lock across network work, re-reads committed baselines transactionally, and treats presence-start handling as best effort. An overlap-induced cascade is not yet demonstrated.
- `ProfileRepository.importSteamHistory()` delegates to `PlaytimeBackfillUseCase`. It serializes imports with the derived-state coordinator but currently writes offsets, completion flag, and recomputation as separate steps. Readiness, raw-ledger interleavings, and interrupted recomputation need explicit tests rather than assumptions of atomicity.

## Goals / Non-Goals

**Goals:** Keep WorkManager authoritative for work state; preserve exact work identity and successes; make onboarding recovery usable; add one optional history decision without a second implementation of import or gamification.

**Non-goals:** Change periodic cadence, collapse manual/periodic work into one name, stop live monitoring during setup, import cloud presence history, reconstruct historical sessions, redesign Home, or implement the separate `extend-history-before-tracking` change. No cloud deployment or new dependency is needed.

## Decisions

### 1. Reproduce and record before changing concurrency policy

Use deterministic WorkManager tests to exercise existing manual work, a running periodic poll, live-presence activity, network constraints, retry backoff, terminal failure/cancellation, and exact-ID recovery. Record stage ID, unique name, admitted UUID, state/attempt transitions, attributable error, and whether the trigger reused or created work in a bounded change-local investigation note. Run a targeted emulator scenario with fake Steam responses so real credentials are neither required nor logged. The note must distinguish observed results from hypotheses.

Keep KEEP policies and distinct periodic/manual identities. A periodic request normally remains ENQUEUED between executions; observing its lifetime as if it were a one-time terminal job would hang setup. Do not solve this by cancelling periodic sync or presence. Existing no-double-credit transactional tests remain part of verification.

Alternative rejected: a setup-wide/global sync lock around all fetching. It would hide rather than explain waiting and unnecessarily block live activity.

### 2. Separate live activity from terminal outcomes

Add a stage activity snapshot (waiting/running/retry-backoff, attempt count, safe reason/next-attempt time where supported) alongside existing progress and `SetupOutcome`. Extend runner callbacks and shared UI derivation accordingly. Remove synthetic failure on backoff: the runner remains attached to its UUID until a real terminal state. Waiting and backoff use textual status, not an error treatment or a misleading progress percentage. Display a next-attempt time only when the underlying scheduler supplies one reliably.

Keep terminal outcomes encoded compatibly. WorkManager state is re-read on recovery; transient display text need not be persisted as an authoritative outcome. Cancellation remains an attributable failure, not success or skipped. Do not infer success solely from a worker's SUCCEEDED state when its operation reports an unreadable/private library or another domain-level no-op: expose current attempt results through typed worker output/repository mapping rather than a stale profile error. Read worker output/progress only through the existing domain-facing setup boundary.

Alternative rejected: add a terminal `Retrying` verdict and clear the active marker. That loses eventual outcomes and repeats the existing backoff-as-failure mistake.

### 3. Keep sequential ownership; let the user leave, not abandon observation

Preserve the coordinator's sequential stage ordering and single exact active-work recovery marker. Long waits may delay later stages, but the user always has `Continue`/`Skip` and can revisit setup from Settings. Do not invent a successful completion to free the screen. Actual failures still advance the stage loop independently.

Separate UI readiness/exit availability from `inScreenSettled` and terminal completion. Leaving an active run releases only the first-run surface claim; the application-scope coordinator and WorkManager continue, and persisted UUID/selection survive process death. Settings reattaches and shows outstanding state. This avoids a larger multi-stage journal and concurrent-detachment rewrite while fulfilling non-blocking entry.

Alternative rejected: return early from every runner when backoff starts and proceed through the list. Without persisting/observing multiple active stage identities that would strand the first queued job's outcome.

### 4. Make recovery commands explicit and guard admission

Reject duplicate start/retry commands while the run is already claimed, before launching execution. The atomic claim replaces the queueing execution mutex, is set synchronously for rapid taps, and is respected during initial load/recovery. Never queue another full run behind the active one.

Provide explicit selection-editing state after settlement; the UI must render pending selections in that state rather than projecting the finished run's selection forever. Recovery preselects only unresolved/failed choices, not successes. Retry targets one failed stage; an edited restart preserves unrelated outcomes, including successes and skips. The original initial decision can still record unselected stages as skipped. Settings retains deliberate explicit reruns of successful work.

Alternative rejected: only change `showRetry` to true. It leaves queued duplicate runs, stale selection projection, and skip-overwrites unresolved.

### 5. Add a durable first-run history decision outside the stage registry

History import is a distinct optional decision after the setup surface and before Home, not a default-on stage or mandatory prerequisite. All first-run exits, including Skip setup and leaving while waiting, route through it. Render the existing Material actions with concise explanatory text: lifetime playtime can count toward XP; Steam does not provide the historical session timestamps. Show `Import Steam history` and `Skip`; while no successful snapshot exists, explain the sync prerequisite and keep Skip enabled. Failed imports offer Retry and Skip. Never silently import later after the user skips.

Persist a first-run phase/decision separately from the setup worker run: credentials → setup → history decision → dismissed. Transition the claim durably before advancing UI. Setup's `completeRun` may release its own claim but cannot erase an outstanding history decision. Recognize the repository's completed import on restart. Migration maps an outstanding legacy setup claim into the new phase, but absence of that claim does not enroll existing configured users. Later credential editing does not create this phase.

Alternative rejected: register history import as a fourth automatic setup stage. It makes a meaningful XP decision too easy to inherit through defaults, complicates baseline prerequisites, and does not reliably cover Skip setup.

### 6. Reuse and harden the existing import boundary

Expose successful-baseline readiness and import completion through repositories/domain models; UI must not import DAOs, entities, or SettingsDataStore. Use the successful snapshot for the current account (not game count alone) so a confirmed empty library differs from a private/unreadable response. Both onboarding and Settings call the same operation.

Test and, where necessary, harden `PlaytimeBackfillUseCase` so raw capture (canonical credited totals, offsets, and completion flag) has a transactional boundary shared with sync/monitoring ledger writes and account admission. Follow the existing sync-to-derived lock ordering; do not hold network work or a Room transaction across gamification recomputation. Derived recomputation must use the existing recoverable protocol, with a durable pending marker before capture so a kill between capture and recompute cannot become an unrecoverable no-op. A completed raw import must still resume pending derived work on repeated invocation/startup. Account reset must clear/account-scope any pending marker.

Do not create another XP writer, per-date distribution, or historical sessions. Preserve the existing frozen-offset formula, taper, Settings reset behavior, and cloud-transfer reset guard. The separate history-attribution proposal may add undated presentation later; this change only supplies truthful import disclosure.

## Risks / Trade-offs

- [Sequential observation can defer artwork/completion-times while sync backs off] → State the wait honestly, preserve Continue, and retain all selections for durable continuation; parallel stage orchestration is outside this fix.
- [Worker success can represent an unreadable library rather than a completed baseline] → Map an attempt-specific domain result and test a confirmed zero-game snapshot separately from private/unconfirmed data.
- [A new history phase could reopen onboarding on upgrade] → Enroll only genuinely outstanding first-run flows and test absent/legacy/completed flags explicitly.
- [Import hardening can introduce lock inversion or stale-account writes] → Follow existing lock order and account admission; exercise both sync/import commit orders, account changes, and interruption points.
- [Persisted old synthetic failures lack retained work IDs] → Preserve old outcomes honestly; a deliberate retry uses KEEP to attach to still-live work rather than fabricating success or blindly replacing it.
- [Visual baseline scope omits onboarding/setup today] → Add targeted recovery/history-decision UI tests and screenshot coverage, and re-record every affected fixture-rendered baseline in the same checkpoint.

## Migration Plan

Add backward-compatible setup activity/first-run phase state while keeping stable stage IDs and existing outcome codecs. Recover an old active marker by UUID; never reset successful stage outcomes during migration. Existing configured users without an active first-run claim stay configured. Add typed import readiness and pending-derived reconciliation without changing stored historical-offset meaning or deleting data.

Ship investigation/tests with the related implementation checkpoints. Run unit/build/lint and bounded visual verification; use only the GPU-verified repository emulator launcher with explicit serial targeting for instrumented tests. No production credentials, backup data, or generated build outputs belong in commits.

Do not auto-archive this change. On rollback, preserve imported offsets and existing work rather than undoing user data; the prior app may ignore new phase/activity keys, so clearing those keys or downgrading is not a recovery strategy.
