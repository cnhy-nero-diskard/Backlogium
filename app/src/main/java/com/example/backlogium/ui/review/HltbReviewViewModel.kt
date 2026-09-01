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

/**
 * Match-center selection derived from the tracked game identity. [persistedAppId] is what the
 * backing selection state must hold after this derivation: the tracked game while it is present,
 * the clamped replacement (first game, or none for an empty queue) when it is not — so a selection
 * that fell out of the queue cannot become active again when the queue later grows.
 */
internal data class MatchCenterSelection(
    val index: Int,
    val persistedAppId: Long?,
)

/**
 * Derives the selected position from a game identity rather than a raw index: the queue reorders
 * across partitions (`ambiguous` + `unmatched`) whenever a game's match status changes — e.g. a
 * broader search moves the selected game from `unmatched` into `ambiguous` — so an index would
 * silently follow a different game. Tracking by appId keeps the selection on the same game, and
 * clamps to the first game when it is absent (resolved away, or not yet in the queue).
 */
internal fun resolveMatchCenterSelection(selectedAppId: Long?, games: List<MatchCenterGameUi>): MatchCenterSelection {
    if (games.isEmpty()) return MatchCenterSelection(index = 0, persistedAppId = null)
    val trackedIndex = games.indexOfFirst { it.appId == selectedAppId }
    return if (trackedIndex >= 0) {
        MatchCenterSelection(index = trackedIndex, persistedAppId = selectedAppId)
    } else {
        MatchCenterSelection(index = 0, persistedAppId = games.first().appId)
    }
}

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

    // Selection tracks the game's appId, not its index: the queue reorders across the
    // ambiguous/unmatched partitions whenever a match status changes, so an index would
    // silently follow a different game. See [resolveMatchCenterSelection].
    private val selectedAppId = MutableStateFlow<Long?>(null)
    private val broaderStates = MutableStateFlow<Map<Long, BroaderSearchUiState>>(emptyMap())
    private val manualLinkStates = MutableStateFlow<Map<Long, ManualLinkUiState>>(emptyMap())

    private val broaderJobs = mutableMapOf<Long, Job>()
    private val manualLinkJobs = mutableMapOf<Long, Job>()

    val matchCenterState: StateFlow<HltbMatchCenterUiState> = combine(
        hltbRepository.matchCenterQueue,
        gameRepository.library,
        selectedAppId,
        broaderStates,
        manualLinkStates,
    ) { matchCenter, games, selectedId, broader, manual ->
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
        val selection = resolveMatchCenterSelection(selectedId, all)
        // Persist clamping back into the backing selection state so a selection that fell out
        // of the queue (resolved away, or clamped) cannot become active again when the queue
        // later grows. Guarded, so the write-back settles instead of re-triggering combine.
        if (selectedAppId.value != selection.persistedAppId) selectedAppId.value = selection.persistedAppId
        HltbMatchCenterUiState(
            loading = false,
            ambiguous = ambiguous,
            unmatched = unmatched,
            selectedIndex = selection.index,
            broaderStates = broader,
            manualLinkStates = manual,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HltbMatchCenterUiState(),
    )

    fun selectNext() {
        val state = matchCenterState.value
        if (state.total == 0) return
        val target = (state.selectedIndex + 1).coerceAtMost(state.total - 1)
        selectedAppId.value = state.allGames[target].appId
    }

    fun selectPrevious() {
        val state = matchCenterState.value
        if (state.total == 0) return
        val target = (state.selectedIndex - 1).coerceAtLeast(0)
        selectedAppId.value = state.allGames[target].appId
    }

    fun selectIndex(index: Int) {
        val state = matchCenterState.value
        if (state.total == 0) return
        selectedAppId.value = state.allGames[index.coerceIn(0, state.total - 1)].appId
    }

    fun resolve(appId: Long, candidate: HltbCandidate) = viewModelScope.launch {
        hltbRepository.resolveMatch(appId, candidate)
        // Selection is tracked by appId (see matchCenterState): once the resolved game leaves the
        // queue, the combine's clamping persists the replacement selection, so no index surgery
        // is needed here.
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
            // Resolve into the *latest* entry, never the snapshot taken before launch, and drop a
            // result whose submitted input was since edited (clearing loading so the new input
            // can be previewed) — a stale preview must never overwrite newer user input.
            val resolved: (ManualLinkUiState) -> ManualLinkUiState = when (val result = hltbRepository.previewLinkedCandidate(input)) {
                is ManualLinkPreviewResult.Preview -> {
                    { it.copy(loading = false, preview = result.candidate) }
                }
                is ManualLinkPreviewResult.Invalid -> {
                    { it.copy(loading = false, validationError = "Invalid HLTB link: ${result.reason}") }
                }
                is ManualLinkPreviewResult.NotFound -> {
                    { it.copy(loading = false, notFound = true) }
                }
                is ManualLinkPreviewResult.Failed -> {
                    { it.copy(loading = false, failed = true, failureClass = result.failureClass) }
                }
            }
            manualLinkStates.update { map ->
                val current = map[appId] ?: return@update map
                val next = if (current.input.trim() == input) resolved(current) else current.copy(loading = false)
                map + (appId to next)
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
