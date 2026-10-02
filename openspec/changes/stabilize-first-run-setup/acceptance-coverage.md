# Acceptance coverage — stabilize-first-run-setup

File scope: **this file only** (`openspec/changes/stabilize-first-run-setup/acceptance-coverage.md`).
No production or test edits, no build, no staging, no commits. Test classes referenced live under
`app/src/test/java/com/example/backlogium/...` (package `work`, `work.setup`, `data.*`, `domain`,
`ui.*`). Method names are copied from the current source.

Every `#### Scenario` in the five delta specs is mapped below — **95 total, 0 unmapped**. Each row is
either a concrete existing `TestClass#method` or a named manual check (`M1`–`M7`). Task 8.1 permits a
named manual acceptance check where no automated test exists, so a manual row is an acceptance
assignment, **not** a "blocked host" gap.

## Status legend

- **Aut — pending verification** — a concrete existing test is mapped; initial status is *pending
  verification*, **not** passed. Nothing below claims a pass.
- **M# — not run — no connected device** — named manual acceptance check; not executed (no ADB
  device available to this session).
- **Aut + M#** — the automated test covers the host-side/persistence boundary; the real-device
  aspect (OS process death, airplane mode, notification runtime prompt, account switch with live
  work) is explicitly manual and is **not** claimed by any simulated Room/transaction failure.

The previous run of five suites (55 tests: `ConcurrentSetupCharacterizationTest` 5 +
`LibraryPollRepositoryTest` 24 + `SetupOperationStateTest` 8 + `ForegroundAttemptTest` 16 +
`PlayerProfileLibraryConfirmationMigrationTest` 2) is **historical verification only** — it is not
counted again here as a new pass.

### Workload-honesty boundaries (established by the mapped fixtures)

- `SteamSyncLibraryEvidenceWorkerTest` drives the **real** `SteamSyncWorker.doWork()` over a real
  in-memory Room database, real committer, real gamification recompute, and real diagnostics via a
  genuine async barrier (two real poll bodies both enter the fetch simultaneously), but with a
  **fake `SteamApi`** and a fake credential provider. It does **not** run the real Presence service
  or a real Steam network poll.
- `ConcurrentSetupCharacterizationTest` runs a **real WorkManager** on Robolectric in
  `PRESERVE_EXECUTORS` mode (real single-thread worker executor; genuinely RUNNING/queued states),
  but every worker **body is a double**, and the live-monitor/post-play handoff chain is exercised
  **only as `ENQUEUED`** (its body is never executed). Host-side scheduler mechanics only.
- **OS process death is never claimed by simulated Room/interrupted-transaction failures.**
  `LibraryPollConfirmationTransactionTest#…rolls back`, `PlaytimeBackfillUseCaseImportTest
  #roomTransactionScopeRollsBackPartialRawWrites`, and the `reconcile*` tests prove the persistence
  boundary; a real kill/process-death observation is **manual** (M4/M5/M6).

---

## 1. `steam-sync` (11 scenarios)

| # | #### Scenario | Concrete test class#method / manual check | Status |
|---|---|---|---|
| 1 | Private profile or empty response | `work.SteamSyncLibraryEvidenceWorkerTest#an unconfirmed empty response is a private-profile no-op, never a committed library` | Aut — pending verification |
| 2 | Confirmed empty library | `work.SteamSyncLibraryEvidenceWorkerTest#a confirmed empty library commits durable evidence and confirmation through the worker` | Aut — pending verification |
| 3 | Worker exits without credentials | `work.SteamSyncLibraryEvidenceWorkerTest#missing credentials produce an attributable not performed outcome` (+ `data.repo.LibraryPollRepositoryTest#a worker exiting without credentials is not performed, not committed`) | Aut — pending verification |
| 4 | Account admission refuses a poll | `work.SteamSyncLibraryEvidenceWorkerTest#an account admission refusal is attributable and never a committed library` (+ `#a stored-library account mismatch is refused at the boundary`) | Aut — pending verification |
| 5 | Two polls overlap | `work.SteamSyncLibraryEvidenceWorkerTest#overlapping manual and periodic polls each keep their own attributable outcome` (two real bodies on an async barrier; 20-min increase credited exactly once) | Aut — pending verification |
| 6 | Initial library commit | `data.local.LibraryPollConfirmationTransactionTest#an accepted first commit records confirmation and evidence atomically with the library` | Aut — pending verification |
| 7 | Interrupted initial commit | `data.local.LibraryPollConfirmationTransactionTest#an interrupted initial commit records neither library nor separate evidence` (interrupted-transaction boundary; real kill → M5) | Aut + M5 |
| 8 | Confirmed zero-game baseline | `data.local.LibraryPollConfirmationTransactionTest#a confirmed empty library commits a zero-game baseline` | Aut — pending verification |
| 9 | Private response before baseline | `data.local.LibraryPollConfirmationTransactionTest#a rejected or private response records a not performed outcome, never readiness` | Aut — pending verification |
| 10 | Later refresh fails | `data.local.LibraryPollConfirmationTransactionTest#a later failed refresh cannot regress the committed record and retains readiness` (+ `data.repo.LibraryPollRepositoryTest#a later failed refresh does not revoke readiness`) | Aut — pending verification |
| 11 | Account or uncertain restored data | `data.backup.LibraryEvidenceBackupBoundaryTest#restoring an unverified backup never grants readiness, even with imported rows` (+ `#restoring preserves already-trusted local confirmation and evidence`; `data.repo.LibraryPollRepositoryTest#restored evidence never confirms readiness`) | Aut — pending verification |

Task-level fixture (2.4 crash-after-raw-commit evidence, no dedicated scenario):
`work.SteamSyncLibraryEvidenceWorkerTest#a committed effect survives a later retry failure of the same work`.

---

## 2. `playtime-backfill` (16 scenarios)

| # | #### Scenario | Concrete test class#method / manual check | Status |
|---|---|---|---|
| 1 | Importing history | `domain.PlaytimeBackfillUseCaseImportTest#freshImportFreezesOffsetsFlagAndMarkerThenClearsOnSuccessfulRecompute` | Aut — pending verification |
| 2 | History not counted by default | `domain.PlaytimeBackfillUseCaseImportTest#resetClearsOffsetsAndFlagAndPreservesSessionsAndStreaks` — closest existing test of the invariant (no offsets ⇒ XP is tracked-only, 30 XP); **no dedicated fresh-install test found** | Aut — pending verification |
| 3 | Baseline is unavailable | `domain.PlaytimeBackfillUseCaseImportTest#noBaselineWritesNothing` (+ `#baselineForAnotherAccountIsNotReadiness`) | Aut — pending verification |
| 4 | Confirmed empty library | `domain.PlaytimeBackfillUseCaseImportTest#confirmedEmptyLibraryImportsZeroChanges` | Aut — pending verification |
| 5 | Steam counters have no dates | `domain.PlaytimeBackfillUseCaseImportTest#manualSharedAndHiddenEvidenceAreUntouched` (asserts "No dated session is invented from lifetime counters") | Aut — pending verification |
| 6 | Repeated invocation does nothing | `domain.PlaytimeBackfillUseCaseImportTest#alreadyImportedIsANoOpWithNoAddedOffsets` | Aut — pending verification |
| 7 | New playtime still accrues after import | `domain.PlaytimeBackfillUseCaseImportTest#concurrentImportAndSyncCommitProduceCoherentMinutes` (frozen offset 100 + tracked 15 accrue on top; coherent total) | Aut — pending verification |
| 8 | Growing Steam total is not re-imported | `domain.PlaytimeBackfillUseCaseImportTest#growingSteamTotalsAreNotReimported` | Aut — pending verification |
| 9 | Imported history survives syncs | `domain.PlaytimeBackfillUseCaseImportTest#growingSteamTotalsAreNotReimported` (backfill stayed 100 across a Steam-field refresh that raised the lifetime total) | Aut — pending verification |
| 10 | Import overlaps a sync commit | `domain.PlaytimeBackfillUseCaseImportTest#concurrentImportAndSyncCommitProduceCoherentMinutes` (same snapshot, each minute counted once) | Aut — pending verification |
| 11 | Interrupted raw import | `domain.PlaytimeBackfillUseCaseImportTest#roomTransactionScopeRollsBackPartialRawWrites` (+ `#failedRawCommitWritesNothingAndReturnsFailure`; real kill → M5) | Aut + M5 |
| 12 | Interrupted recomputation | `domain.PlaytimeBackfillUseCaseImportTest#pendingRecomputeResumeDoesNotRefreezeFromGrowingTotals` (marker held ⇒ resume from frozen offsets; real kill → M5) | Aut + M5 |
| 13 | Repeated request while recomputation is pending | `domain.PlaytimeBackfillUseCaseImportTest#pendingRecomputeResumeDoesNotRefreezeFromGrowingTotals` (retry resumes, never a fresh import) | Aut — pending verification |
| 14 | Another operation recomputes while import recovery is pending | `domain.GamificationUpdaterPendingRecomputeTest#syncRecomputeWhileBackfillPendingIsAdministrativeAndSilent` (+ `#staleResultComputedBeforeRawCommitIsRefusedWhileMarkerPending`; `#xpIntegrityCorrectionStillTakesPrecedenceOverPendingImport`) | Aut — pending verification |
| 15 | Account changes after confirmation | `domain.PlaytimeBackfillUseCaseImportTest#requestForAnotherActiveAccountIsSuperseded` (+ `#importDuringPendingAccountResetIsSuperseded`; `#pendingRecomputeForAnotherAccountIsSuperseded`; live switch → M6) | Aut + M6 |
| 16 | Existing completed import upgrades | `domain.PlaytimeBackfillUseCaseImportTest#legacyCompletedImportWithoutBaselineStaysCompleted` | Aut — pending verification |

Task 5.4 preservation fixtures (no dedicated scenario): `domain.PlaytimeBackfillUseCaseImportTest
#convertedSharedToOwnedCreditIsRaisedWithoutErasing`, `#convertedCreditAndSnapshotCoalesceToTheCoherentMax`,
`#manualSharedAndHiddenEvidenceAreUntouched`. Consent/recovery identity: `domain.HistoryImportCoordinatorTest`
(#consentIsRecordedBeforeLaunchAndSurvivesLaunchFailure, #recoverPendingReplaysARecordedConsentExactlyOnce,
#recoverPendingVoidsAfterRawResetCommittedBeforeBookkeeping …) and `data.history.DataStoreHistoryImportRequestStoreTest`.

---

## 3. `onboarding-credentials` (15 scenarios)

| # | #### Scenario | Concrete test class#method / manual check | Status |
|---|---|---|---|
| 1 | Setup offered after credentials are saved | `ui.onboarding.OnboardingPhaseNavigationTest#firstRunSaveClaimsSetupStepDuably` | Aut — pending verification |
| 2 | Declining setup | `work.setup.SetupCoordinatorTest#decliningSetupRunsNothingAndSkipsEverything` (+ `ui.onboarding.OnboardingPhaseNavigationTest#setupDoneRoutesIntoHistoryChoiceNotCompleted` for the shared leave-setup→choice routing) | Aut — pending verification |
| 3 | Editing credentials later | `domain.FirstRunJourneyCoordinatorTest#configuredInstallWithoutAClaimIsNotOwed` (+ `ui.onboarding.OnboardingAsyncIdentityTest#theDisplayedResolutionPublishesAndVerifiedCredentialsPersist`); re-entry surface → M7 | Aut + M7 |
| 4 | Step count reflects the flow | `ui.onboarding.VerificationDecisionTest#theCredentialStepCountIsDerivedFromTheFlow` | Aut — pending verification |
| 5 | Background setup finishes before the decision | `work.setup.SetupCoordinatorTest#theClaimSurvivesWorkCompletionAndIsClearedOnlyByExplicitRelease` (+ `ui.onboarding.OnboardingPhaseNavigationTest#coldLaunchResumesAnOwedHistoryChoiceWithoutCredentialSteps`) | Aut — pending verification |
| 6 | Ready library | `ui.onboarding.HistoryChoiceContentTest#readyChoiceDoesNotImplyConsentAndSkipImportsNothing` | Aut — pending verification |
| 7 | No baseline available | `ui.onboarding.HistoryChoiceContentTest#unavailableBaselineAllowsExitAndSetupRecoveryWithoutImport` | Aut — pending verification |
| 8 | User skips the decision | `ui.onboarding.OnboardingPhaseNavigationTest#historySkipDefersTheDecisionAndEntersHome` (+ `domain.FirstRunJourneyCoordinatorTest#deferredWithoutARequestIdNeverStartsAnImport`) | Aut — pending verification |
| 9 | Import already exists | `ui.onboarding.HistoryChoiceContentTest#existingImportOffersContinueRatherThanASecondImport` (+ `domain.FirstRunJourneyCoordinatorTest#replayAfterFullSettlementIsAlreadyImportedAndNeverRefreezes`) | Aut — pending verification |
| 10 | Process death on the unanswered choice | `ui.onboarding.OnboardingPhaseNavigationTest#coldLaunchResumesAnOwedHistoryChoiceWithoutCredentialSteps` (real kill → M5) | Aut + M5 |
| 11 | Process death after consent | `domain.FirstRunJourneyCoordinatorTest#killBetweenPhasePersistAndCoordinatorStoreReplaysTheExactPhaseUuid` (+ `domain.HistoryImportCoordinatorTest#recoverPendingReplaysARecordedConsentExactlyOnce`; real kill → M5) | Aut + M5 |
| 12 | Import cannot complete | `domain.HistoryImportCoordinatorTest#uncaughtImportFailureSurfacesAsAttributableFailedStateAndReleasesBusy` (+ `ui.onboarding.HistoryChoiceContentTest#pendingRecomputeHasRecoveryAndAlwaysAllowsExplicitExit`) | Aut — pending verification |
| 13 | User continues while recovery is pending | `ui.onboarding.OnboardingPhaseNavigationTest#skipIsAvailableWhileAnAdmittedImportIsPending` (+ `ui.onboarding.HistoryChoiceContentTest#pendingRecomputeHasRecoveryAndAlwaysAllowsExplicitExit`; `domain.FirstRunJourneyCoordinatorTest#deferredWithRequestIdSettlesTheAdmittedConsentWithoutReopening`) | Aut — pending verification |
| 14 | Previously configured install upgrades | `data.repo.FirstRunJourneyRepositoryTest#restoreLegacyLeavesAConfiguredInstallWithoutAClaimOutOfOnboarding` (+ `domain.FirstRunJourneyCoordinatorTest#configuredInstallWithoutAClaimIsNotOwed`) | Aut — pending verification |
| 15 | Steam account changes | `domain.FirstRunJourneyCoordinatorTest#replacementAccountIsNeverAdvancedByAnOldPhaseOrRequest` (+ `data.repo.FirstRunJourneyRepositoryTest#replacementAccountRejectsOldPhaseAndRequestCallbacks`; live switch with in-flight work → M6) | Aut + M6 |

---

## 4. `first-run-setup` (39 scenarios)

| # | #### Scenario | Concrete test class#method / manual check | Status |
|---|---|---|---|
| 1 | Stages presented in registered order | `work.setup.SetupStageRegistryTest#stageIdsArePinnedBecauseTheyArePersisted` (+ `work.setup.ForegroundAttemptTest#admissionOrderIsRegisteredOrderFilteredToSelection`) | Aut — pending verification |
| 2 | A stage is added | `ui.setup.SetupUiDerivationTest#aNewlyRegisteredStageAppearsEverywhere` | Aut — pending verification |
| 3 | Identifiers are stable | `work.setup.SetupOutcomeTest#projectionDropsUnknownIdsAndFillsMissingOnes` (+ `data.setup.DataStoreSetupStateStoreTest#unknownStageAttemptsAreRetainedByTheStoreNotDroppedOrInvented`; `work.setup.SetupCoordinatorTest#storedRecordsForStagesThisBuildDoesNotKnowAreIgnored`) | Aut — pending verification |
| 4 | A stage whose prerequisite is absent | `work.setup.SetupCoordinatorTest#anUnavailableStageCannotRunAndDoesNotBlockTheOthers` (+ `ui.setup.SetupUiDerivationTest#anUnavailableStageIsShownButNotSelectable`) | Aut — pending verification |
| 5 | An earlier stage remains pending | `work.setup.ForegroundAttemptTest#anEarlierStageSettlingDoesNotStopALaterStageBeingObserved` (+ `#laterStagesAreAdmittedInOrderAndEachStaysObservedUntilItSettles`; `work.setup.SetupCoordinatorTest#queuedAdmissionSettlesTheStageAndAdmitsLaterStagesInOrder`) | Aut — pending verification |
| 6 | Entering the app while detached stages run | `work.setup.SetupCoordinatorTest#theAppIsUsableOnceInScreenStagesSettleWhileDetachedWorkStillRuns` | Aut — pending verification |
| 7 | Detached stage reports its own progress | `work.setup.ForegroundAttemptTest#detachedStageSettlesOnceAdmittedAndNotBefore` (per-stage own observation; real per-stage notification rendering → M3) | Aut + M3 |
| 8 | Detached stage survives process death | `work.setup.SetupCoordinatorTest#reconcileResumesEachPendingAssociationAndAdmitsRemainingWithoutDuplicates` (restart reconciliation; real kill → M4) | Aut + M4 |
| 9 | Notification permission not granted | **M3** — see procedures | M3 — not run (no connected device) |
| 10 | Permission requested before detaching | `ui.setup.SetupUiDerivationTest#theNotificationRequestIsWarrantedOnlyWhenWorkWillDetach` (→ M3 for the on-device runtime prompt) | Aut + M3 |
| 11 | Continue during an offline wait | `work.setup.ForegroundAttemptTest#continueEndsTheWaitButKeepsRemainingAdmissionsInOrder` (+ `work.setup.SetupCoordinatorTest#continuingDuringForegroundWorkStillAdmitsTheRemainingSelectedStages`; real airplane toggle → M2) | Aut + M2 |
| 12 | One stage fails | `work.setup.SetupCoordinatorTest#aRunnerThatThrowsIsIsolatedToItsOwnStage` | Aut — pending verification |
| 13 | Results of other stages preserved | `work.setup.SetupCoordinatorTest#retryReplacesOnlyTheRequestedStagesAttemptAndPreservesSuccessfulSiblings` (+ `ui.setup.SetupRecoveryUiTest#retryTargetsOnlyTheFailedRowAndKeepsSuccessVisible`) | Aut — pending verification |
| 14 | Setup completes with a failure | `ui.setup.SetupUiDerivationTest#finishedStagesStayVisibleWhileLaterOnesRun` (per-stage summary, no global failure badge) | Aut — pending verification |
| 15 | Failure is attributable | `work.setup.WorkStageRunnerTest#theWorkersAttributableFailureReasonIsReported` | Aut — pending verification |
| 16 | Retry backoff is not terminal failure | `work.setup.ForegroundAttemptTest#retryBackoffSettlesTheStageWithoutManufacturingFailure` (+ `work.setup.WorkStageRunnerTest#aQueuedRetryIsRetryScheduledNotATerminalFailure`; `work.setup.ConcurrentSetupCharacterizationTest#a queued retry is retry scheduled not a stored failure`) | Aut — pending verification |
| 17 | A stage is cancelled explicitly | `work.setup.WorkStageRunnerTest#aCancelledJobIsReportedAsCancelledDistinctly` (+ `ui.setup.SetupRecoveryUiTest#cancellationAndMissingWorkHaveDistinctAccessibleStates`) | Aut — pending verification |
| 18 | Retrying a failed stage | `work.setup.SetupCoordinatorTest#retryReplacesOnlyTheRequestedStagesAttemptAndPreservesSuccessfulSiblings` (+ `ui.onboarding.OnboardingSetupRecoveryTest#aFailedStageAfterTheRunHasSettledOffersRetry`) | Aut — pending verification |
| 19 | Retrying a succeeded stage | `ui.setup.SetupRecoveryUiTest#retryTargetsOnlyTheFailedRowAndKeepsSuccessVisible` (succeeded row exposes "Run again") | Aut — pending verification |
| 20 | Retry does not duplicate running work | `work.setup.StageAdmissionTest#duplicateTapOnLiveWorkKeepsOneReusedAdmission` (+ `work.setup.SetupCoordinatorTest#duplicateRetryWhileTheForegroundIsActiveIsRejected`; `work.setup.SetupStageRegistryTest#aStageStartedTwiceDoesNotStackDuplicateWork`) | Aut — pending verification |
| 21 | Work is already scheduled for retry | `work.setup.SetupCoordinatorTest#retryScheduledCanLaterBecomeSucceededAndIsNeverFailedInBetween` (+ `work.setup.WorkStageRunnerTest#aQueuedRetryIsRetryScheduledNotATerminalFailure`) | Aut — pending verification |
| 22 | Subset retry preserves successful siblings | `work.setup.SetupCoordinatorTest#retryReplacesOnlyTheRequestedStagesAttemptAndPreservesSuccessfulSiblings` (+ `#onboardingStartPreservesAPriorSuccessfulSibling`; `ui.setup.SetupRecoveryUiTest#retryTargetsOnlyTheFailedRowAndKeepsSuccessVisible`) | Aut — pending verification |
| 23 | Running setup after declining it | `work.setup.SetupCoordinatorTest#runsOnlySelectedStagesAndRecordsTheRestSkipped` (deliberate subset run; Settings re-entry surface → M7) | Aut + M7 |
| 24 | Last outcome shown | `ui.setup.SetupUiDerivationTest#backgroundOperationsStayVisibleAfterForegroundSettlement` (+ `ui.setup.SetupRecoveryUiTest#pendingRowsOfferObservationAndNeverClaimCompletion`) | Aut — pending verification |
| 25 | Re-run defaults to nothing selected | `ui.setup.SetupViewModelSelectionTest#nextRunUsesExactlyTheVisibleSelectionAndStartsEmptyAfterSettlement` (+ `#foregroundRunLocksSelectionButSettlementAllowsEditingAgain`; `ui.setup.SetupUiDerivationTest#declaredDefaultsDriveTheOnboardingSelection`) | Aut — pending verification |
| 26 | Completion gates nothing | `work.setup.SetupCoordinatorTest#decliningSetupRunsNothingAndSkipsEverything` (never-run/skipped state leaves app usable; `#theAppIsUsableOnceInScreenStagesSettleWhileDetachedWorkStillRuns`) | Aut — pending verification |
| 27 | A stage added after a completed setup | `ui.setup.SetupUiDerivationTest#aNewlyRegisteredStageAppearsEverywhere` (+ `work.setup.SetupOutcomeTest#projectionDropsUnknownIdsAndFillsMissingOnes`; `domain.FirstRunJourneyCoordinatorTest#configuredInstallWithoutAClaimIsNotOwed` for no re-presentation) | Aut — pending verification |
| 28 | Editing after a completed attempt | `ui.setup.SetupViewModelSelectionTest#foregroundRunLocksSelectionButSettlementAllowsEditingAgain` (+ `ui.setup.SetupUiDerivationTest#editingAfterAFinishedRunControlsTheCheckboxesNotTheCompletedRunsSelection`; `ui.onboarding.OnboardingSetupRecoveryTest#editedSelectionAfterSettlementOffersTheNextStartWithoutErasingTheResult`) | Aut — pending verification |
| 29 | Existing work is reused | `work.setup.WorkStageRunnerTest#existingLiveWorkIsAdmittedAsReusedWithoutADuplicate` (+ `work.setup.StageAdmissionTest#keepRetainedCandidateIsReused`; `work.setup.ConcurrentSetupCharacterizationTest#existing running manual work is admitted as reused and observed to completion`) | Aut — pending verification |
| 30 | A job is waiting on constraints | `work.setup.ForegroundAttemptTest#queuedWaitingSettlesTheStageAndKeepsTheWaitingOperation` (+ `work.setup.SetupOperationStateTest#waitingIsPendingButNotTerminalAndNotARetry`; `ui.setup.SetupRecoveryUiTest#pendingRowsOfferObservationAndNeverClaimCompletion`) | Aut — pending verification |
| 31 | Scheduler completes without a library commit | `work.setup.LibraryStageAttributableResultTest#schedulerSuccessWithoutRoomEvidenceIsRecoveryRequiredNotSuccess` (+ `#privateOrUnconfirmedResponseIsAnAttributableFailureNotALibrarySuccess`; `work.SteamSyncLibraryEvidenceWorkerTest#an unconfirmed empty response is a private-profile no-op, never a committed library`) | Aut — pending verification |
| 32 | No artwork is currently available | `work.setup.WorkStageRunnerTest#artworkExplicitZeroTotalIsASuccessfulZeroItemResultWithReRunExplanation` (+ `#artworkMissingLegacyTotalIsNeverInventedAsAZeroGameDownload`; `work.setup.HltbDatasetWorkerTest#nonDownloadStagesRemainIndeterminateButExplainWhatIsHappening`) | Aut — pending verification |
| 33 | Retry succeeds after setup moves on | `work.setup.SetupCoordinatorTest#retryScheduledCanLaterBecomeSucceededAndIsNeverFailedInBetween` | Aut — pending verification |
| 34 | Process dies with more than one admitted stage | `work.setup.SetupCoordinatorTest#reconcileResumesEachPendingAssociationAndAdmitsRemainingWithoutDuplicates` (simulated restart; real multi-admission kill → M4) | Aut + M4 |
| 35 | Process dies between admission and association | `work.setup.SetupCoordinatorTest#reconcileNeverAutoRetriggersAPersistedRequestThatNeverAdmitted` (+ `work.setup.StageAdmissionTest#anEmptyChainAfterAFailedEnqueueYieldsNull` / `#onlyStaleHistoricalRecordsYieldNullNeverAFabricatedJob`; `data.setup.DataStoreSetupStateStoreTest#anIntentOnlyAttemptNeverGainsAFabricatedJob`; real kill in the gap → M4) | Aut + M4 |
| 36 | User continued before process death | `work.setup.SetupCoordinatorTest#reconcileNeverReopensAnExplicitlyDismissedJourney` (+ `domain.FirstRunJourneyCoordinatorTest#deferredWithRequestIdSettlesTheAdmittedConsentWithoutReopening`; real kill after Continue → M4) | Aut + M4 |
| 37 | Superseded result arrives late | `work.setup.SetupCoordinatorTest#anOldObservedGenerationCannotOverwriteANewAttempt` (+ `#oldAccountCompletionsCannotOverwriteAReplacementAttempt`; `work.setup.ForegroundAttemptTest#firstSettlementWinsPerStage`; `data.repo.FirstRunJourneyRepositoryTest#replacementAccountRejectsOldPhaseAndRequestCallbacks`; live account switch → M6) | Aut + M6 |
| 38 | A pending job is unavailable | `work.setup.SetupCoordinatorTest#missingOrPrunedJobsOfferExplicitRecoveryNotInfiniteWaiting` (+ `work.setup.WorkStageRunnerTest#aMissingOrPrunedJobIsRecoveryRequiredNotSuccess`; `work.setup.ForegroundAttemptTest#missingOperationSettlesWithExplicitRecoveryInsteadOfWaitingForBudget`) | Aut — pending verification |
| 39 | Legacy records are retained | `work.setup.SetupCoordinatorTest#aHistoricalLegacyFailureWithoutAJobIdStaysHistorical` (+ `#legacyActiveMarkerWithAWorkIdIsConvertedToALiveAttempt`; `data.setup.DataStoreSetupStateStoreTest#legacyOutcomeKeysRemainReadableAndKnownOutcomesSurvive`) | Aut — pending verification |

---

## 5. `app-settings` (14 scenarios)

| # | #### Scenario | Concrete test class#method / manual check | Status |
|---|---|---|---|
| 1 | Opening setup from Settings | **M7(a)** — see procedures (no dedicated Settings→setup entry test found) | M7 — not run (no connected device) |
| 2 | Last outcome shown per stage | `ui.setup.SetupUiDerivationTest#backgroundOperationsStayVisibleAfterForegroundSettlement` (+ `ui.setup.SetupRecoveryUiTest#pendingRowsOfferObservationAndNeverClaimCompletion`) — shared checklist projection used by Settings; Settings surface rendering → M7 | Aut + M7 |
| 3 | Nothing selected by default | `ui.setup.SetupViewModelSelectionTest#nextRunUsesExactlyTheVisibleSelectionAndStartsEmptyAfterSettlement` (shared onboarding/Settings selection) | Aut — pending verification |
| 4 | Running selected stages | `work.setup.SetupCoordinatorTest#runsOnlySelectedStagesAndRecordsTheRestSkipped` (+ `#retryReplacesOnlyTheRequestedStagesAttemptAndPreservesSuccessfulSiblings`) | Aut — pending verification |
| 5 | Setup never run | `work.setup.SetupOperationStateTest#neverRunIsNeitherPendingNorTerminal` (+ entry-present/when-never-run surface → M7) | Aut + M7 |
| 6 | Credentials not configured | `work.setup.SetupCoordinatorTest#unknownAccountRefusesAdmissionWithoutPersistingIntent` (+ connect-Steam-first checklist copy on device → M7) | Aut + M7 |
| 7 | Edit selections after settlement | `ui.setup.SetupViewModelSelectionTest#foregroundRunLocksSelectionButSettlementAllowsEditingAgain` (+ `ui.onboarding.OnboardingSetupRecoveryTest#editedSelectionAfterSettlementOffersTheNextStartWithoutErasingTheResult`) | Aut — pending verification |
| 8 | Work continues after onboarding | `work.setup.SetupCoordinatorTest#reconcileResumesEachPendingAssociationAndAdmitsRemainingWithoutDuplicates` (+ `#reconcileNeverReopensAnExplicitlyDismissedJourney`; real cold restart → M4) | Aut + M4 |
| 9 | Import presented in Settings | `ui.settings.HistoryImportCardTest#deferredChoiceStillOffersImportOnlyAfterExplicitConfirmation` (Data & privacy control retained, confirmation + one-time behavior) | Aut — pending verification |
| 10 | Import not presented on Home | **M7(d)** — see procedures (parent is adding an actual Compose Home semantics test; until it lands this is manual) | M7 — not run (no connected device) |
| 11 | History was skipped during onboarding | `ui.settings.HistoryImportCardTest#deferredChoiceStillOffersImportOnlyAfterExplicitConfirmation` | Aut — pending verification |
| 12 | Baseline needed | `ui.settings.HistoryImportCardTest#missingBaselineDoesNotCreateConsentAndOffersSetupRecovery` | Aut — pending verification |
| 13 | Raw import awaits recomputation | `ui.settings.HistoryImportCardTest#rawCommittedPendingDoesNotClaimFullCompletionAndResumesExistingRequest` (+ `ui.screenshot.SetupRecoveryScreenshotTest` renders the recovery-pending card) | Aut — pending verification |
| 14 | Existing reset safeguards | `ui.settings.HistoryImportCardTest#cloudTransferStillBlocksResetUntilReversal` (+ `domain.PlaytimeBackfillUseCaseImportTest#resetIsBlockedWhileCloudImportedPlayTransferIsApplied`; `ui.settings.SettingsPresentationTest#steamHistoryResetIsDisabledOnlyWhileAnAppliedCloudTransferNeedsUndo`) | Aut — pending verification |

---

## Named manual acceptance checks (M1–M7)

Procedures are written for a debug build on a connected device. **Capture only**: `stageId`,
opaque attempt/work id (never the Steam ID), unique work name, `REUSED`/`NEW` admission,
scheduler state + attempt count, foreground-settlement reason, attributable error text. **Never
capture** a raw account identifier (`steamId`, `7656119…`), API key, secret/token, or any
owned-library payload; re-apply the redaction assertion pattern already used by
`ConcurrentSetupCharacterizationTest` (§3). Each check names its expected truthful exit and
idempotence requirement.

- **M1 — Concurrent real manual/periodic Steam work + live-monitor/post-play handoff, redacted
  record.** Start a real periodic sync on device; play a game through the live-monitor and end the
  session so a `post-play-sync-*` handoff is enqueued; then run setup with the library_sync stage
  selected while the manual/periodic poll is running. Expected truthful exit: the stage attaches to
  the running job as `REUSED` with no duplicate; the handoff chain keeps its own identity and is
  never cancelled by setup; the diagnostic record contains stage/work identities, scheduler states,
  and errors with the redaction rules above. Idempotent: a second start attaches the same live work,
  never stacks a second poll. (Host-side mechanics for this are already covered by
  `ConcurrentSetupCharacterizationTest` with double bodies and an ENQUEUED-only post-play chain; M1
  is the real-workload complement.)
- **M2 — Airplane-mode/backoff and the 120 s budget never cancel.** With library_sync admitted,
  toggle airplane mode on mid-run, let the job enter WorkManager retry backoff, and let the
  foreground observation pass the injected 120 s monotonic budget. Expected truthful exit: the stage
  shows waiting/retry-scheduled (never failed) the whole time; later independent stages still admit;
  on budget expiry nothing is cancelled or failed and the live job continues; after connectivity
  returns the retry succeeds and the stage reconciles to succeeded. Idempotent: backoff is never
  bypassed by a duplicate request.
- **M3 — Notification permission denied + worker-owned progress.** Decline the runtime
  notification prompt (or deny it in app settings on a configured install) and run setup with a
  detached stage (completion_times). Expected truthful exit: the detached stage still runs, its
  progress stays observable in-app, and the missing permission is never reported as failure. With
  permission granted, the detached stage posts its **own** progress notification, separate from any
  other stage's. Idempotent: re-running setup does not double-post or misattribute progress.
- **M4 — Real process death: multiple admitted work, admission-association gap, Continue.** (a)
  Select multiple stages, start, then kill the process while more than one admitted job is pending;
  cold launch must reconcile each stage with its own admitted operation (no duplicate enqueue, no
  cross-stage attribution). (b) Kill between the durable request-intent write and the exact
  admission-association write; cold launch must reconcile the recorded request identity first, never
  attach an arbitrary historical job, and offer explicit recovery if admission cannot be
  established. (c) Tap Continue/do-later while an admitted import or detached work is pending, then
  kill; cold launch must stay on Home (onboarding does not reopen; the deferred journey does not
  re-open) with the operation still recoverable through Settings. Idempotent: no job is enqueued
  twice. (Automated persistence-boundary coverage exists via the `reconcile*` /
  interrupted-transaction tests; they do not claim OS death.)
- **M5 — Kill consent/import at all four boundaries; reset intent cannot reimport.** From the
  history choice, tap Import and kill the process (a) before the consent/request write completes —
  cold launch must show no credits and no completed import; (b) after consent is recorded but before
  the raw commit — cold launch replays the recorded request exactly once, no double import; (c)
  after the raw commit but before derived recomputation — cold launch resumes recomputation from the
  frozen offsets, Settings shows recovery pending (never "untouched" or fully completed), and
  imported XP is not announced as earned; (d) during recomputation — the same, completing silently.
  Then record an import **reset** intent, kill before the reset settles, cold launch, and verify the
  reset intent is voided after replay and the same request id can never re-import. Idempotent:
  repeated invocation adds no offsets.
- **M6 — Account switch with old callbacks in flight.** Start import/setup work for account A,
  then switch to account B while A's worker callbacks are still running (e.g., a poll completes
  after the switch). Expected truthful exit: the old operation is refused/superseded at the account
  boundary, commits nothing for B, and never overwrites B's setup attempt, readiness, or reactive
  checklist; a concurrent periodic/manual commit still credits the playtime increase exactly once
  for the surviving account.
- **M7 — Settings setup entry, never-run, no credentials, credential-edit, Home import
  absence.** (a) Open Settings → setup entry on a configured, never-run install: the entry is
  present and every stage shows never run with nothing selected. (b) Open the checklist with no
  credentials configured: it explains Steam must be connected first and admits no stage work. (c)
  Re-edit credentials on an already-configured install: neither setup nor the history decision is
  re-presented unprompted. (d) On Home, verify the Steam history import and its reset are not
  presented anywhere. (Parent is adding an actual Compose Home semantics test for (d); until it
  lands that check is manual.) Each exit is truthful (never-run vs pending vs terminal) and
  idempotent (re-entering does not mutate records).

---

## Task 8 rows

- **8.1 — verification sweep.** Every scenario above is mapped (95/95, 0 unmapped) to either an
  existing automated test or a named manual check (M1–M7). The mapped automated tests must be run
  via the 8.1 command (plus focused Room-migration / WorkManager / UI suites); initial status is
  **pending verification**, not passed. No test is described as "blocked host" — manual coverage is
  the assigned acceptance path where automation does not exist.
- **8.2 — Roborazzi goldens.** `ui.screenshot.SetupRecoveryScreenshotTest` /
  `MainScreenScreenshotTest` re-recording and stale-golden removal follow
  `docs/visual-regression-screenshots.md`; baseline changes must ship with the UI change. Status:
  pending.
- **8.3 — device acceptance: BLOCKED.** No ADB device is connected to this session, so the
  on-device build exercise (manual/periodic sync, active play/live monitoring, airplane-mode
  transitions, retry backoff, denied notifications, process death before/after Continue and import
  commit) cannot be run. This is an explicit blocker for 8.3 only; the named manual checks M1–M7
  are the ready-to-run device script for it once a device is attached.
- **8.4 — strict validation and diff review.** Pending; after 8.1–8.3 evidence, `openspec validate
  --strict`, F06/F32 traceability against the overlap map, and the no-#159/#175 / no-cloud-fetch /
  no-extra-bundling checks apply.

## Counts and unmapped

| Capability (delta spec) | Scenarios | Automated row (pending verification) | Manual row (M1–M7) |
|---|---|---|---|
| `steam-sync` | 11 | 11 | 0 |
| `playtime-backfill` | 16 | 16 | 0 |
| `onboarding-credentials` | 15 | 15 | 0 |
| `first-run-setup` | 39 | 38 (all but #9) | 1 (#9 → M3) |
| `app-settings` | 14 | 12 (all but #1, #10) | 2 (#1 → M7, #10 → M7) |
| **Total** | **95** | **92** | **3 manual-only rows; many automated rows carry an M# real-device supplement** |

- **Unmapped scenarios: 0** (required: 0).
- The 92 automated rows are all **pending verification** — the previously verified 55-test run is
  historical and is not a new pass for this table.