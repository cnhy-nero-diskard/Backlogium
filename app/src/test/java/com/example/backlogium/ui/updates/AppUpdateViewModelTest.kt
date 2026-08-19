package com.example.backlogium.ui.updates

import com.example.backlogium.data.updates.AppUpdateManager
import com.example.backlogium.data.updates.AppUpdateRepository
import com.example.backlogium.data.updates.AppUpdateState
import com.example.backlogium.data.updates.AvailableUpdate
import com.example.backlogium.data.updates.UpdateCheckResult
import com.example.backlogium.data.updates.UpdateInstallResult
import com.example.backlogium.data.updates.UpdateInstallStatus
import com.example.backlogium.data.updates.UpdateProgress
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith
import org.junit.Test
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppUpdateViewModelTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun persistedPackageInstallerFailureLeavesUpdateAvailableForRetry() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val update = update()
            val state = MutableStateFlow(AppUpdateState(available = update))
            val repository = FakeRepository(state)
            val manager = RecordingManager(state)
            val viewModel = AppUpdateViewModel(repository, manager)
            val collector = backgroundScope.launch { viewModel.uiState.collect() }
            advanceUntilIdle()

            viewModel.startUpdate()
            advanceUntilIdle()
            assertEquals(UpdateOperation.Installing, viewModel.uiState.value.operation)

            state.value = state.value.copy(
                installStatus = UpdateInstallStatus.Failed(update.tag, "Installation canceled."),
            )
            advanceUntilIdle()
            assertEquals(
                UpdateOperation.Failed("Installation canceled."),
                viewModel.uiState.value.operation,
            )
            assertEquals(update, viewModel.uiState.value.available)

            viewModel.startUpdate()
            advanceUntilIdle()
            assertEquals(2, manager.calls)
            assertEquals(UpdateOperation.Installing, viewModel.uiState.value.operation)

            collector.cancel()
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun update() = AvailableUpdate(
        tag = "v1.8.0",
        versionName = "1.8.0",
        versionCode = 1_008_000L,
        releaseName = "Release",
        releaseNotes = "Notes",
        apkName = "app-release.apk",
        apkUrl = "https://example.test/app.apk",
        checksumUrl = "https://example.test/app.sha256",
    )

    private class FakeRepository(
        private val stateFlow: MutableStateFlow<AppUpdateState>,
    ) : AppUpdateRepository {
        override val state: Flow<AppUpdateState> = stateFlow

        override suspend fun check(force: Boolean): UpdateCheckResult =
            UpdateCheckResult.SkippedRecent(stateFlow.value.available)

        override suspend fun decline(tag: String) {
            stateFlow.value = stateFlow.value.copy(installStatus = UpdateInstallStatus.Idle)
        }
    }

    private class RecordingManager(
        private val stateFlow: MutableStateFlow<AppUpdateState>,
    ) : AppUpdateManager {
        var calls = 0

        override suspend fun downloadAndInstall(
            update: AvailableUpdate,
            onProgress: (UpdateProgress) -> Unit,
        ): UpdateInstallResult {
            calls++
            stateFlow.value = stateFlow.value.copy(
                installStatus = UpdateInstallStatus.Started(update.tag),
            )
            return UpdateInstallResult.Started
        }
    }
}
