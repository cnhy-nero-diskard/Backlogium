package com.example.backlogium.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.backlogium.data.hltb.HltbCandidate
import com.example.backlogium.data.repo.BroaderResult
import com.example.backlogium.data.repo.GameRepository
import com.example.backlogium.data.repo.HltbMatchState
import com.example.backlogium.data.repo.HltbMatchRevision
import com.example.backlogium.data.repo.HltbRepository
import com.example.backlogium.data.repo.ManualLinkPreviewResult
import com.example.backlogium.data.hltb.HltbFailureClass
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
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
    val matchStatus: HltbMatchState,
    val candidates: List<HltbCandidate>,
    val revision: HltbMatchRevision = HltbMatchRevision(0L, matchStatus, null),
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
    val revision: HltbMatchRevision? = null,
)

/**
 * Match-center selection state: [index] is the selection's last known position in the display
 * order (`ambiguous + unmatched`) and [persistedAppId] the tracked game identity. Both persist
 * into the session after each derivation. The position starts the search for the next surviving
 * unprocessed game when the tracked identity leaves the queue.
 */
internal data class MatchCenterSelection(
    val index: Int,
    val persistedAppId: Long?,
)

/**
 * Derives the selected position from a game identity rather than a raw index: the queue reorders
 * across partitions (`ambiguous` + `unmatched`) whenever a game's match status changes — e.g. a
 * broader search moves the selected game from `unmatched` into `ambiguous` — so an index would
 * silently follow a different game. Follow the tracked identity while it remains actionable;
 * otherwise prefer an unprocessed game at or after its old position, then one earlier in the
 * current queue. Deferred games never participate in automatic selection.
 */
internal fun resolveMatchCenterSelection(
    prior: MatchCenterSelection,
    games: List<MatchCenterGameUi>,
    deferredAppIds: Set<Long> = emptySet(),
    scopedAppId: Long? = null,
): MatchCenterSelection {
    val eligible = games.indices.filter { index ->
        games[index].appId !in deferredAppIds && (scopedAppId == null || games[index].appId == scopedAppId)
    }
    val tracked = eligible.firstOrNull { games[it].appId == prior.persistedAppId }
    val next = tracked ?: eligible.firstOrNull { it >= prior.index } ?: eligible.firstOrNull()
    return if (next == null) MatchCenterSelection(0, null)
    else MatchCenterSelection(next, games[next].appId)
}

/**
 * A scoped single-game route's completion test: its requested app is absent from the actionable
 * queue. Test against the full repository queue, including deferred games: skipping the scoped
 * game must never finish the route. Loading guards in the session prevent premature completion.
 */
internal fun isScopedAppMissing(
    scopedAppId: Long?,
    games: List<MatchCenterGameUi>,
): Boolean = scopedAppId != null && games.none { it.appId == scopedAppId }

data class HltbMatchCenterUiState(
    val loading: Boolean = true,
    val ambiguous: List<MatchCenterGameUi> = emptyList(),
    val unmatched: List<MatchCenterGameUi> = emptyList(),
    val selectedIndex: Int = 0,
    val broaderStates: Map<Long, BroaderSearchUiState> = emptyMap(),
    val manualLinkStates: Map<Long, ManualLinkUiState> = emptyMap(),
    val deferredAppIds: Set<Long> = emptySet(),
    val scopedAppId: Long? = null,
    /**
     * True when the scoped single-game route (see [HltbReviewViewModel.selectGame]) is complete:
     * its requested app is absent from the actionable queue now that loading has finished. The
     * screen finishes the route. Session deferral is checked separately and never sets this flag.
     */
    val scopedAppMissing: Boolean = false,
) {
    val allGames: List<MatchCenterGameUi> get() = ambiguous + unmatched
    val selectedGame: MatchCenterGameUi? get() = allGames.getOrNull(selectedIndex)
    val activeGames: List<MatchCenterGameUi> get() = allGames.filter {
        it.appId !in deferredAppIds && (scopedAppId == null || it.appId == scopedAppId)
    }
    val deferredCount: Int get() = allGames.count {
        it.appId in deferredAppIds && (scopedAppId == null || it.appId == scopedAppId)
    }
    val total: Int get() = activeGames.size
    val currentPosition: Int get() = activeGames.indexOfFirst { it.appId == selectedGame?.appId } + 1
    val previousGame: MatchCenterGameUi? get() = activeGames.getOrNull(currentPosition - 2)
    val nextGame: MatchCenterGameUi? get() = activeGames.getOrNull(currentPosition)
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

    private val reviewSession = HltbReviewSession()
    private val broaderStates = MutableStateFlow<Map<Long, BroaderSearchUiState>>(emptyMap())
    private val manualLinkStates = MutableStateFlow<Map<Long, ManualLinkUiState>>(emptyMap())

    private val broaderJobs = mutableMapOf<Long, Job>()
    private val manualLinkJobs = mutableMapOf<Long, Job>()

    init {
        // One collector feeds queue changes into the same atomic state as navigation and skips.
        // It lives only as long as this route ViewModel; no session state survives process death.
        combine(
            hltbRepository.matchCenterQueue,
            gameRepository.library,
        ) { matchCenter, games ->
            val infoByAppId = games.associate { it.appId to it }
            matchCenter.map { entry ->
                val game = infoByAppId[entry.appId]
                MatchCenterGameUi(
                    appId = entry.appId,
                    name = game?.name ?: "Unknown game",
                    iconUrl = game?.iconUrl ?: "",
                    headerUrl = game?.headerUrl ?: "",
                    heroCapsuleUrl = game?.heroCapsuleUrl ?: "",
                    matchStatus = entry.matchStatus,
                    candidates = entry.candidates,
                    revision = entry.revision,
                )
            }
        }.onEach(reviewSession::updateQueue).launchIn(viewModelScope)
    }

    val matchCenterState: StateFlow<HltbMatchCenterUiState> = combine(
        reviewSession.state,
        broaderStates,
        manualLinkStates,
    ) { session, broader, manual ->
        session.toUiState().copy(
            broaderStates = broader,
            manualLinkStates = manual,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HltbMatchCenterUiState(),
    )

    fun selectNext(expectedAppId: Long? = null) {
        reviewSession.navigate(1, expectedAppId)
    }

    fun selectPrevious(expectedAppId: Long? = null) {
        reviewSession.navigate(-1, expectedAppId)
    }

    fun selectIndex(index: Int) {
        reviewSession.selectIndex(index)
    }

    fun skip(expectedAppId: Long? = null) = reviewSession.skip(expectedAppId)

    fun reviewSkipped() = reviewSession.reviewSkipped()

    /**
     * Select a game by identity rather than position — used when the caller (e.g. a single-game
     * lookup from the Library) already knows which game needs attention but not its position in
     * the queue. The seeded index of 0 is only a fallback: [resolveMatchCenterSelection] re-derives
     * the real position by [appId] once `matchCenterQueue` includes it. The requested [appId] is
     * also preserved as the route's scoped identity: if that game is absent
     * from the queue once loading has completed — already resolved elsewhere, or gone before the
     * screen observed Room — the route is complete and finishes instead of clamping onto another
     * game, so the user can never be stranded reviewing an unrelated title.
     */
    fun selectGame(appId: Long) {
        reviewSession.selectGame(appId)
    }

    /**
     * Resolves the match and lets the queue drive any navigation: once the persist completes, the
     * game leaves the actionable queue, which is exactly the condition a scoped single-game route
     * finishes on (see [isScopedAppMissing]) — so the active screen pops from state, never from a
     * callback captured in this scope that could outlive the composition that created it.
     */
    fun resolve(appId: Long, candidate: HltbCandidate, expectedRevision: HltbMatchRevision) = viewModelScope.launch {
        // Never advance optimistically: the queue keeps this game when a stale revision is rejected
        // and follows whichever row state Room currently exposes.
        hltbRepository.resolveMatch(appId, candidate, expectedRevision)
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

    fun updateManualLinkInput(appId: Long, input: String, expectedRevision: HltbMatchRevision) {
        manualLinkStates.update { map ->
            val existing = map[appId] ?: ManualLinkUiState()
            map + (appId to existing.copy(
                input = input,
                validationError = null,
                notFound = false,
                failed = false,
                preview = null,
                revision = expectedRevision,
            ))
        }
    }

    fun previewManualLink(appId: Long, expectedRevision: HltbMatchRevision) {
        if (manualLinkJobs[appId]?.isActive == true) return
        val state = manualLinkStates.value[appId]
            ?.takeIf { it.revision == expectedRevision }
            ?: ManualLinkUiState(revision = expectedRevision)
        if (state.loading) return
        val currentState = state.copy(revision = expectedRevision)
        val input = currentState.input.trim()
        if (input.isEmpty()) {
            manualLinkStates.update {
                it + (appId to currentState.copy(validationError = "Enter an HLTB link"))
            }
            return
        }
        manualLinkStates.update {
            it + (appId to currentState.copy(
                loading = true,
                validationError = null,
                notFound = false,
                failed = false,
                preview = null,
                revision = expectedRevision,
            ))
        }
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
                if (current.revision != expectedRevision) return@update map
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

    /** See [resolve] for how completion is signaled: from the queue-driven state, post-persist. */
    fun confirmManualLink(
        appId: Long,
        preview: HltbCandidate,
        expectedRevision: HltbMatchRevision,
    ) = viewModelScope.launch {
        val current = manualLinkStates.value[appId] ?: return@launch
        if (current.preview != preview || current.revision != expectedRevision) return@launch
        val accepted = hltbRepository.resolveMatch(appId, preview, expectedRevision)
        manualLinkStates.update { map ->
            val latest = map[appId] ?: return@update map
            if (latest.preview == preview && latest.revision == expectedRevision) map - appId else map
        }
        if (accepted) {
            // Also clear broader state for that game if present.
            clearBroaderState(appId)
        }
    }
}
