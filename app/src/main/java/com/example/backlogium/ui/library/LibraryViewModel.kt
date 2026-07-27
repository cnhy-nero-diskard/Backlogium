package com.example.backlogium.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.backlogium.data.local.dao.AchievementCounts
import com.example.backlogium.data.repo.AchievementRepository
import com.example.backlogium.data.repo.CredentialsRepository
import com.example.backlogium.data.repo.CredentialsState
import com.example.backlogium.data.repo.GameRepository
import com.example.backlogium.data.repo.HltbMatchState
import com.example.backlogium.data.repo.HltbRepository
import com.example.backlogium.data.repo.LibraryGame
import com.example.backlogium.data.repo.SessionRepository
import com.example.backlogium.data.repo.SettingsRepository
import com.example.backlogium.domain.GameXpInput
import com.example.backlogium.domain.LibrarySortKey
import com.example.backlogium.domain.LibrarySortPrefs
import com.example.backlogium.domain.LibraryXp
import com.example.backlogium.gamification.RuleConfig
import com.example.backlogium.work.HltbBatchProgress
import com.example.backlogium.work.SyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Transient (non-persisted) state of an in-flight or just-finished per-game HLTB lookup. */
enum class HltbFetchOp { IN_PROGRESS, FAILED }

data class GoalGameUi(
    override val appId: Long,
    override val name: String,
    val iconUrl: String,
    override val playtimeForever: Int,
    /** Steam's rolling two-week playtime — the "recently played" sort key, not displayed. */
    override val playtime2Weeks: Int = 0,
    /** XP this game has contributed to the player's total. Zero is a real value, not "unknown". */
    override val xpContributed: Int = 0,
    /** HowLongToBeat Completionist length, if resolved. Null → no completion-based progress. */
    val completionistMinutes: Int? = null,
    /** Persisted match status from the cache, or null when no lookup has been stored yet. */
    val hltbStatus: HltbMatchState? = null,
    /** In-flight/failed state of a manual lookup, layered over [hltbStatus]. */
    val fetchOp: HltbFetchOp? = null,
    /** Unlocked/total achievement counts, null when no achievement data is stored yet. */
    val achievementUnlocked: Int? = null,
    val achievementTotal: Int? = null,
) : LibraryRow

data class BacklogGameUi(
    override val appId: Long,
    override val name: String,
    val iconUrl: String,
    override val playtimeForever: Int,
    override val playtime2Weeks: Int = 0,
    override val xpContributed: Int = 0,
    /**
     * HowLongToBeat Completionist length, if resolved. Present here too: the batch refresh fetches
     * a length for every owned game, so withholding completion progress from untagged rows was a
     * leftover from when only tagged games had a target at all.
     */
    val completionistMinutes: Int? = null,
    val hltbStatus: HltbMatchState? = null,
    val fetchOp: HltbFetchOp? = null,
    val achievementUnlocked: Int? = null,
    val achievementTotal: Int? = null,
) : LibraryRow

/** One processed game in a running batch sweep. A null [outcome] means the lookup failed. */
data class HltbLogEntry(val gameName: String, val outcome: HltbMatchState?)

data class LibraryUiState(
    val loading: Boolean = true,
    val configured: Boolean = true,
    /** The tracked ("Focus") section, already filtered and sorted for display. */
    val goalGames: List<GoalGameUi> = emptyList(),
    /** The rest of the library ("Your games"), already filtered and sorted for display. */
    val backlog: List<BacklogGameUi> = emptyList(),
    val reviewCount: Int = 0,
    val refreshing: Boolean = false,
    val query: String = "",
    val focusSort: LibrarySortKey = LibrarySortKey.NAME,
    val librarySort: LibrarySortKey = LibrarySortKey.PLAYTIME,
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
    val batchProgress: HltbBatchProgress? = null,
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
    private val syncScheduler: SyncScheduler,
    private val credentials: CredentialsRepository,
) : ViewModel() {

    /** Per-game manual-lookup state, keyed by appId. Not persisted — cleared on success. */
    private val fetchOps = MutableStateFlow<Map<Long, HltbFetchOp>>(emptyMap())

    /** Name filter. Applied in memory: the library is already loaded, so no query per keystroke. */
    private val query = MutableStateFlow("")

    /** Transient multi-select for the targeted refresh. Never persisted (see [clearSelection]). */
    private val selection = MutableStateFlow<Set<Long>>(emptySet())

    private val content = combine(
        gameRepository.goalGames,
        gameRepository.backlog,
        hltbRepository.reviewCount,
        credentials.credentialsStateFlow,
    ) { goals, backlog, reviewCount, credState ->
        val goalIds = goals.mapTo(HashSet()) { it.appId }
        LibraryContent(
            configured = credState is CredentialsState.Configured,
            goals = goals,
            // Drop any game already shown as a goal: goalGames and backlog come from two
            // independent Room queries that can momentarily both contain a just-tagged game,
            // and a duplicate appId across LazyColumn items crashes Compose.
            backlog = backlog.filterNot { it.appId in goalIds },
            reviewCount = reviewCount,
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

    private val viewPrefs = combine(
        query,
        selection,
        fetchOps,
        settings.librarySort,
        ::ViewPrefs,
    )

    /**
     * The sweep's progress plus the log accumulated from it. WorkManager progress carries one
     * snapshot, so the history is rebuilt on this side — which means it only covers the period the
     * screen has been observed. Leaving mid-run and returning shows correct progress with a log
     * that resumes from that point; documented behavior, not a bug.
     */
    private val batchState = combine(
        syncScheduler.hltbRefreshInProgress,
        syncScheduler.hltbRefreshProgress
            .distinctUntilChanged()
            .runningFold(BatchLog()) { acc, next -> acc.accumulate(next) },
        ::BatchState,
    )

    val uiState: StateFlow<LibraryUiState> = combine(
        content,
        xpInputs,
        achievementRepository.counts,
        viewPrefs,
        batchState,
    ) { content, xp, counts, view, batch ->
        val goals = content.goals
            .map { it.toGoalUi(xp, counts, view.ops) }
            .matching(view.query)
            .sortedFor(view.sort.focus)
        val backlog = content.backlog
            .map { it.toBacklogUi(xp, counts, view.ops) }
            .matching(view.query)
            .sortedFor(view.sort.library)
        LibraryUiState(
            loading = false,
            configured = content.configured,
            goalGames = goals,
            backlog = backlog,
            reviewCount = content.reviewCount,
            refreshing = batch.refreshing,
            query = view.query,
            focusSort = view.sort.focus,
            librarySort = view.sort.library,
            selection = view.selection,
            libraryEmpty = content.goals.isEmpty() && content.backlog.isEmpty(),
            batchProgress = batch.log.progress,
            batchLog = batch.log.entries,
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

    /** Add or remove one game from the selection; removing the last one exits selection mode. */
    fun toggleSelection(appId: Long) = selection.update {
        if (appId in it) it - appId else it + appId
    }

    /** Drop the whole selection. Called on navigation away, so nothing outlives the screen. */
    fun clearSelection() {
        selection.value = emptySet()
    }

    /**
     * Run the HowLongToBeat lookup over the current selection only, forced. Cleared afterward so
     * the action bar does not linger over a run the user can already see in the progress panel.
     */
    fun refreshSelection() {
        val appIds = selection.value
        if (appIds.isEmpty()) return
        syncScheduler.refreshHltbNow(appIds)
        clearSelection()
    }

    /**
     * Force a fresh HowLongToBeat lookup for a single game (ignoring the cache) and surface the
     * outcome: [HltbFetchOp.IN_PROGRESS] while it runs, then either [HltbFetchOp.FAILED] (the
     * request itself failed — cached data is left intact) or the persisted match status once it
     * succeeds (matched / needs review / no match).
     */
    fun refreshGame(appId: Long, name: String) = viewModelScope.launch {
        fetchOps.update { it + (appId to HltbFetchOp.IN_PROGRESS) }
        val result = hltbRepository.refresh(appId, name)
        fetchOps.update {
            if (result == null) it + (appId to HltbFetchOp.FAILED) else it - appId
        }
    }

    /** Enqueue the batch HLTB refresh. [force] re-fetches every game regardless of freshness. */
    fun refreshHltb(force: Boolean) = syncScheduler.refreshHltbNow(force)
}

/** The two Room-backed lists plus the two screen-wide facts, before any per-row derivation. */
private data class LibraryContent(
    val configured: Boolean,
    val goals: List<LibraryGame>,
    val backlog: List<LibraryGame>,
    val reviewCount: Int,
)

/** Everything the XP badge needs, gathered once per change rather than per row. */
private data class XpInputs(
    val trackedByGame: Map<Long, Int>,
    val rarityByGame: Map<Long, List<Double?>>,
    val cfg: RuleConfig,
)

/** Transient view state (filter, selection, in-flight lookups) plus the persisted sort choices. */
private data class ViewPrefs(
    val query: String,
    val selection: Set<Long>,
    val ops: Map<Long, HltbFetchOp>,
    val sort: LibrarySortPrefs,
)

private data class BatchState(val refreshing: Boolean, val log: BatchLog)

/** Progress snapshot plus the entries accumulated from earlier snapshots of the same run. */
private data class BatchLog(
    val progress: HltbBatchProgress? = null,
    val entries: List<HltbLogEntry> = emptyList(),
) {
    /**
     * Fold one snapshot in. A null snapshot means no run is reporting — including a *finished*
     * one, since WorkManager clears progress on completion — so both progress and log reset rather
     * than freezing at the last game. A count that does not advance past the previous one is a new
     * run, which starts a fresh log.
     */
    fun accumulate(next: HltbBatchProgress?): BatchLog {
        if (next == null) return BatchLog()
        val entry = HltbLogEntry(next.gameName, next.outcome)
        val restarted = progress == null || next.done <= progress.done
        return BatchLog(
            progress = next,
            entries = if (restarted) listOf(entry) else (entries + entry).takeLast(LOG_LIMIT),
        )
    }

    private companion object {
        /** The log is a progress aid, not a record: only the recent tail is worth keeping. */
        const val LOG_LIMIT = 50
    }
}

private fun LibraryGame.toGoalUi(
    xp: XpInputs,
    counts: Map<Long, AchievementCounts>,
    ops: Map<Long, HltbFetchOp>,
) = GoalGameUi(
    appId = appId,
    name = name,
    iconUrl = iconUrl,
    playtimeForever = playtimeForever,
    playtime2Weeks = playtime2Weeks,
    xpContributed = xpContribution(xp),
    completionistMinutes = completionistMinutes,
    hltbStatus = hltbMatchState,
    fetchOp = ops[appId],
    achievementUnlocked = counts[appId]?.unlocked,
    achievementTotal = counts[appId]?.total,
)

private fun LibraryGame.toBacklogUi(
    xp: XpInputs,
    counts: Map<Long, AchievementCounts>,
    ops: Map<Long, HltbFetchOp>,
) = BacklogGameUi(
    appId = appId,
    name = name,
    iconUrl = iconUrl,
    playtimeForever = playtimeForever,
    playtime2Weeks = playtime2Weeks,
    xpContributed = xpContribution(xp),
    completionistMinutes = completionistMinutes,
    hltbStatus = hltbMatchState,
    fetchOp = ops[appId],
    achievementUnlocked = counts[appId]?.unlocked,
    achievementTotal = counts[appId]?.total,
)

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

/** Case-insensitive name filter; a blank query matches everything. */
private fun <T : LibraryRow> List<T>.matching(query: String): List<T> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return this
    return filter { it.name.contains(trimmed, ignoreCase = true) }
}
