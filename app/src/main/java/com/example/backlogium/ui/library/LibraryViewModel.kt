package com.example.backlogium.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.backlogium.data.local.dao.AchievementCounts
import com.example.backlogium.data.hltb.HltbCandidate
import com.example.backlogium.data.repo.AchievementRepository
import com.example.backlogium.data.repo.CredentialsRepository
import com.example.backlogium.data.repo.CredentialsState
import com.example.backlogium.data.repo.GameRepository
import com.example.backlogium.data.repo.GameGenre
import com.example.backlogium.data.repo.HltbMatchState
import com.example.backlogium.data.repo.HltbRepository
import com.example.backlogium.data.repo.HltbRefreshOutcome
import com.example.backlogium.data.repo.LibraryGame
import com.example.backlogium.domain.GameSource
import com.example.backlogium.data.repo.LiveStatusRepository
import com.example.backlogium.data.repo.NowPlaying
import com.example.backlogium.data.repo.SessionRepository
import com.example.backlogium.data.repo.SettingsRepository
import com.example.backlogium.data.repo.toDomain
import com.example.backlogium.domain.GameXpInput
import com.example.backlogium.domain.GameListDensity
import com.example.backlogium.domain.GameRecencyState
import com.example.backlogium.domain.LibrarySortKey
import com.example.backlogium.domain.LibrarySortDirection
import com.example.backlogium.domain.LibrarySortPrefs
import com.example.backlogium.domain.LibraryXp
import com.example.backlogium.gamification.RuleConfig
import com.example.backlogium.ui.search.gameSearchMatchTier
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

/** Transient (non-persisted) state of an in-flight or just-finished per-game HLTB lookup. */
enum class HltbFetchOp { IN_PROGRESS, FAILED }

/** Transient state for the inline candidate picker; never persisted with the library row. */
data class HltbPickerUiState(
    val candidates: List<HltbCandidate> = emptyList(),
    val loading: Boolean = false,
    val failed: Boolean = false,
)

/** Transient manual-link state for the inline picker footer. */
data class PickerManualLinkUiState(
    val input: String = "",
    val validationError: String? = null,
    val loading: Boolean = false,
    val preview: HltbCandidate? = null,
    val notFound: Boolean = false,
    val failed: Boolean = false,
    val failureClass: com.example.backlogium.data.hltb.HltbFailureClass? = null,
)

data class GoalGameUi(
    override val appId: Long,
    override val name: String,
    val iconUrl: String,
    /** Store header art, drawn as a faint backdrop behind the row. */
    val headerUrl: String = "",
    /** Steam's portrait hero capsule, used by grid cells. */
    val heroCapsuleUrl: String = "",
    override val playtimeForever: Int,
    /** Steam's rolling two-week playtime — the "recently played" sort key, not displayed. */
    override val playtime2Weeks: Int = 0,
    /** XP this game has contributed to the player's total. Zero is a real value, not "unknown". */
    override val xpContributed: Int = 0,
    /** HowLongToBeat Completionist length, if resolved. Null → no completion-based progress. */
    val completionistMinutes: Int? = null,
    /** Persisted match status, or NOT_COVERED when no lookup/dataset row has been stored. */
    val hltbStatus: HltbMatchState = HltbMatchState.NOT_COVERED,
    /** In-flight/failed state of a manual lookup, layered over [hltbStatus]. */
    val fetchOp: HltbFetchOp? = null,
    /** Unlocked/total achievement counts, null when no achievement data is stored yet. */
    val achievementUnlocked: Int? = null,
    val achievementTotal: Int? = null,
    /** True while Steam's live presence reports this exact game as the one running right now. */
    val isCurrentlyPlaying: Boolean = false,
    override val genres: List<GameGenre> = emptyList(),
    /** The one recency signal this game carries, already derived; null when it carries none. */
    val recencyState: GameRecencyState? = null,
    /**
     * Played through Family Sharing rather than owned. Rendered as a short text label on the row,
     * never as colour alone; false for an owned game, which carries no marking at all.
     */
    val isFamilyShared: Boolean = false,
) : LibraryRow

data class BacklogGameUi(
    override val appId: Long,
    override val name: String,
    val iconUrl: String,
    val headerUrl: String = "",
    val heroCapsuleUrl: String = "",
    override val playtimeForever: Int,
    override val playtime2Weeks: Int = 0,
    override val xpContributed: Int = 0,
    /**
     * HowLongToBeat Completionist length, if resolved. Present here too: the batch refresh fetches
     * a length for every owned game, so withholding completion progress from untagged rows was a
     * leftover from when only tagged games had a target at all.
     */
    val completionistMinutes: Int? = null,
    val hltbStatus: HltbMatchState = HltbMatchState.NOT_COVERED,
    val fetchOp: HltbFetchOp? = null,
    val achievementUnlocked: Int? = null,
    val achievementTotal: Int? = null,
    /** True while Steam's live presence reports this exact game as the one running right now. */
    val isCurrentlyPlaying: Boolean = false,
    override val genres: List<GameGenre> = emptyList(),
    /** The one recency signal this game carries, already derived; null when it carries none. */
    val recencyState: GameRecencyState? = null,
    /**
     * Played through Family Sharing rather than owned. Rendered as a short text label on the row,
     * never as colour alone; false for an owned game, which carries no marking at all.
     */
    val isFamilyShared: Boolean = false,
) : LibraryRow

/** One processed game in a running selection lookup, including structured failure evidence. */
data class HltbLogEntry(val gameName: String, val outcome: HltbRefreshOutcome)

/** One progress snapshot of a running explicit-selection HowLongToBeat lookup. */
data class HltbSelectionProgress(
    val done: Int,
    val total: Int,
    val gameName: String,
    val outcome: HltbRefreshOutcome,
)

data class LibraryUiState(
    val loading: Boolean = true,
    val configured: Boolean = true,
    /** The tracked ("Focus") section, already filtered and sorted for display. */
    val goalGames: List<GoalGameUi> = emptyList(),
    /** The rest of the library ("Your games"), already filtered and sorted for display. */
    val backlog: List<BacklogGameUi> = emptyList(),
    val reviewCount: Int = 0,
    val hltbCandidatesByAppId: Map<Long, List<HltbCandidate>> = emptyMap(),
    val pickerStates: Map<Long, HltbPickerUiState> = emptyMap(),
    val pickerManualLinkStates: Map<Long, PickerManualLinkUiState> = emptyMap(),
    /**
     * One-shot: the appId whose just-finished lookup needs the match center. The active screen
     * navigates on this and clears it via [LibraryViewModel.consumeNeedsAttention] — never a
     * captured callback, which could outlive the composition that started the lookup.
     */
    val needsAttentionAppId: Long? = null,
    val refreshing: Boolean = false,
    val query: String = "",
    val focusSort: LibrarySortKey = LibrarySortKey.NAME,
    val librarySort: LibrarySortKey = LibrarySortKey.PLAYTIME,
    val focusSortDirection: LibrarySortDirection = focusSort.defaultDirection,
    val librarySortDirection: LibrarySortDirection = librarySort.defaultDirection,
    val density: GameListDensity = GameListDensity.LIST,
    /** All known genres, kept unfiltered so the transient Library catalog remains usable while searching. */
    val availableGenres: List<GameGenre> = emptyList(),
    /**
     * Selected appIds, kept independent of the active filter so hiding a selected game does not
     * silently drop it from the pending refresh.
     */
    val selection: Set<Long> = emptySet(),
    /**
     * Whether the library is empty *before* filtering. The full-screen "No games yet" state keys
     * off this, never off the filtered lists: a query matching nothing must not unmount the search
     * field that produced it.
     */
    val libraryEmpty: Boolean = true,
    val batchProgress: HltbSelectionProgress? = null,
    val batchLog: List<HltbLogEntry> = emptyList(),
) {
    val selectionMode: Boolean get() = selection.isNotEmpty()

    /** A filter is active and matched nothing — an in-list empty state, not a blank screen. */
    val noMatches: Boolean
        get() = query.isNotBlank() && goalGames.isEmpty() && backlog.isEmpty()
}

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val gameRepository: GameRepository,
    private val hltbRepository: HltbRepository,
    private val achievementRepository: AchievementRepository,
    private val sessionRepository: SessionRepository,
    private val settings: SettingsRepository,
    private val credentials: CredentialsRepository,
    private val liveStatusRepository: LiveStatusRepository,
) : ViewModel() {

    /** Per-game manual-lookup state, keyed by appId. Not persisted — cleared on success. */
    private val fetchOps = MutableStateFlow<Map<Long, HltbFetchOp>>(emptyMap())
    private val pickerStates = MutableStateFlow<Map<Long, HltbPickerUiState>>(emptyMap())
    private val pickerJobs = mutableMapOf<Long, Job>()
    private val pickerManualLinkStates = MutableStateFlow<Map<Long, PickerManualLinkUiState>>(emptyMap())
    private val pickerManualLinkJobs = mutableMapOf<Long, Job>()

    /**
     * One-shot signal that a finished per-game lookup needs the match center (see [refreshGame]).
     * The ViewModel holds the appId as state rather than invoking a captured navigation lambda:
     * the lookup job lives in `viewModelScope` and can outlive the composition that started it
     * (dialog dismissed, tab changed, configuration change), so the active screen consumes this
     * under lifecycle collection and performs the navigation itself. Cleared by [consumeNeedsAttention].
     */
    private val needsAttention = MutableStateFlow<Long?>(null)

    /** Name filter. Applied in memory: the library is already loaded, so no query per keystroke. */
    private val query = MutableStateFlow("")

    /** Transient multi-select for the targeted refresh. Never persisted (see [clearSelection]). */
    private val selection = MutableStateFlow<Set<Long>>(emptySet())

    /**
     * The running explicit-selection lookup, owned by this ViewModel rather than WorkManager: it
     * runs only while this screen is watching it, and leaving the screen cancels it — there is no
     * detached background HLTB work any more. Every game already processed keeps the data it
     * received, since each result is written to Room as it is looked up.
     */
    private val selectionLookup = MutableStateFlow(SelectionLookupState())
    private var selectionLookupJob: Job? = null

    private val content = combine(
        gameRepository.goalGames,
        gameRepository.backlog,
        hltbRepository.reviewQueue,
        credentials.credentialsStateFlow,
        liveStatusRepository.nowPlaying,
    ) { goals, backlog, reviewQueue, credState, nowPlaying ->
        val goalIds = goals.mapTo(HashSet()) { it.appId }
        LibraryContent(
            configured = credState is CredentialsState.Configured,
            goals = goals,
            // Drop any game already shown as a goal: goalGames and backlog come from two
            // independent Room queries that can momentarily both contain a just-tagged game,
            // and a duplicate appId across LazyColumn items crashes Compose.
            backlog = backlog.filterNot { it.appId in goalIds },
            reviewCount = reviewQueue.size,
            hltbCandidatesByAppId = reviewQueue.associate { it.appId to it.candidates },
            playingAppId = (nowPlaying as? NowPlaying.InGame)?.gameId,
        )
    }

    /**
     * The XP badge's inputs. `ruleConfig` is combined in like any other input rather than letting
     * the engine's `cfg` default apply: [RuleConfig] is user-tunable and persisted, and every other
     * caller threads the stored value — a defaulted config would compile and render plausible
     * numbers that do not add up to the player's total.
     */
    private val xpInputs = combine(
        sessionRepository.trackedMinutesByGame,
        achievementRepository.unlockedRarityByGame,
        settings.ruleConfig,
    ) { tracked, rarity, cfg -> XpInputs(tracked, rarity, cfg) }

    private val pickerCombined = combine(
        pickerStates,
        pickerManualLinkStates,
        needsAttention,
        ::Triple,
    )

    private val viewPrefs = combine(
        combine(query, selection, fetchOps, pickerCombined, settings.librarySort) {
                query, selection, ops, pickerTriple, sort ->
            val (pickerStates, manualLinkStates, needsAttention) = pickerTriple
            ViewPrefs(
                query = query,
                selection = selection,
                ops = ops,
                pickerStates = pickerStates,
                pickerManualLinkStates = manualLinkStates,
                needsAttention = needsAttention,
                sort = sort,
                density = GameListDensity.LIST,
            )
        },
        settings.libraryDensity,
    ) { prefs, density -> prefs.copy(density = density) }

    val uiState: StateFlow<LibraryUiState> = combine(
        content,
        xpInputs,
        achievementRepository.counts,
        viewPrefs,
        selectionLookup,
    ) { content, xp, counts, view, lookup ->
        val goals = content.goals
            .map { it.toGoalUi(xp, counts, view.ops, content.playingAppId) }
            .matching(view.query)
            .sortedFor(
                key = view.sort.focus,
                direction = view.sort.focusDirection,
                query = view.query,
            )
        val backlog = content.backlog
            .map { it.toBacklogUi(xp, counts, view.ops, content.playingAppId) }
            .matching(view.query)
            .sortedFor(
                key = view.sort.library,
                direction = view.sort.libraryDirection,
                query = view.query,
            )
        LibraryUiState(
            loading = false,
            configured = content.configured,
            goalGames = goals,
            backlog = backlog,
            reviewCount = content.reviewCount,
            hltbCandidatesByAppId = content.hltbCandidatesByAppId,
            pickerStates = view.pickerStates,
            pickerManualLinkStates = view.pickerManualLinkStates,
            needsAttentionAppId = view.needsAttention,
            refreshing = lookup.running,
            query = view.query,
            focusSort = view.sort.focus,
            librarySort = view.sort.library,
            focusSortDirection = view.sort.focusDirection,
            librarySortDirection = view.sort.libraryDirection,
            density = view.density,
            availableGenres = content.goals
                .asSequence()
                .plus(content.backlog.asSequence())
                .flatMap { it.genres.asSequence() }
                .toList(),
            selection = view.selection,
            libraryEmpty = content.goals.isEmpty() && content.backlog.isEmpty(),
            batchProgress = lookup.progress,
            batchLog = lookup.log,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = LibraryUiState(),
    )

    fun tagGoal(appId: Long) = viewModelScope.launch {
        gameRepository.tagGoal(appId)
    }

    fun untagGoal(appId: Long) = viewModelScope.launch {
        gameRepository.untagGoal(appId)
    }

    fun setQuery(value: String) {
        query.value = value
    }

    fun clearQuery() {
        query.value = ""
    }

    fun setFocusSort(key: LibrarySortKey) = viewModelScope.launch {
        settings.setFocusSort(key)
    }

    fun setLibrarySort(key: LibrarySortKey) = viewModelScope.launch {
        settings.setLibrarySort(key)
    }

    fun setFocusSortDirection(direction: LibrarySortDirection) = viewModelScope.launch {
        settings.setFocusSortDirection(direction)
    }

    fun setLibrarySortDirection(direction: LibrarySortDirection) = viewModelScope.launch {
        settings.setLibrarySortDirection(direction)
    }

    fun setDensity(density: GameListDensity) = viewModelScope.launch {
        settings.setLibraryDensity(density)
    }

    /** Add or remove one game from the selection; removing the last one exits selection mode. */
    fun toggleSelection(appId: Long) = selection.update {
        if (appId in it) it - appId else it + appId
    }

    /** Drop the whole selection. Called on navigation away, so nothing outlives the screen. */
    fun clearSelection() {
        selection.value = emptySet()
    }

    /**
     * Run the HowLongToBeat lookup over an explicit [games] selection (appId to name),
     * unconditionally — an explicit selection is always looked up regardless of any cached or
     * dataset-supplied data. Runs on this ViewModel's own scope: leaving the screen cancels it,
     * and any game already processed keeps the data it received, since each result is written to
     * Room as it is looked up. Cleared afterward so the action bar does not linger over a run the
     * user can already see in the progress panel.
     */
    fun refreshSelection(games: List<Pair<Long, String>>) {
        if (games.isEmpty()) return
        clearSelection()
        selectionLookupJob?.cancel()
        selectionLookupJob = viewModelScope.launch {
            selectionLookup.value = SelectionLookupState(running = true)
            try {
                hltbRepository.refreshSelection(games) { done, total, name, outcome ->
                    selectionLookup.update { current ->
                        current.copy(
                            progress = HltbSelectionProgress(done, total, name, outcome),
                            log = (current.log + HltbLogEntry(name, outcome)).takeLast(SELECTION_LOG_LIMIT),
                        )
                    }
                }
            } finally {
                selectionLookup.value = SelectionLookupState()
            }
        }
    }

    /**
     * Force a fresh HowLongToBeat lookup for a single game (ignoring the cache) and surface the
     * outcome: [HltbFetchOp.IN_PROGRESS] while it runs, then either [HltbFetchOp.FAILED] (the
     * request itself failed — cached data is left intact) or the persisted match status once it
     * succeeds (matched / needs review / no match). When the persisted result requires a manual
     * match decision (ambiguous or no match), the appId is raised as one-shot state (see
     * [needsAttention]) so the active screen can jump into the match center: holding it as state,
     * not invoking a callback, keeps the completion signal alive across the originating
     * composition being disposed or recreated while this `viewModelScope` job is still running.
     */
    fun refreshGame(appId: Long, name: String) = viewModelScope.launch {
        fetchOps.update { it + (appId to HltbFetchOp.IN_PROGRESS) }
        val result = hltbRepository.refresh(appId, name)
        fetchOps.update {
            if (result == null) it + (appId to HltbFetchOp.FAILED) else it - appId
        }
        when (result?.matchStatus.toDomain()) {
            HltbMatchState.NEEDS_REVIEW, HltbMatchState.UNMATCHED -> needsAttention.value = appId
            else -> Unit
        }
    }

    /** Consume the one-shot match-center request raised by [refreshGame]. */
    fun consumeNeedsAttention() {
        needsAttention.value = null
    }

    fun resolveMatch(appId: Long, candidate: HltbCandidate) = viewModelScope.launch {
        hltbRepository.resolveMatch(appId, candidate)
    }

    fun changeMatch(appId: Long, name: String) {
        pickerJobs.remove(appId)?.cancel()
        pickerStates.update { it + (appId to HltbPickerUiState(loading = true)) }
        val job = viewModelScope.launch {
            val result = runCatching { hltbRepository.searchCandidates(name) }
            pickerStates.update { states ->
                val current = states[appId] ?: return@update states
                states + (appId to current.copy(
                    candidates = result.getOrDefault(emptyList()),
                    loading = false,
                    failed = result.isFailure,
                ))
            }
        }
        pickerJobs[appId] = job
        job.invokeOnCompletion {
            if (pickerJobs[appId] === job) pickerJobs.remove(appId)
        }
    }

    fun clearPicker(appId: Long) {
        pickerJobs.remove(appId)?.cancel()
        pickerStates.update { it - appId }
        clearPickerManualLink(appId)
    }

    fun updatePickerManualLinkInput(appId: Long, input: String) {
        pickerManualLinkStates.update { map ->
            val existing = map[appId] ?: PickerManualLinkUiState()
            map + (appId to existing.copy(input = input, validationError = null, notFound = false, failed = false, preview = null))
        }
    }

    fun previewPickerManualLink(appId: Long) {
        if (pickerManualLinkJobs[appId]?.isActive == true) return
        val state = pickerManualLinkStates.value[appId] ?: PickerManualLinkUiState()
        if (state.loading) return
        val input = state.input.trim()
        if (input.isEmpty()) {
            pickerManualLinkStates.update { it + (appId to state.copy(validationError = "Enter an HLTB link")) }
            return
        }
        pickerManualLinkStates.update { it + (appId to state.copy(loading = true, validationError = null, notFound = false, failed = false, preview = null)) }
        val job = viewModelScope.launch {
            // Resolve into the *latest* entry, never the snapshot taken before launch, and drop a
            // result whose submitted input was since edited (clearing loading so the new input
            // can be previewed) — a stale preview must never overwrite newer user input.
            val resolved: (PickerManualLinkUiState) -> PickerManualLinkUiState = when (val result = hltbRepository.previewLinkedCandidate(input)) {
                is com.example.backlogium.data.repo.ManualLinkPreviewResult.Preview -> {
                    { it.copy(loading = false, preview = result.candidate) }
                }
                is com.example.backlogium.data.repo.ManualLinkPreviewResult.Invalid -> {
                    { it.copy(loading = false, validationError = "Invalid HLTB link: ${result.reason}") }
                }
                is com.example.backlogium.data.repo.ManualLinkPreviewResult.NotFound -> {
                    { it.copy(loading = false, notFound = true) }
                }
                is com.example.backlogium.data.repo.ManualLinkPreviewResult.Failed -> {
                    { it.copy(loading = false, failed = true, failureClass = result.failureClass) }
                }
            }
            pickerManualLinkStates.update { map ->
                val current = map[appId] ?: return@update map
                val next = if (current.input.trim() == input) resolved(current) else current.copy(loading = false)
                map + (appId to next)
            }
        }
        pickerManualLinkJobs[appId] = job
        job.invokeOnCompletion { if (pickerManualLinkJobs[appId] === job) pickerManualLinkJobs.remove(appId) }
    }

    fun dismissPickerManualLinkPreview(appId: Long) {
        pickerManualLinkJobs[appId]?.cancel()
        pickerManualLinkJobs.remove(appId)
        pickerManualLinkStates.update { map ->
            val existing = map[appId] ?: return@update map
            map + (appId to existing.copy(preview = null, loading = false, notFound = false, failed = false, validationError = null))
        }
    }

    fun clearPickerManualLink(appId: Long) {
        pickerManualLinkJobs[appId]?.cancel()
        pickerManualLinkJobs.remove(appId)
        pickerManualLinkStates.update { it - appId }
    }

    fun confirmPickerManualLink(appId: Long) = viewModelScope.launch {
        val preview = pickerManualLinkStates.value[appId]?.preview ?: return@launch
        hltbRepository.resolveMatch(appId, preview)
        clearPicker(appId)
    }

    /** Stop a running selection lookup. Every game already processed keeps its data. */
    fun stopHltbRefresh() = selectionLookupJob?.cancel()

    private companion object {
        /** The log is a progress aid, not a record: only the recent tail is worth keeping. */
        const val SELECTION_LOG_LIMIT = 50
    }
}

/** The two Room-backed lists plus the two screen-wide facts, before any per-row derivation. */
private data class LibraryContent(
    val configured: Boolean,
    val goals: List<LibraryGame>,
    val backlog: List<LibraryGame>,
    val reviewCount: Int,
    val hltbCandidatesByAppId: Map<Long, List<HltbCandidate>>,
    /** appId of the game Steam's live presence reports as running right now, if any. */
    val playingAppId: Long?,
)

/** Everything the XP badge needs, gathered once per change rather than per row. */
internal data class XpInputs(
    val trackedByGame: Map<Long, Int>,
    val rarityByGame: Map<Long, List<Double?>>,
    val cfg: RuleConfig,
)

/** Transient view state (filter, selection, in-flight lookups) plus the persisted sort choices. */
private data class ViewPrefs(
    val query: String,
    val selection: Set<Long>,
    val ops: Map<Long, HltbFetchOp>,
    val pickerStates: Map<Long, HltbPickerUiState>,
    val pickerManualLinkStates: Map<Long, PickerManualLinkUiState> = emptyMap(),
    /** One-shot match-center navigation request; see [LibraryViewModel.consumeNeedsAttention]. */
    val needsAttention: Long? = null,
    val sort: LibrarySortPrefs,
    val density: GameListDensity,
)

/** The running selection lookup's state: whether it is active, its latest snapshot, and its log. */
private data class SelectionLookupState(
    val running: Boolean = false,
    val progress: HltbSelectionProgress? = null,
    val log: List<HltbLogEntry> = emptyList(),
)

private fun LibraryGame.toGoalUi(
    xp: XpInputs,
    counts: Map<Long, AchievementCounts>,
    ops: Map<Long, HltbFetchOp>,
    playingAppId: Long?,
) = GoalGameUi(
    appId = appId,
    name = name,
    iconUrl = iconUrl,
    headerUrl = headerUrl,
    heroCapsuleUrl = heroCapsuleUrl,
    playtimeForever = displayedPlaytimeMinutes(xp),
    playtime2Weeks = playtime2Weeks,
    xpContributed = xpContribution(xp),
    completionistMinutes = completionistMinutes,
    hltbStatus = hltbMatchState,
    fetchOp = ops[appId],
    achievementUnlocked = counts[appId]?.unlocked,
    achievementTotal = counts[appId]?.total,
    isCurrentlyPlaying = appId == playingAppId,
    genres = genres,
    recencyState = recencyState,
    isFamilyShared = when (source) {
        GameSource.FAMILY_SHARED -> true
        GameSource.STEAM_OWNED -> false
    },
)

private fun LibraryGame.toBacklogUi(
    xp: XpInputs,
    counts: Map<Long, AchievementCounts>,
    ops: Map<Long, HltbFetchOp>,
    playingAppId: Long?,
) = BacklogGameUi(
    appId = appId,
    name = name,
    iconUrl = iconUrl,
    headerUrl = headerUrl,
    heroCapsuleUrl = heroCapsuleUrl,
    playtimeForever = displayedPlaytimeMinutes(xp),
    playtime2Weeks = playtime2Weeks,
    xpContributed = xpContribution(xp),
    completionistMinutes = completionistMinutes,
    hltbStatus = hltbMatchState,
    fetchOp = ops[appId],
    achievementUnlocked = counts[appId]?.unlocked,
    achievementTotal = counts[appId]?.total,
    isCurrentlyPlaying = appId == playingAppId,
    genres = genres,
    recencyState = recencyState,
    isFamilyShared = when (source) {
        GameSource.FAMILY_SHARED -> true
        GameSource.STEAM_OWNED -> false
    },
)

/**
 * The playtime figure the Library shows, sorts by, and measures completion progress against.
 *
 * For an owned game this is Steam's lifetime total, unchanged. For a family-shared game Steam
 * reports no total at all, so `playtimeForever` is structurally 0 and using it would render a game
 * with a real history of sessions as "0m" — and sort it to the bottom of every playtime ordering.
 * The observed session minutes are the only playtime such a game has, and the row labels them as
 * observed rather than presenting them as a Steam total. [manualSharedMinutes] is added on top —
 * the player's own estimate, additive with what was actually tracked
 * (add-shared-game-playtime-and-filter).
 */
internal fun LibraryGame.displayedPlaytimeMinutes(xp: XpInputs): Int = when (source) {
    GameSource.STEAM_OWNED -> playtimeForever
    GameSource.FAMILY_SHARED -> (xp.trackedByGame[appId] ?: 0) + manualSharedMinutes
}

/**
 * This game's XP contribution, from the engine's own inputs: frozen backfill plus tracked session
 * minutes (never `playtimeForever`, which includes pre-install hours that only count when the
 * player imported history), tapered against its completionist length, plus its unlocked
 * achievements' rarity XP.
 */
private fun LibraryGame.xpContribution(xp: XpInputs): Int = LibraryXp.contribution(
    GameXpInput(
        appId = appId,
        minutesPlayed = backfillMinutes + (xp.trackedByGame[appId] ?: 0),
        completionistMinutes = completionistMinutes,
        unlockedRarityPercents = xp.rarityByGame[appId].orEmpty(),
    ),
    xp.cfg,
)

/** Case-insensitive name-or-genre filter ranked by the strongest match tier. */
internal fun <T : LibraryRow> List<T>.matching(query: String): List<T> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return this
    return mapNotNull { game ->
        gameSearchMatchTier(
            query = trimmed,
            name = game.name,
            genreLabels = game.genres.asSequence().map(GameGenre::label).asIterable(),
        )?.let { tier -> game to tier }
    }
        .sortedBy { (_, tier) -> tier.ordinal }
        .map { (game, _) -> game }
}
