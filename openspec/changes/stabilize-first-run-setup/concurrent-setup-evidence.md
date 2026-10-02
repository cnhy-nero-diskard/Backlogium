# Concurrent setup characterization — evidence (task 1.2)

Date: 2026-10-02 · Change: `stabilize-first-run-setup` · Scope: **host-side, reproducible,
concurrent-work investigation** of the *current* setup runner against a real Robolectric
WorkManager.

| Item | Value |
|---|---|
| Task | 1.2 (tasks.md §1) |
| Fixture | `app/src/test/java/com/example/backlogium/work/setup/ConcurrentSetupCharacterizationTest.kt` |
| Evidence doc | `openspec/changes/stabilize-first-run-setup/concurrent-setup-evidence.md` (this file) |
| Production edits | none |
| Other test edits | none |
| Commits | none |

## 1. Verification status (honest)

This document records the protocol the fixture encodes and the assertions it makes, plus the
outcome of running it (`./gradlew.bat :app:testDebugUnitTest --tests "...ConcurrentSetupCharacterizationTest"`
and the four prepared companions — see §8 for last results). Everything called "asserted" is an
assertion in the fixture; nothing here claims behavior that was not executed.

Parent review: the five passing scenarios establish host-side scheduler mechanics, not a running
Steam poll or active live-monitor service. Task 1.2 remains unchecked pending that remaining
concurrent-work evidence; passing doubles and an outbox handoff do not establish the full workload.

## 2. Fixture architecture (pinned WorkManager-testing APIs)

- **Real WorkManager** on Robolectric, initialized in `PRESERVE_EXECUTORS` mode: a real single­thread
  worker executor executes worker bodies (so a `RUNNING` state can be genuinely observed and held),
  while WorkManager's internal task executor stays synchronous.
- **`WorkerFactory` seam** (the repo's proven `androidx.work.WorkerFactory` pattern, as in
  `PostPlaySyncWorkerTest`): the *real* scheduler class names (`SteamSyncWorker`,
  `HltbDatasetWorker`, `PostPlaySyncWorker`) are routed to small doubles. `SteamSyncWorker` is
  routed by its real `KEY_TRIGGER` input: `manual` → the behavior chosen per scenario (succeed /
  RUNNING-gated / retry-always), `periodic` → a gate-held RUNNING double.
- **Real schedulers**: `SyncScheduler.ensurePeriodicSync()`, `syncNow()` (`KEEP`, `steam_sync_now`),
  `ensureCompletionTimes()`, and the real `PostPlaySyncScheduler` +
  `PostPlayGenerationCoordinator` over a real `PlaySessionEndPublisher`, real `SessionEndOutbox`
  contract, and `WorkManagerPostPlayWorkEnqueuer`.
- **Real runner**: the `library_sync` stage's `WorkStageRunner` taken from the *registered*
  `SetupStageRegistry` (`steam_sync_now`, trigger `{ scheduler.syncNow() }`, its real failure reason).
- **Per-work deterministic constraints** via the pinned `TestDriver`
  (`setAllConstraintsMet(UUID)` / `setPeriodDelayMet(UUID)` — the pinned surface has **no**
  `NetworkType` overload). Only the intended chain is released at each step; nothing else is ever
  cancelled by the fixture.

### Honest boundaries — what is real vs double

| Aspect | Status |
|---|---|
| WorkManager scheduling, KEEP, unique names, constraints, states (incl. RUNNING), attempts, backoff | **real** WorkManager |
| Manual sync chain executing (RUNNING → SUCCEEDED) while the runner attaches | **real execution** of a *double body* |
| Periodic chain executing (RUNNING → finished) alongside setup | **real execution** of a *double body* |
| Session-end outbox drained into the post-play chain | **real** `PostPlaySyncScheduler` handoff surface; the chain is exercised as **enqueued**, its worker body is a never-executed double |
| Steam network fetch / profile / commit inside worker bodies | **none** — every body is a double; no Steam network or device evidence is claimed |

## 3. Bounded diagnostic record

Each scenario appends to an in-test journal capped at **48 entries**
(`RecordEntry` / `DiagnosticJournal`), one pipe-delimited row per fact:

```
step | stageId | uniqueWorkName | opaqueWorkId | admission | schedulerState | runAttemptCount | outcome | attributableError
```

- `stageId` — persisted stage ids (`library_sync`, `completion_times`, `n/a`).
- `uniqueWorkName` — `steam_sync_now`, `steam_sync_periodic`, `hltb_dataset_check`,
  `post-play-sync-440` (440 is a game/app id, **not** an account identifier).
- `opaqueWorkId` — WorkManager `UUID`.
- `admission` — `REUSED` / `NEW`.
- `schedulerState` — `WorkInfo.State.name`, `ABSENT`, or `generation=N`.
- `runAttemptCount` — WorkManager attempt counter.
- `outcome` — runner `SetupOutcome` verdict or existing-diagnostics `SyncOutcome.value`.
- `attributableError` — failure-reason text only.

Existing diagnostics APIs are used where their semantics fit: the **real** `SyncRunRecorder` writes
one `SyncRun` per attributable manual-chain end. After every run the rendered record is asserted to
contain none of `steamId`, `apiKey`, `secret`, `token`, a `7656119*` sentinel, or an
account-marker, and is re-checked against its cap.

## 4. Scenarios and assertions

### S1 — Genuinely RUNNING manual work reused by KEEP while a real periodic run coexists

1. Real `syncNow()` under `steam_sync_now` is released per-work; its double holds **RUNNING** on a
   gate (real `RUNNING` WorkInfo state, held deterministically).
2. Real `ensurePeriodicSync()` is released per-work (`setPeriodDelayMet` +
   `setAllConstraintsMet`); the periodic double holds **RUNNING** alongside.
3. `hltb_dataset_check` sits gated (`ENQUEUED`), untouched.
4. The registered runner attaches: `startedIds == <the RUNNING manual id>` → `REUSED`, no
   duplicate, runner still active (waiting on live work).
5. Manual gate released → manual chain **SUCCEEDED** → runner settles `Succeeded`.
6. Periodic still RUNNING at setup settlement (identity unchanged) → released → finished.
7. Asserts: reuse of *running* work; no foreign identity; periodic identity stable and genuinely
   executed; manual chain recorded via `SyncRunRecorder`.

### S2 — Queued retry becomes a stored stage failure while the job stays live

1. `manual` double answers `Result.retry()` on its first attempt → scheduler reports `ENQUEUED`,
   `runAttemptCount ≥ 1`. (Backoff continues on WorkManager's own schedule.)
2. The runner reattaches (REUSED) and — because the current `WorkStageRunner` treats
   `ENQUEUED && runAttemptCount > 0` as terminal — returns
   `Failed("Couldn't reach Steam. …")`.
3. The record captures **both** the runner verdict and the same-instant scheduler truth
   (`ENQUEUED`, `runAttemptCount ≥ 1`, not `FAILED`/`CANCELLED`), plus the `SyncRunRecorder` row.
4. Asserts the two facts diverge — the stored failure is not corroborated by a terminal scheduler
   state.

### S3 — No pre-existing work ⇒ brand-new exact identity

Runner over an empty `steam_sync_now`; `startedIds` = the freshly admitted id (`NEW`); per-work
release settles `Succeeded`; no duplicate/foreign identity appears.

### S4 — Manual and periodic chains are attributionally separate at the request level

Real `ensurePeriodicSync()` + `syncNow()`; distinct opaque ids; exact `WorkSpec` inputs carry
`TRIGGER_MANUAL` vs `TRIGGER_PERIODIC` (read by exact work UUID, not re-derived from a name);
both `ENQUEUED`, `runAttemptCount 0`.

### S5 — Live-monitor/post-play handoff while setup runs over RUNNING manual work

1. Manual chain RUNNING (gated double) + periodic chain RUNNING (gated double) in flight.
2. A durably recorded session end (`SessionEndOutbox.pending`) is drained by the real
   `PostPlaySyncScheduler.observeSessionEnds()`: generation **1** is acquired and one attempt is
   enqueued under `post-play-sync-440` (`ENQUEUED`, network-gated). Outbox acknowledged.
3. Three chains coexist with independent opaque ids; the runner attaches to the RUNNING manual job
   (`REUSED`) and settles `Succeeded` after its gate release.
4. The handoff chain is then **still `ENQUEUED` with the same id** — setup neither cancelled nor
   consumed it (an executable double exists but is never invoked).
5. Periodic double completes after its gate release.

## 5. Findings

1. **KEEP reuse includes genuinely running work.** The runner attached to the exact RUNNING
   pre-existing job (`REUSED`), never stacked a duplicate, and waited on it — the behavior design
   Decision 2 requires.
2. **The periodic chain is a separate, surviving, *executing* chain.** `steam_sync_periodic` ran
   (RUNNING → finished) alongside setup settlement and kept its identity; setup changed nothing.
3. **Backoff retries are currently misclassified as stored failures.** `ENQUEUED` +
   `runAttemptCount ≥ 1` yields a durable `Failed` stage outcome while the scheduler still holds the
   job live (S2: runner verdict vs same-instant scheduler truth).
4. **The live-monitor/post-play handoff dispatches independently of setup** through its real seams
   (outbox → scheduler → generation → unique chain), and setup leaves the enqueued handoff chain
   untouched.
5. **Diagnostics gap (relevant to the fix).** `SyncRunRecorder`/`PresenceDecisionRecorder` carry
   trigger/attempt/outcome/error but no work identity, admission kind, or reused-vs-new marker —
   carried here only by the bounded record; the change's per-stage attempt records are the durable
   home.
6. **Bodies are doubles; execution evidence is execution of doubles.** No Steam network call, no
   device, and no claim of one. The periodic/manual RUNNING and completion states prove WorkManager
   concurrency mechanics, not real Steam behavior — exactly the boundary this task's host-side
   evidence can truthfully establish.

## 6. Original cascade — NOT reproduced (recorded honestly)

The #158 user-visible cascade is **not** reproduced or claimed: no device, build version, exact
failing stage, or error text is available (design.md Open Questions). What is evidenced is the
underlying mechanics — running-work reuse, backoff misclassification, a concurrent periodic run,
and the post-play handoff — and §2's boundary table keeps those claims from being over-read.

## 7. Commands

Run from the repo root (`D:\Codez\Projects\Backlogium`), PowerShell:

1. Focused characterization run:
   ```
   .\gradlew.bat :app:testDebugUnitTest --tests "com.example.backlogium.work.setup.ConcurrentSetupCharacterizationTest"
   ```
2. The record prints under `=== ConcurrentSetupCharacterization Record ===` and lands in:
   ```
   app\build\test-results\testDebugUnitTest\TEST-com.example.backlogium.work.setup.ConcurrentSetupCharacterizationTest.xml
   ```
3. Prepared companions for this run:
   ```
   .\gradlew.bat :app:testDebugUnitTest --tests "com.example.backlogium.work.setup.ConcurrentSetupCharacterizationTest" --tests "com.example.backlogium.data.repo.LibraryPollRepositoryTest" --tests "com.example.backlogium.data.local.PlayerProfileLibraryConfirmationMigrationTest" --tests "com.example.backlogium.work.setup.SetupOperationStateTest" --tests "com.example.backlogium.work.setup.ForegroundAttemptTest"
   ```

## 8. Last executed outcome

Run on this checkout (2026-10-02, Windows host, PowerShell; `.\gradlew.bat :app:testDebugUnitTest`
with the `--tests` filters from §7, `--console=plain`):

| Class | Result |
|---|---|
| `ConcurrentSetupCharacterizationTest` | **5 / 5 passed** (records captured; see §4 and the run's XML) |
| `LibraryPollRepositoryTest` | **24 / 24 passed** |
| `SetupOperationStateTest` | **8 / 8 passed** |
| `ForegroundAttemptTest` | **16 / 16 passed** |
| `PlayerProfileLibraryConfirmationMigrationTest` | **2 / 2 passed** after shortening test method names |

### Resolved migration-test failure

The earlier migration run failed with native `SQLITE_CANTOPEN` (code 14) before its assertions.
Parent reproduction measured a **266-character database path**, or **274 characters** with the
SQLite journal suffix. Robolectric embeds the class and test method names in its temporary
directory; the original long method names exceeded the traditional Windows native path limit.
The directory existed, so the exception's generic permissions hint was misleading.

Shortening only the two test method names resolved the failure. Both tests now pass in isolation
and alongside the four other prepared suites (**55 tests total, zero failures**). No database
backend, Room migration, journal mode, production behavior, or assertion was changed. Real
file-backed SQLite, Room-driven upgrade, and full on-open schema validation remain exercised.
Task 2.2 is checked; the migration-test blocker is resolved. Device acceptance remains separate.

The combined command in §7 was rerun with `--no-daemon --console=plain`. Copies of all five XML
reports are preserved outside Gradle's overwritten results directory at
`C:\Users\cnhyn\AppData\Local\Temp\opencode\backlogium-setup-focused-reports\`.
Before/after migration XMLs and logs are preserved alongside it as
`backlogium-migration-before.{xml,log}` and `backlogium-migration-after.{xml,log}`; the combined
run log is `backlogium-setup-focused-after.log`. These are local verification artifacts, not
repository source files.

## 9. Task criteria

| Task 1.2 criterion | Status |
|---|---|
| Setup exercised with running/gated manual work reused by KEEP | host scheduler coverage verified — S1/S5 genuinely RUNNING double reused; real Steam poll body not exercised |
| Concurrent separate periodic chain | host scheduler coverage verified — S1/S4/S5 separate name/id/trigger and executing periodic double; real periodic poll body not exercised |
| Live monitoring / post-play handoff exercised, not file inspection | handoff seam verified — S5 real scheduler/generation/enqueue over a fake outbox; active live-monitor service and post-play body not exercised |
| Bounded diagnostic record with stage id, opaque identity, unique names, scheduler state / runAttemptCount, attributable errors, reused-vs-new | met — §3 schema, capped journal, asserted redaction |
| Existing diagnostics APIs used where applicable | met — real `SyncRunRecorder` rows; durable per-stage records remain the change's job (§5.5) |
| No secrets / raw account identifiers | met — asserted after every scenario |
| Original cascade recorded honestly (NOT reproduced unless evidenced) | met — §6 |
| Device-dependent residue reported as blocker, not faked | outstanding — real poll/live-monitor concurrency and task 8.3 device acceptance remain unverified; original cascade remains unreproduced |

## 10. Autoship verification boundary

Checkpoint `0b97bafe` shipped the verified domain models and baseline migration to
`origin/fix/stabilize-first-run-setup`. Sections 7–9 describe the earlier verified fixtures;
they are not pass evidence for subsequent rewrites of the coordinator, workers, or import.

The first compilation pass over those later drafts compiled the main Android source set,
but unit-test compilation failed. **No tests executed in that pass.** Its logs are preserved
under `C:\Users\cnhyn\AppData\Local\Temp\opencode\backlogium-autoship-library-reports\`
as `build-slot-compile1.log` and `build-slot-testrun1.log`. XML files copied into that directory
came from the earlier five-suite run, not this compilation pass. They must not be counted
as verification of the new production evidence recorder, real-worker fixtures, import,
or durable setup reconciliation.

Review also identified exact-admission, callback-fencing, foreground-settlement, typed import
admission, and mixed backup/import provenance gaps in the drafts. Those tasks remain unchecked
until their fixes and focused tests are verified. No worker-double result is being substituted
for active live-monitor or device acceptance.
