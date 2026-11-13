package com.example.backlogium.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.backlogium.data.repo.HistoryImportGateway
import com.example.backlogium.data.repo.HistoryImportReadiness
import com.example.backlogium.work.setup.FirstRunSetupGateway
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HistoryImportUiState(
    val readiness: HistoryImportReadiness = HistoryImportReadiness.NEEDS_SYNC,
    val importing: Boolean = false,
    val error: String? = null,
    val completed: Boolean = false,
)

@HiltViewModel
class HistoryImportViewModel @Inject constructor(
    private val history: HistoryImportGateway,
    private val setup: FirstRunSetupGateway,
) : ViewModel() {
    private val state = MutableStateFlow(HistoryImportUiState())
    val uiState = state.asStateFlow()

    init {
        viewModelScope.launch {
            history.historyImportReadiness.collect { readiness ->
                state.update { it.copy(readiness = readiness) }
                if (readiness == HistoryImportReadiness.IMPORTED) finish()
            }
        }
    }

    fun importHistory() {
        if (state.value.importing || state.value.completed) return
        if (state.value.readiness == HistoryImportReadiness.NEEDS_SYNC) return
        state.update { it.copy(importing = true, error = null) }
        viewModelScope.launch {
            try {
                history.importSteamHistory()
                finish()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                state.update { it.copy(error = error.message ?: "History import didn't finish. Try again.") }
            } finally {
                state.update { it.copy(importing = false) }
            }
        }
    }

    fun skip() {
        if (state.value.importing || state.value.completed) return
        viewModelScope.launch { finish() }
    }

    private suspend fun finish() {
        setup.releaseFirstRunSetup()
        state.update { it.copy(completed = true) }
    }
}
