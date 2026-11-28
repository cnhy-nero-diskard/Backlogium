package com.example.backlogium.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.backlogium.data.repo.HistoryImportGateway
import com.example.backlogium.data.repo.HistoryImportReadiness
import com.example.backlogium.data.setup.SetupOwner
import com.example.backlogium.work.setup.FirstRunSetupGateway
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HistoryImportUiState(
    val readiness: HistoryImportReadiness = HistoryImportReadiness.NEEDS_SYNC,
    val importing: Boolean = false,
    val error: String? = null,
    val completed: Boolean = false,
    val syncingLibrary: Boolean = false,
)

@HiltViewModel
class HistoryImportViewModel @Inject constructor(
    private val history: HistoryImportGateway,
    private val setup: FirstRunSetupGateway,
) : ViewModel() {
    private val state = MutableStateFlow(HistoryImportUiState())
    val uiState = state.asStateFlow()
    private var owner: SetupOwner? = null

    init {
        viewModelScope.launch {
            owner = setup.firstRunOwner.first()
            history.historyImportReadiness.collect { readiness ->
                state.update { it.copy(readiness = readiness) }
                if (readiness == HistoryImportReadiness.IMPORTED) finish()
            }
        }
        viewModelScope.launch {
            history.historyLibrarySyncing.collect { syncing -> state.update { it.copy(syncingLibrary = syncing) } }
        }
    }

    fun syncLibrary() = history.syncHistoryLibrary()

    fun resumeHistory() = runRequest { history.resumeSteamHistory(owner) }

    fun importHistory() {
        if (state.value.readiness != HistoryImportReadiness.READY) return
        runRequest { history.importSteamHistory(owner) }
    }

    private fun runRequest(action: suspend () -> Unit) {
        if (state.value.importing || state.value.completed) return
        state.update { it.copy(importing = true, error = null) }
        viewModelScope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                state.update { it.copy(error = "History import didn't finish. Try again.") }
            } finally {
                state.update { it.copy(importing = false) }
            }
        }
    }

    fun skip() {
        if (state.value.completed ||
            (state.value.importing && state.value.readiness != HistoryImportReadiness.PENDING)
        ) return
        viewModelScope.launch { finish() }
    }

    private suspend fun finish() {
        setup.releaseFirstRunSetup(owner)
        state.update { it.copy(completed = true) }
    }
}
