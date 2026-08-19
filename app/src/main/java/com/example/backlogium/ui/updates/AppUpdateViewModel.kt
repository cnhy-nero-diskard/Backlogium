package com.example.backlogium.ui.updates

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.backlogium.data.updates.AppUpdateManager
import com.example.backlogium.data.updates.AppUpdateRepository
import com.example.backlogium.data.updates.AvailableUpdate
import com.example.backlogium.data.updates.UpdateInstallResult
import com.example.backlogium.data.updates.UpdateInstallStatus
import com.example.backlogium.data.updates.UpdateProgress
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AppUpdateUiState(
    val available: AvailableUpdate? = null,
    val operation: UpdateOperation = UpdateOperation.Idle,
)

sealed interface UpdateOperation {
    data object Idle : UpdateOperation
    data class Downloading(val bytesRead: Long, val totalBytes: Long?) : UpdateOperation
    data object VerifyingDigest : UpdateOperation
    data object VerifyingSigner : UpdateOperation
    data object Installing : UpdateOperation
    data object PermissionRequired : UpdateOperation
    data class Failed(val message: String) : UpdateOperation
}

@HiltViewModel
class AppUpdateViewModel @Inject constructor(
    private val repository: AppUpdateRepository,
    private val manager: AppUpdateManager,
) : ViewModel() {
    private val operation = MutableStateFlow<UpdateOperation>(UpdateOperation.Idle)
    private var updateJob: Job? = null

    val uiState: StateFlow<AppUpdateUiState> = combine(repository.state, operation) { state, current ->
        AppUpdateUiState(
            available = state.available,
            operation = persistedOperation(state.installStatus, state.available, current),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppUpdateUiState(),
    )

    fun startUpdate() {
        val update = uiState.value.available ?: return
        if (updateJob?.isActive == true) return
        updateJob = viewModelScope.launch {
            operation.value = UpdateOperation.Downloading(0L, null)
            try {
                when (val result = manager.downloadAndInstall(update, ::onProgress)) {
                    UpdateInstallResult.Started -> operation.value = UpdateOperation.Installing
                    UpdateInstallResult.PermissionRequired -> {
                        operation.value = UpdateOperation.PermissionRequired
                    }
                    is UpdateInstallResult.Failed -> {
                        operation.value = UpdateOperation.Failed(result.message)
                    }
                }
            } catch (cancellation: CancellationException) {
                operation.value = UpdateOperation.Idle
            } catch (failure: Exception) {
                operation.value = UpdateOperation.Failed(
                    failure.message ?: "The update could not be downloaded.",
                )
            } finally {
                updateJob = null
            }
        }
    }

    fun decline() {
        val tag = uiState.value.available?.tag ?: return
        viewModelScope.launch {
            repository.decline(tag)
            operation.value = UpdateOperation.Idle
        }
    }

    fun cancelDownload() {
        updateJob?.cancel()
    }

    private fun onProgress(progress: UpdateProgress) {
        operation.value = when (progress) {
            is UpdateProgress.Downloading ->
                UpdateOperation.Downloading(progress.bytesRead, progress.totalBytes)
            UpdateProgress.VerifyingDigest -> UpdateOperation.VerifyingDigest
            UpdateProgress.VerifyingSigner -> UpdateOperation.VerifyingSigner
            UpdateProgress.Installing -> UpdateOperation.Installing
        }
    }

    private fun persistedOperation(
        installStatus: UpdateInstallStatus,
        available: AvailableUpdate?,
        current: UpdateOperation,
    ): UpdateOperation {
        val statusTag = when (installStatus) {
            UpdateInstallStatus.Idle -> null
            is UpdateInstallStatus.Started -> installStatus.tag
            is UpdateInstallStatus.AwaitingUserAction -> installStatus.tag
            is UpdateInstallStatus.Failed -> installStatus.tag
        }
        if (statusTag == null || available?.tag != statusTag) return current

        return when (installStatus) {
            UpdateInstallStatus.Idle -> current
            is UpdateInstallStatus.Started,
            is UpdateInstallStatus.AwaitingUserAction,
            -> if (current == UpdateOperation.Idle) UpdateOperation.Installing else current
            is UpdateInstallStatus.Failed -> UpdateOperation.Failed(installStatus.message)
        }
    }
}
