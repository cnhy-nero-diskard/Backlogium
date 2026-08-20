package com.example.backlogium.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.backlogium.data.repo.CredentialsRepository
import com.example.backlogium.data.repo.CredentialsState
import com.example.backlogium.work.setup.SetupCoordinator
import com.example.backlogium.work.setup.SetupStageSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/**
 * Backs both setup surfaces — the onboarding step and the Settings re-run entry — from the one
 * registry and the one coordinator. The only difference between them is [prepare]'s argument, so a
 * newly registered stage reaches both without either being touched.
 */
@HiltViewModel
class SetupViewModel @Inject constructor(
    private val source: SetupStageSource,
    private val coordinator: SetupCoordinator,
    credentials: CredentialsRepository,
) : ViewModel() {

    /** The user's pending checklist selection, before a run makes it the coordinator's business. */
    private val selection = MutableStateFlow<Set<String>>(emptySet())
    private var prepared = false

    /**
     * True on the onboarding surface. Going through that checklist is a decision about every stage,
     * so the unticked ones are recorded skipped; a Settings re-run decides about its own stages only
     * and must leave the rest of the recorded outcomes — the whole point of that list — alone.
     */
    private var isFirstRunFlow = false

    val uiState: StateFlow<SetupUiState> = combine(
        coordinator.state,
        selection,
        credentials.credentialsStateFlow.map { it is CredentialsState.Configured },
    ) { run, pending, configured ->
        SetupUiState(
            loading = !run.loaded,
            stages = setupStagesUi(source.stages, pending, run),
            running = run.running,
            finished = run.finished,
            inScreenSettled = run.inScreenSettled,
            credentialsConfigured = configured,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SetupUiState(),
    )

    /**
     * Seed the checklist. [applyDefaults] is true for the onboarding step, where each stage's
     * declared default applies; false for the Settings re-run, where nothing starts ticked so that
     * a re-run is deliberate and the common case — retrying one failed stage — takes one tap.
     *
     * Idempotent: a recreated surface must not wipe a selection the user has already adjusted, nor
     * re-seed defaults over a run in progress.
     */
    fun prepare(applyDefaults: Boolean) {
        coordinator.ensureLoaded()
        if (prepared) return
        prepared = true
        isFirstRunFlow = applyDefaults
        if (applyDefaults) {
            selection.value = source.stages
                .filter { it.isAvailable && it.defaultOptIn }
                .map { it.id }
                .toSet()
        }
    }

    fun toggle(stageId: String, selected: Boolean) {
        // An unavailable stage is not selectable; a toggle aimed at one is dropped rather than
        // producing a selection that the coordinator would then have to filter out again.
        if (source.stages.none { it.id == stageId && it.isAvailable }) return
        selection.update { current ->
            if (selected) current + stageId else current - stageId
        }
    }

    fun start() = coordinator.start(
        selectedIds = selection.value,
        recordUnselectedAsSkipped = isFirstRunFlow,
    )

    /** Re-run one stage, replacing that stage's recorded outcome and no other's. */
    fun retry(stageId: String) =
        coordinator.start(selectedIds = setOf(stageId), recordUnselectedAsSkipped = false)

    /** Decline setup entirely: nothing runs and every stage is recorded as skipped. */
    fun skip() = coordinator.skipAll()
}
