package com.example.backlogium.ui.onboarding

import android.net.Uri
import com.example.backlogium.data.backup.BackupExportGateway
import com.example.backlogium.data.repo.AccountChangeGateway
import com.example.backlogium.data.repo.CredentialVerification
import com.example.backlogium.data.repo.CredentialsSaveResult
import com.example.backlogium.data.repo.CredentialsState
import com.example.backlogium.data.repo.OnboardingCredentialsGateway
import com.example.backlogium.data.repo.SteamIdResolution
import com.example.backlogium.work.setup.FirstRunSetupGateway
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Request-identity coverage for the onboarding credential flow, exercised through the ViewModel
 * wiring rather than the guard predicate alone.
 *
 * Each resolution and verification suspends on a test-controlled gate awaited in [NonCancellable],
 * so an abandoned request keeps running past its cancellation — the shape the
 * `runCatching`-absorbed `CancellationException` used to produce — and the test proves the
 * generation guard discards it anyway. Cancellation stays an optimization; the guard is what the
 * requirement rests on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingAsyncIdentityTest {

    @Test
    fun generationIdentifiesTheLiveRequest() {
        assertTrue(isCurrentCredentialRequest(4L, 4L))
        assertFalse(isCurrentCredentialRequest(4L, 1L))
    }

    @Test
    fun anAbandonedResolutionCannotPublishOverItsReplacement() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val credentials = FakeCredentials()
            val viewModel = OnboardingViewModel(
                credentials,
                FakeBackup(),
                FakeAccountChange(),
                FakeSetup(),
            )
            advanceUntilIdle()

            viewModel.onApiKeyChange(KEY)
            viewModel.onSteamIdInputChange(INPUT_A)
            viewModel.resolveSteamId()
            runCurrent()
            assertEquals(1, credentials.resolveRequests.size)
            assertTrue(viewModel.uiState.value.resolve is ResolveState.Resolving)

            // Edit to B, cancelling A, then back to A with a replacement request.
            viewModel.onSteamIdInputChange(INPUT_B)
            runCurrent()
            viewModel.onSteamIdInputChange(INPUT_A)
            viewModel.resolveSteamId()
            runCurrent()
            assertEquals(2, credentials.resolveRequests.size)

            // Release the abandoned first request with a success. The screen shows A again, so a
            // value comparison would match — the generation must still discard it.
            credentials.resolveRequests[0].gate.complete(SteamIdResolution.Resolved(STEAM_ID_A))
            runCurrent()
            assertTrue(
                "an abandoned resolution must not publish over its replacement",
                viewModel.uiState.value.resolve is ResolveState.Resolving,
            )
            assertTrue(
                "an abandoned resolution must never reach save",
                credentials.saveCalls.isEmpty(),
            )

            // The replacement still publishes normally.
            credentials.resolveRequests[1].gate.complete(SteamIdResolution.Resolved(STEAM_ID_A))
            runCurrent()
            assertEquals(
                ResolveState.Resolved(STEAM_ID_A),
                viewModel.uiState.value.resolve,
            )
            assertTrue(credentials.saveCalls.isEmpty())
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun editingDuringVerificationPreventsThatVerificationFromPersisting() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val credentials = FakeCredentials()
            val viewModel = OnboardingViewModel(
                credentials,
                FakeBackup(),
                FakeAccountChange(),
                FakeSetup(),
            )
            advanceUntilIdle()

            viewModel.onApiKeyChange(KEY)
            viewModel.onSteamIdInputChange(INPUT_A)
            viewModel.resolveSteamId()
            runCurrent()
            credentials.resolveRequests[0].gate.complete(SteamIdResolution.Resolved(STEAM_ID_A))
            runCurrent()
            assertTrue(viewModel.uiState.value.isResolved)

            viewModel.finish()
            runCurrent()
            assertEquals(1, credentials.verifyRequests.size)
            assertTrue(viewModel.uiState.value.verify is VerifyState.Verifying)

            // The controls stay live during verification by design: an edit invalidates it.
            viewModel.onSteamIdInputChange(INPUT_B)
            runCurrent()

            credentials.verifyRequests[0].gate.complete(CredentialVerification.Verified)
            runCurrent()
            assertTrue(
                "a superseded verification must never reach save",
                credentials.saveCalls.isEmpty(),
            )
            assertTrue(
                "a superseded verification must not overwrite the edited state",
                viewModel.uiState.value.verify is VerifyState.Idle,
            )
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun theDisplayedResolutionPublishesAndVerifiedCredentialsPersist() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val credentials = FakeCredentials()
            val viewModel = OnboardingViewModel(
                credentials,
                FakeBackup(),
                FakeAccountChange(),
                FakeSetup(),
            )
            advanceUntilIdle()

            viewModel.onApiKeyChange(KEY)
            viewModel.onSteamIdInputChange(INPUT_A)
            viewModel.resolveSteamId()
            runCurrent()
            credentials.resolveRequests[0].gate.complete(SteamIdResolution.Resolved(STEAM_ID_A))
            runCurrent()
            assertEquals(
                ResolveState.Resolved(STEAM_ID_A),
                viewModel.uiState.value.resolve,
            )

            viewModel.finish()
            runCurrent()
            credentials.verifyRequests[0].gate.complete(CredentialVerification.Verified)
            runCurrent()
            assertEquals(listOf(KEY to STEAM_ID_A), credentials.saveCalls)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun retryAfterUnreachablePersistsWithoutReentry() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val credentials = FakeCredentials()
            val viewModel = OnboardingViewModel(
                credentials,
                FakeBackup(),
                FakeAccountChange(),
                FakeSetup(),
            )
            advanceUntilIdle()

            viewModel.onApiKeyChange(KEY)
            viewModel.onSteamIdInputChange(INPUT_A)
            viewModel.resolveSteamId()
            runCurrent()
            credentials.resolveRequests[0].gate.complete(SteamIdResolution.Resolved(STEAM_ID_A))
            runCurrent()

            viewModel.finish()
            runCurrent()
            credentials.verifyRequests[0].gate.complete(CredentialVerification.Unreachable)
            runCurrent()
            assertTrue(viewModel.uiState.value.verify is VerifyState.Unreachable)
            assertTrue(credentials.saveCalls.isEmpty())

            viewModel.retryVerification()
            runCurrent()
            assertEquals(2, credentials.verifyRequests.size)
            credentials.verifyRequests[1].gate.complete(CredentialVerification.Verified)
            runCurrent()
            assertEquals(listOf(KEY to STEAM_ID_A), credentials.saveCalls)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private class FakeCredentials(
        var configured: CredentialsState.Configured? = null,
    ) : OnboardingCredentialsGateway {
        data class ResolveRequest(
            val input: String,
            val apiKeyOverride: String?,
            val gate: CompletableDeferred<SteamIdResolution>,
        )

        data class VerifyRequest(
            val apiKey: String,
            val steamId: String,
            val gate: CompletableDeferred<CredentialVerification>,
        )

        val resolveRequests = mutableListOf<ResolveRequest>()
        val verifyRequests = mutableListOf<VerifyRequest>()
        val saveCalls = mutableListOf<Pair<String, String>>()

        override suspend fun currentCredentials(): CredentialsState.Configured? = configured

        override suspend fun resolveSteamId(
            input: String,
            apiKeyOverride: String?,
        ): SteamIdResolution {
            val gate = CompletableDeferred<SteamIdResolution>()
            resolveRequests += ResolveRequest(input, apiKeyOverride, gate)
            // Non-cancellable on purpose: the guard must discard a superseded request even when
            // its cancellation never stops it.
            return withContext(NonCancellable) { gate.await() }
        }

        override suspend fun verify(apiKey: String, steamId: String): CredentialVerification {
            val gate = CompletableDeferred<CredentialVerification>()
            verifyRequests += VerifyRequest(apiKey, steamId, gate)
            return withContext(NonCancellable) { gate.await() }
        }

        override suspend fun save(apiKey: String, steamId: String): CredentialsSaveResult {
            saveCalls += apiKey to steamId
            return CredentialsSaveResult.Saved
        }

        override suspend fun refresh(): CredentialsState =
            configured ?: CredentialsState.Unconfigured
    }

    @Test
    fun coldLaunchRestoresHistoryDecisionButCredentialEditingDoesNotEnroll() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val configured = FakeCredentials(CredentialsState.Configured(KEY, STEAM_ID_A))
            val firstRun = FakeSetup(com.example.backlogium.work.setup.FirstRunPhase.HISTORY)
            val restored = OnboardingViewModel(configured, FakeBackup(), FakeAccountChange(), firstRun)
            advanceUntilIdle()
            assertEquals(OnboardingStep.HISTORY, restored.uiState.value.step)
            val editing = OnboardingViewModel(configured, FakeBackup(), FakeAccountChange(), FakeSetup())
            advanceUntilIdle()
            assertEquals(OnboardingStep.API_KEY, editing.uiState.value.step)
        } finally { Dispatchers.resetMain() }
    }

    @Test
    fun anySetupExitAdvancesToHistoryWithoutCompletingTheFlow() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val setup = FakeSetup(com.example.backlogium.work.setup.FirstRunPhase.SETUP)
            val vm = OnboardingViewModel(FakeCredentials(CredentialsState.Configured(KEY, STEAM_ID_A)),
                FakeBackup(), FakeAccountChange(), setup)
            advanceUntilIdle()
            vm.onSetupDone()
            advanceUntilIdle()
            assertEquals(OnboardingStep.HISTORY, vm.uiState.value.step)
            assertFalse(vm.uiState.value.completed)
            assertTrue(setup.firstRunSetupActive.first())
        } finally { Dispatchers.resetMain() }
    }

    private class FakeBackup : BackupExportGateway {
        override suspend fun exportTo(uri: Uri) = Unit
    }

    private class FakeAccountChange : AccountChangeGateway {
        override suspend fun apply(apiKey: String, steamId: String) = Unit
    }

    private class FakeSetup(initial: com.example.backlogium.work.setup.FirstRunPhase? = null) : FirstRunSetupGateway {
        private val active = MutableStateFlow(initial != null)
        override val firstRunSetupActive: Flow<Boolean> = active
        override val firstRunPhase = MutableStateFlow(initial)

        override suspend fun advanceToHistory() {
            firstRunPhase.value = com.example.backlogium.work.setup.FirstRunPhase.HISTORY
        }

        override suspend fun claimFirstRunSetup() {
            active.value = true
            firstRunPhase.value = com.example.backlogium.work.setup.FirstRunPhase.SETUP
        }

        override suspend fun releaseFirstRunSetup() {
            active.value = false
            firstRunPhase.value = null
        }
    }

    private companion object {
        const val KEY = "key"
        const val INPUT_A = "https://steamcommunity.com/id/alice"
        const val INPUT_B = "https://steamcommunity.com/id/bob"
        const val STEAM_ID_A = "76561198000000001"
    }
}
