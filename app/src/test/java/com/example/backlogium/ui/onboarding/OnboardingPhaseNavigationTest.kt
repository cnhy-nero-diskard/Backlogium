package com.example.backlogium.ui.onboarding

import android.net.Uri
import com.example.backlogium.data.backup.BackupExportGateway
import com.example.backlogium.data.history.HistoryImportRequestRecord
import com.example.backlogium.data.repo.AccountChangeGateway
import com.example.backlogium.data.repo.CredentialVerification
import com.example.backlogium.data.repo.CredentialsSaveResult
import com.example.backlogium.data.repo.CredentialsState
import com.example.backlogium.data.repo.OnboardingCredentialsGateway
import com.example.backlogium.data.repo.SteamIdResolution
import com.example.backlogium.domain.FirstRunJourney
import com.example.backlogium.domain.FirstRunJourneyGateway
import com.example.backlogium.domain.FirstRunPhase
import com.example.backlogium.domain.HistoryImportResult
import com.example.backlogium.domain.HistoryImportState
import com.example.backlogium.work.setup.FirstRunSetupGateway
import java.util.ArrayDeque
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Milestone B phase navigation through the real ViewModel wiring (tasks 6.2/6.4): credentials ->
 * SETUP -> setup done -> HISTORY_CHOICE -> skip/continue -> Home, plus cold-launch phase restore
 * (no credential repeat, loading covers the resolve window) and constructor backcompat.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingPhaseNavigationTest {

    // -------------------------------------------------------------------- transitions

    @Test
    fun firstRunSaveClaimsSetupStepDuably() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val credentials = FakeCredentials(configured = null)
            val journey = FakeJourney()
            val viewModel = OnboardingViewModel(credentials, FakeBackup(), FakeAccountChange(), journey)
            advanceUntilIdle()

            enterCredentialsAndSave(viewModel, credentials)

            assertEquals(OnboardingStep.SETUP, viewModel.uiState.value.step)
            assertEquals(FirstRunPhase.SETUP, journey.phase())
            assertFalse(viewModel.uiState.value.completed)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun setupDoneRoutesIntoHistoryChoiceNotCompleted() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val credentials = FakeCredentials(configured = null)
            val journey = FakeJourney()
            val viewModel = OnboardingViewModel(credentials, FakeBackup(), FakeAccountChange(), journey)
            advanceUntilIdle()
            enterCredentialsAndSave(viewModel, credentials)
            assertEquals(OnboardingStep.SETUP, viewModel.uiState.value.step)

            viewModel.onSetupDone()
            advanceUntilIdle()

            // The decision is owed: the flow routes to the history choice, the journey advances
            // durably, and the takeover is NOT released on setup completion alone.
            assertEquals(OnboardingStep.HISTORY_CHOICE, viewModel.uiState.value.step)
            assertEquals(FirstRunPhase.HISTORY_CHOICE, journey.phase())
            assertFalse(viewModel.uiState.value.completed)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun historySkipDefersTheDecisionAndEntersHome() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val credentials = FakeCredentials(configured = null)
            val journey = FakeJourney()
            val viewModel = OnboardingViewModel(credentials, FakeBackup(), FakeAccountChange(), journey)
            advanceUntilIdle()
            enterCredentialsAndSave(viewModel, credentials)
            viewModel.onSetupDone()
            advanceUntilIdle()
            assertEquals(OnboardingStep.HISTORY_CHOICE, viewModel.uiState.value.step)

            viewModel.onHistorySkip()
            advanceUntilIdle()

            assertEquals(FirstRunPhase.DEFERRED, journey.phase())
            assertTrue(viewModel.uiState.value.completed)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun successfulImportCompletesTheExactJourneyAndEntersHome() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val credentials = FakeCredentials(configured = null)
            val journey = FakeJourney(scriptedResults = ArrayDeque(listOf(HistoryImportResult.Imported(1, 100))))
            val viewModel = OnboardingViewModel(credentials, FakeBackup(), FakeAccountChange(), journey)
            advanceUntilIdle()
            enterCredentialsAndSave(viewModel, credentials)
            viewModel.onSetupDone()
            advanceUntilIdle()

            viewModel.onHistoryImport()
            advanceUntilIdle()

            assertEquals(FirstRunPhase.COMPLETE, journey.phase())
            assertTrue(viewModel.uiState.value.completed)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun skipIsAvailableWhileAnAdmittedImportIsPending() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val credentials = FakeCredentials(configured = null)
            val journey = FakeJourney(scriptedResults = ArrayDeque(listOf(HistoryImportResult.PendingRecompute)))
            val viewModel = OnboardingViewModel(credentials, FakeBackup(), FakeAccountChange(), journey)
            advanceUntilIdle()
            enterCredentialsAndSave(viewModel, credentials)
            viewModel.onSetupDone()
            advanceUntilIdle()

            viewModel.onHistoryImport()
            advanceUntilIdle()

            // Pending recompute keeps the surface on the decision, not a false completion.
            assertEquals(FirstRunPhase.IMPORT_REQUESTED, journey.phase())
            assertFalse(viewModel.uiState.value.completed)

            viewModel.onHistorySkip()
            advanceUntilIdle()

            assertEquals(FirstRunPhase.DEFERRED, journey.phase())
            assertTrue(viewModel.uiState.value.completed)
        } finally {
            Dispatchers.resetMain()
        }
    }

    // ------------------------------------------------------------- cold-launch restoration

    @Test
    fun coldLaunchResumesAnOwedHistoryChoiceWithoutCredentialSteps() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val steamId = "76561198000000001"
            val credentials = FakeCredentials(configured = CredentialsState.Configured("key", steamId))
            val journey = FakeJourney(
                activeSteamIdValue = steamId,
                resolved = FirstRunJourney(steamId, FirstRunPhase.HISTORY_CHOICE),
            )
            val viewModel = OnboardingViewModel(credentials, FakeBackup(), FakeAccountChange(), journey)
            advanceUntilIdle()

            // Resolved to the history choice, never re-entering credential entry.
            assertEquals(OnboardingStep.HISTORY_CHOICE, viewModel.uiState.value.step)
            assertFalse(viewModel.uiState.value.loading)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun coldLaunchResumesAnOwedSetupPhase() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val steamId = "76561198000000001"
            val credentials = FakeCredentials(configured = CredentialsState.Configured("key", steamId))
            val journey = FakeJourney(
                activeSteamIdValue = steamId,
                resolved = FirstRunJourney(steamId, FirstRunPhase.SETUP),
            )
            val viewModel = OnboardingViewModel(credentials, FakeBackup(), FakeAccountChange(), journey)
            advanceUntilIdle()

            assertEquals(OnboardingStep.SETUP, viewModel.uiState.value.step)
            assertEquals(steamId, viewModel.uiState.value.steamIdInput)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun unresolvedResumeStaysLoadingUntilTheJourneyResolves() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val credentials = FakeCredentials(configured = null)
            val journey = FakeJourney(blockResolved = true)
            val viewModel = OnboardingViewModel(credentials, FakeBackup(), FakeAccountChange(), journey)
            runCurrent()

            // Before the resolver completes, the surface must not show credential steps.
            assertTrue(viewModel.uiState.value.loading)

            journey.releaseResolved()
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.loading)
            assertEquals(OnboardingStep.API_KEY, viewModel.uiState.value.step)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun legacyFourArgumentConstructorRemainsAWorkingTestSeam() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val credentials = FakeCredentials(configured = null)
            val setup = FakeLegacySetup()
            val viewModel = OnboardingViewModel(credentials, FakeBackup(), FakeAccountChange(), setup)
            advanceUntilIdle()

            enterCredentialsAndSave(viewModel, credentials)

            // Legacy seam: claim maps to the old durable flag, then the edit-free straight-out
            // behavior the credential-flow tests rely on is still the same shape.
            assertTrue(setup.claimed)
            advanceUntilIdle()
        } finally {
            Dispatchers.resetMain()
        }
    }

    // -------------------------------------------------------------------- helpers

    private fun TestScope.enterCredentialsAndSave(viewModel: OnboardingViewModel, credentials: FakeCredentials) {
        viewModel.onApiKeyChange(KEY)
        viewModel.onSteamIdInputChange(INPUT)
        viewModel.resolveSteamId()
        runCurrent()
        credentials.resolveRequests.last().gate.complete(SteamIdResolution.Resolved(STEAM_ID))
        runCurrent()
        viewModel.finish()
        runCurrent()
        credentials.verifyRequests.last().gate.complete(CredentialVerification.Verified)
        runCurrent()
        assertTrue(credentials.saveCalls.isNotEmpty())
    }

    private class FakeJourney(
        var activeSteamIdValue: String? = STEAM_ID,
        var resolved: FirstRunJourney? = null,
        private var scriptedResults: ArrayDeque<HistoryImportResult> = ArrayDeque(),
        private val blockResolved: Boolean = false,
    ) : FirstRunJourneyGateway {
        private val resolverGate = CompletableDeferred<Unit>()
        private val journeyFlow = MutableStateFlow<FirstRunJourney?>(resolved)
        private val owedFlow = MutableStateFlow<Boolean?>(null)
        private val importState = MutableStateFlow<HistoryImportState>(HistoryImportState.Idle)
        private val pendingFlow = MutableStateFlow<HistoryImportRequestRecord?>(null)

        fun releaseResolved() {
            if (!resolverGate.isCompleted) resolverGate.complete(Unit)
        }

        override val journey: Flow<FirstRunJourney?> = journeyFlow
        override val firstRunOwed: Flow<Boolean?> = owedFlow
        override val historyImportState: Flow<HistoryImportState> = importState
        override val historyImportPendingRequest: Flow<HistoryImportRequestRecord?> = pendingFlow

        override suspend fun currentHistoryImportPendingRequest() = pendingFlow.value
        override suspend fun activeAccountSteamId() = activeSteamIdValue
        override suspend fun baselineConfirmed() = false
        override suspend fun importBackfilled() = false
        override suspend fun recomputePending(activeAccount: String?) = false

        override suspend fun resolvedJourney(): FirstRunJourney? {
            if (blockResolved && !resolverGate.isCompleted) resolverGate.await()
            return resolved ?: journeyFlow.value?.takeIf { it.accountSteamId == activeSteamIdValue }
        }

        fun phase(): FirstRunPhase? = journeyFlow.value?.phase

        override suspend fun claim(steamId: String) {
            journeyFlow.value = FirstRunJourney(steamId, FirstRunPhase.SETUP)
            owedFlow.value = true
        }

        override suspend fun setupDone(steamId: String): Boolean {
            journeyFlow.value = journeyFlow.value?.copy(phase = FirstRunPhase.HISTORY_CHOICE) ?: return false
            owedFlow.value = true
            return true
        }

        override suspend fun requestImport(steamId: String): HistoryImportResult {
            val requestId = journeyFlow.value?.importRequestId ?: "phase-req"
            journeyFlow.value = FirstRunJourney(steamId, FirstRunPhase.IMPORT_REQUESTED, requestId)
            pendingFlow.value = HistoryImportRequestRecord(steamId, 1L, requestId)
            val result = scriptedResults.pollFirst() ?: HistoryImportResult.Imported(1, 100)
            importState.value = HistoryImportState.Completed(result, requestId, steamId)
            if (result is HistoryImportResult.Imported ||
                result is HistoryImportResult.AlreadyImported ||
                result == HistoryImportResult.Superseded
            ) {
                pendingFlow.value = null
            }
            return result
        }

        override suspend fun resumeImport(): HistoryImportResult? = null

        override suspend fun recoverPendingImport() = Unit

        override suspend fun skipHistory(steamId: String): Boolean {
            journeyFlow.value = journeyFlow.value?.copy(phase = FirstRunPhase.DEFERRED)
            owedFlow.value = false
            return true
        }

        override suspend fun finishHistory(steamId: String, requestId: String?): Boolean {
            journeyFlow.value = journeyFlow.value?.copy(phase = FirstRunPhase.COMPLETE)
            owedFlow.value = false
            return true
        }

        override suspend fun onImportReset(steamId: String) = Unit

        override suspend fun refreshAccountOwed() = Unit

        override suspend fun startupRecovery() = Unit
    }

    private class FakeCredentials(
        var configured: CredentialsState.Configured?,
    ) : OnboardingCredentialsGateway {
        data class ResolveRequest(val gate: CompletableDeferred<SteamIdResolution>)
        data class VerifyRequest(val gate: CompletableDeferred<CredentialVerification>)

        val resolveRequests = mutableListOf<ResolveRequest>()
        val verifyRequests = mutableListOf<VerifyRequest>()
        val saveCalls = mutableListOf<Pair<String, String>>()

        override suspend fun currentCredentials(): CredentialsState.Configured? = configured

        override suspend fun resolveSteamId(input: String, apiKeyOverride: String?): SteamIdResolution {
            val gate = CompletableDeferred<SteamIdResolution>()
            resolveRequests += ResolveRequest(gate)
            return gate.await()
        }

        override suspend fun verify(apiKey: String, steamId: String): CredentialVerification {
            val gate = CompletableDeferred<CredentialVerification>()
            verifyRequests += VerifyRequest(gate)
            return gate.await()
        }

        override suspend fun save(apiKey: String, steamId: String): CredentialsSaveResult {
            saveCalls += apiKey to steamId
            configured = CredentialsState.Configured(apiKey, steamId)
            return CredentialsSaveResult.Saved
        }

        override suspend fun refresh(): CredentialsState = configured ?: CredentialsState.Unconfigured
    }

    private class FakeBackup : BackupExportGateway {
        override suspend fun exportTo(uri: Uri) = Unit
    }

    private class FakeAccountChange : AccountChangeGateway {
        override suspend fun apply(apiKey: String, steamId: String) = Unit
    }

    private class FakeLegacySetup : FirstRunSetupGateway {
        private val active = MutableStateFlow(false)
        var claimed = false
            private set
        override val firstRunSetupActive: Flow<Boolean> = active

        override suspend fun claimFirstRunSetup() {
            claimed = true
            active.value = true
        }

        override suspend fun releaseFirstRunSetup() {
            active.value = false
        }
    }

    private companion object {
        const val KEY = "key"
        const val INPUT = "https://steamcommunity.com/id/alice"
        const val STEAM_ID = "76561198000000001"
    }
}