package com.example.backlogium.ui.onboarding

import com.example.backlogium.data.repo.HistoryImportGateway
import com.example.backlogium.data.repo.HistoryImportReadiness
import com.example.backlogium.work.setup.FirstRunSetupGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryImportViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun readyDoesNotImportWithoutAnExplicitActionAndSkipIsDurable() = runTest(dispatcher) {
        val history = FakeHistory()
        val setup = FakeFirstRun()
        val vm = HistoryImportViewModel(history, setup)
        advanceUntilIdle()
        assertEquals(0, history.calls)
        vm.skip()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.completed)
        assertFalse(setup.firstRunSetupActive.value)
        assertEquals(0, history.calls)
    }

    @Test fun notReadyCanSkipButCannotImportAnEmptyUnconfirmedLibrary() = runTest(dispatcher) {
        val history = FakeHistory(HistoryImportReadiness.NEEDS_SYNC)
        val vm = HistoryImportViewModel(history, FakeFirstRun())
        advanceUntilIdle()
        vm.importHistory()
        advanceUntilIdle()
        assertEquals(0, history.calls)
        vm.skip()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.completed)
    }

    @Test fun failureOffersRetryAndSuccessCompletesTheDecision() = runTest(dispatcher) {
        val history = FakeHistory().also { it.fail = true }
        val vm = HistoryImportViewModel(history, FakeFirstRun())
        advanceUntilIdle()
        vm.importHistory()
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.error)
        assertFalse(vm.uiState.value.completed)
        assertFalse(vm.uiState.value.importing)
        history.fail = false
        vm.importHistory()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.completed)
        assertEquals(2, history.calls)
    }

    @Test fun completedImportOnRestartDismissesWithoutAnotherInvocation() = runTest(dispatcher) {
        val history = FakeHistory(HistoryImportReadiness.IMPORTED)
        val setup = FakeFirstRun()
        val vm = HistoryImportViewModel(history, setup)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.completed)
        assertFalse(setup.firstRunSetupActive.value)
        assertEquals(0, history.calls)
    }

    private class FakeHistory(initial: HistoryImportReadiness = HistoryImportReadiness.READY) : HistoryImportGateway {
        override val historyImportReadiness = MutableStateFlow(initial)
        var calls = 0
        var fail = false
        override suspend fun importSteamHistory(): Boolean {
            calls++
            if (fail) error("Fixture import failed")
            historyImportReadiness.value = HistoryImportReadiness.IMPORTED
            return true
        }
    }

    private class FakeFirstRun : FirstRunSetupGateway {
        override val firstRunSetupActive = MutableStateFlow(true)
        override suspend fun claimFirstRunSetup() { firstRunSetupActive.value = true }
        override suspend fun releaseFirstRunSetup() { firstRunSetupActive.value = false }
    }
}
