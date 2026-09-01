package com.example.backlogium.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.backlogium.data.hltb.HltbCandidate
import com.example.backlogium.data.local.entity.HltbMatchStatus
import com.example.backlogium.data.repo.BroaderResult
import com.example.backlogium.data.repo.GameRepository
import com.example.backlogium.data.repo.HltbRepository
import com.example.backlogium.data.repo.ManualLinkPreviewResult
import com.example.backlogium.data.hltb.HltbFailureClass
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ReviewGameUi(
    val appId: Long,
    val name: String,
    val candidates: List<HltbCandidate>,
)

data class HltbReviewUiState(
    val loading: Boolean = true,
    val games: List<ReviewGameUi> = emptyList(),
)

data class MatchCenterGameUi(
    val appId: Long,
    val name: String,
    val iconUrl: String = "",
    val headerUrl: String = "",
    val heroCapsuleUrl: String = "",
    val matchStatus: HltbMatchStatus,
    val candidates: List<HltbCandidate>,
)

// Per-game broader-search transient state
data class BroaderSearchUiState(
    val loading: Boolean = false,
    val failed: Boolean = false,
    val exhausted: Boolean = false,
    val failureClass: HltbFailureClass? = null,
)

// Per-game manual-link transient state
data class ManualLinkUiState(
    val input: String = "",
    val validationError: String? = null,
    val loading: Boolean = false,
    val preview: HltbCandidate? = null,
    val notFound: Boolean = false,
    val failed: Boolean = false,
    val failureClass: HltbFailureClass? = null,
)

data class HltbMatchCenterUiState(
    val loading: Boolean = true,
    val ambiguous: List<MatchCenterGameUi> = emptyList(),
    val unmatched: List<MatchCenterGameUi> = emptyList(),
    val selectedIndex: Int = 0,
    val broaderStates: Map<Long, BroaderSearchUiState> = emptyMap(),
    val manualLinkStates: Map<Long, ManualLinkUiState> = emptyMap(),
) {
    val allGames: List<MatchCenterGameUi> get() = ambiguous + unmatched
    val selectedGame: MatchCenterGameUi? get() = allGames.getOrNull(selectedIndex)
    val total: Int get() = allGames.size
    val currentPosition: Int get() = if (total == 0) 0 else selectedIndex + 1
}

/**
 * Drives the match-center surface: lists games flagged `NEEDS_REVIEW` with their retained
 * candidates (joined with the library for display names) plus UNMATCHED games for rescue.
 * Selecting a candidate resolves the match and removes the game from the list.
 * Also owns broader-search and manual-link preview transient states per game, guarding
 * duplicate operations.
 */
@HiltViewModel
class HltbReviewViewModel @Inject constructor(
    private val hltbRepository: HltbRepository,
    private val gameRepository: GameRepository,
) : ViewModel() {

    // Legacy review-only state (kept for compatibility; new UI reads matchCenterState)
    val uiState: StateFlow<HltbReviewUiState> = combine(
        hltbRepository.reviewQueue,
        gameRepository.library,
    ) { review, games ->
        val namesByAppId = games.associate { it.appId to it.name }
        HltbReviewUiState(
            loading = false,
            games = review.map { flagged ->
                ReviewGameUi(
                    appId = flagged.appId,
                    name = namesByAppId[flagged.appId] ?: "Unknown game",
                    candidates = flagged.candidates,
                )
            },
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HltbReviewUiState(),
    )

    private val selectedIndex = MutableStateFlow(0)
    private val broaderStates = MutableStateFlow<Map<Long, BroaderSearchUiState>>(emptyMap())
    private val manualLinkStates = MutableStateFlow<Map<Long, ManualLinkUiState>>(emptyMap())

    private val broaderJobs = mutableMapOf<Long, Job>()
    private val manualLinkJobs = mutableMapOf<Long, Job>()

    val matchCenterState: StateFlow<HltbMatchCenterUiState> = combine(
        hltbRepository.matchCenterQueue,
        gameRepository.library,
        selectedIndex,
        broaderStates,
        manualLinkStates,
    ) { matchCenter, games, idx, broader, manual ->
        val infoByAppId = games.associate { it.appId to it }
        val all = matchCenter.map { entry ->
            val game = infoByAppId[entry.appId]
            MatchCenterGameUi(
                appId = entry.appId,
                name = game?.name ?: "Unknown game",
                iconUrl = game?.iconUrl ?: "",
                headerUrl = game?.headerUrl ?: "",
                heroCapsuleUrl = game?.heroCapsuleUrl ?: "",
                matchStatus = entry.matchStatus,
                candidates = entry.candidates,
            )
        }
        val ambiguous = all.filter { it.matchStatus == HltbMatchStatus.NEEDS_REVIEW }
        val unmatched = all.filter { it.matchStatus == HltbMatchStatus.UNMATCHED }
        val total = all.size
        val clampedIndex = when {
            total == 0 -> 0
            idx >= total -> (total - 1).coerceAtLeast(0)
            else -> idx
        }
        HltbMatchCenterUiState(
            loading = false,
            ambiguous = ambiguous,
            unmatched = unmatched,
            selectedIndex = clampedIndex,
            broaderStates = broader,
            manualLinkStates = manual,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HltbMatchCenterUiState(),
    )

    fun selectNext() {
        val total = matchCenterState.value.total
        if (total == 0) return
        selectedIndex.update { current ->
            val clamped = current.coerceIn(0, total - 1)
            if (clamped + 1 < total) clamped + 1 else clamped
        }
    }

    fun selectPrevious() {
        val total = matchCenterState.value.total
        if (total == 0) return
        selectedIndex.update { current ->
            val clamped = current.coerceIn(0, total - 1)
            if (clamped > 0) clamped - 1 else clamped
        }
    }

    fun selectIndex(index: Int) {
        val total = matchCenterState.value.total
        if (total == 0) return
        selectedIndex.value = index.coerceIn(0, total - 1)
    }

    fun resolve(appId: Long, candidate: HltbCandidate) = viewModelScope.launch {
        hltbRepository.resolveMatch(appId, candidate)
        // Selection stability: if the resolved game was selected, keep index at same position
        // which now points to next game; if it was last, clamp to previous.
        // The combine's clamping handles it; no extra adjust needed beyond ensuring the flow
        // re-evaluates. But we proactively adjust if the selected game was the one resolved
        // and it was the last item.
    }

    fun startBroaderSearch(appId: Long, originalName: String) {
        if (broaderJobs[appId]?.isActive == true) return
        val currentState = broaderStates.value[appId]
        if (currentState?.loading == true) return
        broaderStates.update { it + (appId to BroaderSearchUiState(loading = true)) }
        val job = viewModelScope.launch {
            val result = hltbRepository.searchBroaderCandidates(appId, originalName)
            broaderStates.update { map ->
                val next = when (result) {
                    is BroaderResult.Success -> BroaderSearchUiState(loading = false)
                    is BroaderResult.Exhausted -> BroaderSearchUiState(loading = false, exhausted = true)
                    is BroaderResult.Failed -> BroaderSearchUiState(loading = false, failed = true, failureClass = result.failureClass)
                    is BroaderResult.NotEligible -> BroaderSearchUiState(loading = false, failed = true)
                }
                map + (appId to next)
            }
        }
        broaderJobs[appId] = job
        job.invokeOnCompletion { if (broaderJobs[appId] === job) broaderJobs.remove(appId) }
    }

    fun clearBroaderState(appId: Long) {
        broaderJobs[appId]?.cancel()
        broaderJobs.remove(appId)
        broaderStates.update { it - appId }
    }

    fun updateManualLinkInput(appId: Long, input: String) {
        manualLinkStates.update { map ->
            val existing = map[appId] ?: ManualLinkUiState()
            map + (appId to existing.copy(input = input, validationError = null, notFound = false, failed = false, preview = null))
        }
    }

    fun previewManualLink(appId: Long) {
        if (manualLinkJobs[appId]?.isActive == true) return
        val state = manualLinkStates.value[appId] ?: ManualLinkUiState()
        if (state.loading) return
        val input = state.input.trim()
        if (input.isEmpty()) {
            manualLinkStates.update { it + (appId to state.copy(validationError = "Enter an HLTB link")) }
            return
        }
        manualLinkStates.update { it + (appId to state.copy(loading = true, validationError = null, notFound = false, failed = false, preview = null)) }
        val job = viewModelScope.launch {
            when (val result = hltbRepository.previewLinkedCandidate(input)) {
                is ManualLinkPreviewResult.Preview -> {
                    manualLinkStates.update { it + (appId to state.copy(loading = false, preview = result.candidate)) }
                }
                is ManualLinkPreviewResult.Invalid -> {
                    manualLinkStates.update { it + (appId to state.copy(loading = false, validationError = "Invalid HLTB link: ${result.reason}")) }
                }
                is ManualLinkPreviewResult.NotFound -> {
                    manualLinkStates.update { it + (appId to state.copy(loading = false, notFound = true)) }
                }
                is ManualLinkPreviewResult.Failed -> {
                    manualLinkStates.update { it + (appId to state.copy(loading = false, failed = true, failureClass = result.failureClass)) }
                }
            }
        }
        manualLinkJobs[appId] = job
        job.invokeOnCompletion { if (manualLinkJobs[appId] === job) manualLinkJobs.remove(appId) }
    }

    fun dismissManualLinkPreview(appId: Long) {
        manualLinkJobs[appId]?.cancel()
        manualLinkJobs.remove(appId)
        manualLinkStates.update { map ->
            val existing = map[appId] ?: return@update map
            map + (appId to existing.copy(preview = null, loading = false, notFound = false, failed = false, validationError = null))
        }
    }

    fun clearManualLink(appId: Long) {
        manualLinkJobs[appId]?.cancel()
        manualLinkJobs.remove(appId)
        manualLinkStates.update { it - appId }
    }

    fun confirmManualLink(appId: Long) = viewModelScope.launch {
        val preview = manualLinkStates.value[appId]?.preview ?: return@launch
        hltbRepository.resolveMatch(appId, preview)
        clearManualLink(appId)
        // Also clear broader state for that game if present
        clearBroaderState(appId)
    }
}
