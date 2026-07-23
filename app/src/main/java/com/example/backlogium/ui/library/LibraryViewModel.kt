package com.example.backlogium.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.backlogium.BuildConfig
import com.example.backlogium.data.local.SettingsDataStore
import com.example.backlogium.data.local.entity.HltbMatchStatus
import com.example.backlogium.data.repo.GameRepository
import com.example.backlogium.data.repo.HltbRepository
import com.example.backlogium.work.SyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
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

data class GoalGameUi(
    val appId: Long,
    val name: String,
    val iconUrl: String,
    val playtimeForever: Int,
    /** HowLongToBeat Completionist length, if resolved. Null → no completion-based progress. */
    val completionistMinutes: Int? = null,
    /** Persisted match status from the cache, or null when no lookup has been stored yet. */
    val hltbStatus: HltbMatchStatus? = null,
    /** In-flight/failed state of a manual lookup, layered over [hltbStatus]. */
    val fetchOp: HltbFetchOp? = null,
)

data class BacklogGameUi(
    val appId: Long,
    val name: String,
    val iconUrl: String,
    val playtimeForever: Int,
    val hltbStatus: HltbMatchStatus? = null,
    val fetchOp: HltbFetchOp? = null,
)

data class LibraryUiState(
    val loading: Boolean = true,
    val configured: Boolean = true,
    val goalGames: List<GoalGameUi> = emptyList(),
    val backlog: List<BacklogGameUi> = emptyList(),
    val reviewCount: Int = 0,
    val refreshing: Boolean = false,
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val gameRepository: GameRepository,
    private val hltbRepository: HltbRepository,
    private val syncScheduler: SyncScheduler,
    private val settings: SettingsDataStore,
) : ViewModel() {

    /** Per-game manual-lookup state, keyed by appId. Not persisted — cleared on success. */
    private val fetchOps = MutableStateFlow<Map<Long, HltbFetchOp>>(emptyMap())

    private val content = combine(
        gameRepository.goalGames,
        gameRepository.backlog,
        hltbRepository.allData,
        hltbRepository.needsReview,
        settings.steamIdFlow,
    ) { goals, backlog, hltb, review, steamId ->
        val rowByAppId = hltb.associateBy { it.appId }
        val goalIds = goals.mapTo(HashSet()) { it.appId }
        LibraryUiState(
            loading = false,
            configured = BuildConfig.STEAM_API_KEY.isNotBlank() && steamId.isNotBlank(),
            goalGames = goals.map { game ->
                val row = rowByAppId[game.appId]
                GoalGameUi(
                    appId = game.appId,
                    name = game.name,
                    iconUrl = game.iconUrl,
                    playtimeForever = game.playtimeForever,
                    completionistMinutes = row?.completionistMinutes,
                    hltbStatus = row?.matchStatus,
                )
            },
            // Drop any game already shown as a goal: goalGames and backlog come from two
            // independent Room queries that can momentarily both contain a just-tagged game,
            // and a duplicate appId across LazyColumn items crashes Compose.
            backlog = backlog.filterNot { it.appId in goalIds }.map { game ->
                BacklogGameUi(
                    appId = game.appId,
                    name = game.name,
                    iconUrl = game.iconUrl,
                    playtimeForever = game.playtimeForever,
                    hltbStatus = rowByAppId[game.appId]?.matchStatus,
                )
            },
            reviewCount = review.size,
        )
    }

    val uiState: StateFlow<LibraryUiState> = combine(
        content,
        syncScheduler.hltbRefreshInProgress,
        fetchOps,
    ) { state, refreshing, ops ->
        state.copy(
            refreshing = refreshing,
            goalGames = state.goalGames.map { it.copy(fetchOp = ops[it.appId]) },
            backlog = state.backlog.map { it.copy(fetchOp = ops[it.appId]) },
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
