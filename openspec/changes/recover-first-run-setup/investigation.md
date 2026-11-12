# Setup recovery investigation (#158)

## Before the fix (source inspection)

`WorkStageRunner.terminalWhileBackingOff` returned `Failed` for an ENQUEUED job with a nonzero attempt count. The background job was not cancelled, but setup stopped observing it. This explains misleading failure reporting; it does not prove that concurrent monitoring caused the original reported failure. The coordinator already isolated genuine stage exceptions. Onboarding explicitly suppressed Retry, and finished-run selection projection prevented edited restarts from reflecting pending selections.

## Deterministic scheduler exercises

`WorkStageRunnerTest.setupReusesManualWorkWhilePeriodicAndPresenceAreRunning` uses held response workers for a periodic poll, a live observer, and constrained manual work. The setup trigger calls KEEP against the existing manual unique name. Assertions identify the exact admitted UUID, WAITING → RUNNING → SUCCEEDED transitions, one manual work record, and concurrently RUNNING periodic/observer jobs. The trigger reuses rather than replaces work. These are scheduler stand-ins, not a claim that the production Steam API or presence service was exercised.

`retryBackoffRemainsOutstandingAndRecoveryUsesTheSameId` observes a real Result.retry worker by persisted UUID. Its ENQUEUED state and nonzero attempt count remain nonterminal; cancellation is reported distinctly as a genuine unfinished stage. `domainFailureInSuccessfulWorkerOutputIsNotReportedAsCompleted` checks an attributable operation error despite a successful WorkManager envelope.

The pre-existing coordinator tests verify that a real failure does not cascade, successful outcomes survive single-stage retry, and exact-ID detached recovery continues remaining selected work (#111). New tests check duplicate admission and selection editing. No setup concurrency-policy change is needed to demonstrate these fixes.

## Device exercise

Target: GPU-verified `emulator-5554`, launched through `scripts/test-emulator.sh start` with `-gpu host -feature GuestAngle`; guest reports Goldfish GFXStream (Apple M5). `SetupConcurrentWorkDeviceTest` exercises real WorkManager reuse and exact-ID recovery with fake response workers, isolated test unique names, and no Steam requests/credentials. Results are recorded after execution, not inferred from source.

Result: passed on the emulator. Logcat captured stage `library_sync`, UUID `5311ec68-6683-4dfb-8ff3-0106086cc604`, WAITING (`reused=true`, `replacement=false`, `error=none`) followed by SUCCEEDED (`recovery=same-id`, `error=none`). Host setup/derivation suite: 48 passing tests, including retry backoff → success. Targeted Android test: 1 passed. Debug lint passed with existing baseline-filtered findings; the baseline was not edited. A test originally assumed asynchronous observation had finished after `runCurrent`; it now awaits explicit admission/activity signals instead of relying on timing.

For #111, `recoversDetachedWorkAndContinuesTheRemainingStages` remains passing, and the registered completion-times stage still uses this exact-ID runner. The device fake-response exercise verifies the scheduler recovery mechanism, not a live HowLongToBeat download or an actual OS process kill.

## Recovery semantics

Waiting and retry backoff keep observation attached to the same work. Continue leaves the setup surface without cancelling it or writing a skipped outcome. Later selected stages retain their order; a delayed first stage can delay their start, but not entry into the app. Settings shows outstanding work, and recovery resumes its exact UUID. Genuine failures advance to remaining stages independently. An edited recovery run preserves outcomes outside its explicit selection.

## History import integrity

The existing import operation now admits the configured account under the raw-ledger coordinator, then takes the derived-state coordinator. Canonical credited totals, frozen offsets, the one-time flag, and the existing Room `pendingImportRecompute` marker are captured transactionally. A raw interruption rolls back the whole capture. After commit, recomputation occurs outside the database transaction; a repeated call or the existing startup pending-import resolver finishes derived work without re-freezing offsets. Account resets already clear that account-owned marker. Settings reports failures recoverably instead of allowing a new readiness exception to escape its coroutine.

Readiness depends on a successful snapshot for the configured account, not game count: an unreadable unsynced library is rejected, while a confirmed empty snapshot may be explicitly imported. A pending recompute is not presented as completed. Tests cover both sides of a tracked-credit update, duplicate requests, capture rollback, committed-capture interruption, wrong/pending accounts, and the existing cloud-transfer reset guard.

The final device exercise passed two tests. Its second run logged work UUID `a1639706-f96f-4833-ad1d-e8dae5ebbbf3`, reused through WAITING → SUCCEEDED and exact-ID recovery. A real in-memory Room import was interrupted after capture, recovered through the shared operation, and invoked again: the historical contribution remained 120 minutes and XP was unchanged by repetition. No production database or Steam API was used.
