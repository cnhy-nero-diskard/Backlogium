package com.example.backlogium.ui.setup

import com.example.backlogium.work.setup.FakeSetupStateStore
import com.example.backlogium.work.setup.FakeStageRunner
import com.example.backlogium.work.setup.FakeStageSource
import com.example.backlogium.work.setup.SetupCoordinator
import com.example.backlogium.work.setup.fakeStage
import kotlinx.coroutines.Dispatchers
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Before
import org.junit.After

@OptIn(ExperimentalCoroutinesApi::class)
class SetupViewModelSelectionTest {
    private var currentModel: SetupViewModel? = null

    @Before fun installMain() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun releaseMain() {
        currentModel?.viewModelScope?.cancel()
        Dispatchers.resetMain()
    }
    @Test
    fun nextRunUsesExactlyTheVisibleSelectionAndStartsEmptyAfterSettlement() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val first = FakeStageRunner()
            val second = FakeStageRunner()
            val source = FakeStageSource(listOf(fakeStage("sync", first), fakeStage("assets", second)))
            val store = FakeSetupStateStore()
            val coordinator = SetupCoordinator(source, store, backgroundScope)
            val model = SetupViewModel(source, coordinator, flowOf(true)).also { currentModel = it }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.uiState.collect() }
            model.prepare(applyDefaults = false)
            runCurrent()

            model.toggle("sync", true)
            runCurrent()
            assertEquals(setOf("sync"), model.uiState.value.selectedIds())
            model.start()
            runCurrent()
            assertTrue(first.started)
            assertFalse(second.started)
            assertTrue(model.uiState.value.finished)
            assertEquals(emptySet<String>(), model.uiState.value.selectedIds())

            model.toggle("assets", true)
            runCurrent()
            val visible = model.uiState.value.selectedIds()
            assertEquals(setOf("assets"), visible)
            model.start()
            runCurrent()
            assertEquals(visible, coordinator.state.value.selected)
            assertTrue(second.started)
        } finally {
            backgroundScope.cancel()
        }
    }

    @Test
    fun foregroundRunLocksSelectionButSettlementAllowsEditingAgain() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val first = FakeStageRunner().apply { autoComplete = false }
            val source = FakeStageSource(listOf(fakeStage("sync", first), fakeStage("assets")))
            val coordinator = SetupCoordinator(source, FakeSetupStateStore(), backgroundScope)
            val model = SetupViewModel(source, coordinator, flowOf(true)).also { currentModel = it }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.uiState.collect() }
            model.prepare(applyDefaults = false)
            runCurrent()
            model.toggle("sync", true)
            model.start()
            runCurrent()
            assertTrue(model.uiState.value.running)

            model.toggle("assets", true)
            runCurrent()
            assertEquals(setOf("sync"), model.uiState.value.selectedIds())
            first.gate.complete(Unit)
            runCurrent()
            model.toggle("assets", true)
            runCurrent()
            assertEquals(setOf("assets"), model.uiState.value.selectedIds())
        } finally {
            backgroundScope.cancel()
        }
    }

    private fun SetupUiState.selectedIds(): Set<String> = stages.filter { it.selected }.map { it.id }.toSet()
}
