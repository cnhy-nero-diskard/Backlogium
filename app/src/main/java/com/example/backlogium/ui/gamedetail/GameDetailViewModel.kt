package com.example.backlogium.ui.gamedetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.backlogium.data.repo.AchievementRepository
import com.example.backlogium.data.repo.FamilySharedGameRepository
import com.example.backlogium.data.repo.GameAchievement
import com.example.backlogium.data.repo.GameRepository
import com.example.backlogium.data.repo.GameGenre
import com.example.backlogium.data.repo.LibraryGame
import com.example.backlogium.data.repo.SessionRepository
import com.example.backlogium.data.repo.SettingsRepository
import com.example.backlogium.domain.GameSource
import com.example.backlogium.domain.GameXpInput
import com.example.backlogium.domain.LibraryXp
import com.example.backlogium.gamification.AchievementInput
import com.example.backlogium.gamification.Gamification
import com.example.backlogium.gamification.RarityStanding
import com.example.backlogium.gamification.RarityTier
import com.example.backlogium.gamification.RuleConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/** How the achievement list is ordered. Transient view state — deliberately not persisted. */
enum class AchievementSort {
    /** Most recently unlocked first. The default: "what did I just get" is the common question. */
    DATE_ACHIEVED,

    /** Rarest first, by the same percent each row displays. */
    RARITY,
}

/** One achievement row, pre-resolved against the engine's tier/XP rules for display. */
data class AchievementUi(
    val apiName: String,
    val displayName: String,
    val iconUrl: String?,
    val unlocked: Boolean,
    val tier: RarityTier?,
    val xp: Int,
    /**
     * The share of players who have this, as a percent. Deliberately the *same* number the rarity
     * sort keys on and — for unlocked rows — the one that earned the tier beside it, so the row can
     * never read "6% of players · Legendary". Null when neither percent is known.
     */
    val unlockPercent: Double?,
    val unlockedAt: Long?,
    val description: String?,
    val hidden: Boolean,
) {
    /**
     * Steam withholds a hidden achievement's description until it is unlocked, so a locked hidden
     * row has nothing to show and says so rather than leaving a blank line that reads as a bug.
     */
    val showHiddenLabel: Boolean get() = hidden && description == null
}

/** The game's own facts, above the achievement list. */
data class GameSummaryUi(
    val headerUrl: String = "",
    val iconUrl: String = "",
    /** Steam's lifetime playtime for the game. */
    val playtimeMinutes: Int = 0,
    /** Frozen historical playtime from the opt-in import; 0 when nothing was imported. */
    val importedMinutes: Int = 0,
    /** Minutes from sessions this app tracked itself. */
    val trackedMinutes: Int = 0,
    val mainStoryMinutes: Int? = null,
    val mainExtraMinutes: Int? = null,
    val completionistMinutes: Int? = null,
    val allStylesMinutes: Int? = null,
    val achievementsUnlocked: Int = 0,
    val achievementsTotal: Int = 0,
    /** XP this game contributed to the player's total, from `LibraryXp` — same value the Library shows. */
    val xpContributed: Int = 0,
    /**
     * The game's current Steam concurrent-player count, polled every 30 seconds while this
     * screen is open. Null until the first fetch resolves, and null again after any failed
     * poll — never persisted, never a placeholder zero.
     */
    val activePlayers: Int? = null,
    /** Ordered, cached Store genres. Empty means unknown or definitively unavailable. */
    val genres: List<GameGenre> = emptyList(),
    /**
     * True when this game is played through Family Sharing rather than owned. Drives the source
     * marking and the coverage disclosure; false for an owned game, which is presented exactly as
     * it was before family sharing existed.
     */
    val isFamilyShared: Boolean = false,
    /**
     * Whether background presence monitoring is on. Only read for a family-shared game, where it
     * is the actionable remedy for partial coverage — the disclosure points at it rather than
     * merely apologising.
     */
    val liveMonitorEnabled: Boolean = false,
) {
    /** True when any HLTB length resolved. Gates the whole block: no zeros, no placeholders. */
    val hasHltb: Boolean
        get() = mainStoryMinutes != null || mainExtraMinutes != null ||
            completionistMinutes != null || allStylesMinutes != null

    /**
     * Whether the playtime is worth splitting. Only meaningful once history was imported — with no
     * backfill the split is just the total restated.
     */
    val showPlaytimeSplit: Boolean get() = importedMinutes > 0

    /**
     * The playtime figure to lead with. Steam reports no lifetime total for a family-shared game,
     * so [playtimeMinutes] is structurally 0 for one and leading with it would read as "0m played"
     * beside a history of real sessions. Tracked minutes are what the app actually knows.
     */
    val headlineMinutes: Int get() = if (isFamilyShared) trackedMinutes else playtimeMinutes
}

data class GameDetailUiState(
    val loading: Boolean = true,
    val gameName: String = "",
    val summary: GameSummaryUi = GameSummaryUi(),
    val rarityStanding: RarityStanding.Result? = null,
    val achievements: List<AchievementUi> = emptyList(),
    val sort: AchievementSort = AchievementSort.DATE_ACHIEVED,
    val isRefreshingPlayerCount: Boolean = false,
) {
    /** True once every known achievement for this game is unlocked (100% completion). */
    val allUnlocked: Boolean
        get() = achievements.isNotEmpty() && achievements.all { it.unlocked }
}

/**
 * Drives the per-game detail screen: the game's own summary plus its achievements, each resolved
 * to a rarity tier and XP contribution via the engine's own `tierFor`/`achievementXp` (using the
 * persisted rarity snapshot, never the live percent — same rule the recompute uses).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class GameDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    achievementRepository: AchievementRepository,
    private val gameRepository: GameRepository,
    private val sharedGames: FamilySharedGameRepository,
    sessionRepository: SessionRepository,
    settings: SettingsRepository,
) : ViewModel() {

    private val appIdState = MutableStateFlow<Long?>(savedStateHandle["appId"])

    internal val appId: Long
        get() = checkNotNull(appIdState.value) {
            "GameDetailViewModel requires an app id before it can render"
        }

    /** Transient: a lens on the list, reset every visit rather than persisted as a preference. */
    private val sort = MutableStateFlow(AchievementSort.DATE_ACHIEVED)

    /**
     * Polled every 30 seconds while this screen is open, not part of [content] — [content]
     * combines only local, offline-safe flows, and a slow or failed network call must never
     * hold up the rest of the summary or the achievement list.
     */
    private val activePlayers = MutableStateFlow<Int?>(null)
    private val refreshingPlayerCount = MutableStateFlow(false)
    private var activePlayersPollingJob: Job? = null

    private val content = appIdState
        .filterNotNull()
        .distinctUntilChanged()
        .flatMapLatest { appId ->
            combine(
                gameRepository.library,
                achievementRepository.observeForGame(appId),
                sessionRepository.trackedMinutesByGame,
                settings.ruleConfig,
                settings.liveMonitorEnabled,
            ) { games, achievements, trackedByGame, config, liveMonitorEnabled ->
                Content(
                    games.firstOrNull { it.appId == appId },
                    achievements,
                    trackedByGame[appId] ?: 0,
                    config,
                    liveMonitorEnabled,
                )
            }
        }

    val uiState: StateFlow<GameDetailUiState> = combine(
        content,
        sort,
        activePlayers,
        refreshingPlayerCount,
    ) { content, sort, activePlayers, isRefreshingPlayerCount ->
        val rows = content.achievements.map { it.toUi(content.config) }
        GameDetailUiState(
            loading = false,
            gameName = content.game?.name ?: "",
            summary = content.toSummary(rows, activePlayers),
            rarityStanding = content.toRarityStanding(),
            achievements = rows.sortedWith(sort.comparator()),
            sort = sort,
            isRefreshingPlayerCount = isRefreshingPlayerCount,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = GameDetailUiState(),
    )

    /** Supply the explicit id used by a state-hosted sheet; navigation supplies the saved-state id. */
    internal fun setAppId(value: Long) {
        if (appIdState.value == value) return
        stopPolling()
        appIdState.value = value
        activePlayers.value = null
    }

    /** Start live polling while this screen presentation is composed. */
    internal fun startPolling() {
        val appId = appIdState.value ?: return
        if (activePlayersPollingJob?.isActive == true) return
        activePlayersPollingJob = viewModelScope.launch { pollActivePlayers(appId) }
    }

    /** Stop live polling when a retained sheet ViewModel leaves composition. */
    internal fun stopPolling() {
        activePlayersPollingJob?.cancel()
        activePlayersPollingJob = null
    }

    /** Refresh only the live player count, making this fetch the new anchor for periodic polling. */
    fun refreshPlayerCount() {
        val appId = appIdState.value ?: return
        if (refreshingPlayerCount.value) return

        stopPolling()
        refreshingPlayerCount.value = true
        activePlayersPollingJob = viewModelScope.launch {
            refreshPlayerCountOnce(
                refreshing = refreshingPlayerCount,
                fetch = { gameRepository.currentPlayerCount(appId) },
                publish = { activePlayers.value = it },
            )
            // Keep the next poll relative to the manual fetch instead of the cancelled loop's
            // previous schedule, which could otherwise fire immediately after the gesture. The
            // refresh indicator has already ended; polling is background maintenance, not the
            // one-shot gesture still being in flight.
            delay(ACTIVE_PLAYERS_POLL_INTERVAL_MS)
            pollActivePlayers(appId)
        }
    }

    private suspend fun pollActivePlayers(appId: Long) {
        while (currentCoroutineContext().isActive) {
            activePlayers.value = gameRepository.currentPlayerCount(appId)
            delay(ACTIVE_PLAYERS_POLL_INTERVAL_MS)
        }
    }

    fun setSort(value: AchievementSort) {
        sort.value = value
    }

    /**
     * Remove a family-shared game and record the exclusion, so further play does not re-admit it.
     * A no-op for an owned game — the repository guards it in SQL, because the contents of the
     * player's Steam library are not the app's to decide.
     */
    fun removeSharedGame() {
        val appId = appIdState.value ?: return
        viewModelScope.launch { sharedGames.remove(appId) }
    }

    private companion object {
        const val ACTIVE_PLAYERS_POLL_INTERVAL_MS = 30_000L
    }
}

/**
 * Runs only the one-shot part of a player-count refresh. Keeping its completion state separate
 * from the follow-up polling loop prevents the pull indicator from remaining active for the
 * entire 30-second cadence (or forever while the loop is alive).
 */
internal suspend fun refreshPlayerCountOnce(
    refreshing: MutableStateFlow<Boolean>,
    fetch: suspend () -> Int?,
    publish: (Int?) -> Unit,
) {
    refreshing.value = true
    try {
        publish(fetch())
    } finally {
        refreshing.value = false
    }
}

/** The flows the screen derives from, gathered before any per-row work. */
internal data class Content(
    val game: LibraryGame?,
    val achievements: List<GameAchievement>,
    val trackedMinutes: Int,
    val config: RuleConfig,
    /** Only consulted for a family-shared game, as the remedy its disclosure points at. */
    val liveMonitorEnabled: Boolean = false,
)

/**
 * The standing uses the full observed row count and the live global rates. It must not reuse
 * [GameAchievement.rarityPercent], which is intentionally frozen for XP/tier stability.
 */
internal fun Content.toRarityStanding(): RarityStanding.Result? {
    if (achievements.isEmpty()) return null
    return RarityStanding.derive(
        RarityStanding.Input(
            totalAchievements = achievements.size,
            unlockedAchievements = achievements.count { it.unlocked },
            globalUnlockPercents = achievements.map { it.globalPercent },
        ),
    )
}

internal fun Content.toSummary(rows: List<AchievementUi>, activePlayers: Int?): GameSummaryUi {
    val game = game ?: return GameSummaryUi()
    return GameSummaryUi(
        headerUrl = game.headerUrl,
        iconUrl = game.iconUrl,
        playtimeMinutes = game.playtimeForever,
        importedMinutes = game.backfillMinutes,
        trackedMinutes = trackedMinutes,
        mainStoryMinutes = game.mainStoryMinutes,
        mainExtraMinutes = game.mainExtraMinutes,
        completionistMinutes = game.completionistMinutes,
        allStylesMinutes = game.allStylesMinutes,
        achievementsUnlocked = rows.count { it.unlocked },
        achievementsTotal = rows.size,
        // The Library's own derivation, called rather than re-written, so the two agree by
        // construction: engine inputs (backfill + tracked minutes), never `playtimeForever`.
        xpContributed = LibraryXp.contribution(
            GameXpInput(
                appId = game.appId,
                minutesPlayed = game.backfillMinutes + trackedMinutes,
                completionistMinutes = game.completionistMinutes,
                unlockedRarityPercents = achievements.filter { it.unlocked }.map { it.rarityPercent },
            ),
            config,
        ),
        activePlayers = activePlayers,
        genres = game.genres,
        isFamilyShared = when (game.source) {
            GameSource.FAMILY_SHARED -> true
            GameSource.STEAM_OWNED -> false
        },
        liveMonitorEnabled = liveMonitorEnabled,
    )
}

/**
 * Resolves the engine's tier/XP for one achievement. Presentation only — the rarity percent that
 * feeds tier and XP is the repository's frozen snapshot, so neither follows the live global percent.
 *
 * The *displayed* percent is a separate concern: it falls back to the live percent for locked rows,
 * which have no snapshot. Since that fallback is also the rarity sort's key, what a row shows, what
 * it sorts by, and what earned its XP are one number.
 */
internal fun GameAchievement.toUi(config: RuleConfig): AchievementUi {
    val percent = rarityPercent
    val tierable = unlocked && percent != null
    return AchievementUi(
        apiName = apiName,
        displayName = displayName,
        iconUrl = iconUrl,
        unlocked = unlocked,
        tier = if (tierable) Gamification.tierFor(percent!!) else null,
        xp = if (tierable) {
            Gamification.achievementXp(listOf(AchievementInput(apiName, true, percent)), config)
        } else {
            0
        },
        unlockPercent = displayPercent,
        unlockedAt = unlockedAt,
        description = description,
        hidden = hidden,
    )
}

/**
 * The percent a row shows and sorts by: the frozen snapshot when there is one, else the live global
 * percent. Unlocked rows therefore show the number that produced their tier, and locked rows — which
 * never have a snapshot — still show how rare the achievement is.
 */
private val GameAchievement.displayPercent: Double?
    get() = rarityPercent ?: globalPercent

/**
 * Ordering for the chosen sort. Locked achievements group after unlocked ones in both modes: in
 * date order they have no date at all, and in rarity order their percent answers a different
 * question ("how rare is this" rather than "how rare was mine"), so interleaving them would produce
 * an order that looks arbitrary. A null key sorts last within its own group.
 */
internal fun AchievementSort.comparator(): Comparator<AchievementUi> {
    val lockedLast = compareByDescending<AchievementUi> { it.unlocked }
    return when (this) {
        // Most recent first.
        AchievementSort.DATE_ACHIEVED -> lockedLast
            .thenByDescending { it.unlockedAt ?: Long.MIN_VALUE }
            .thenBy { it.displayName }

        // Rarest first — the *lowest* percent is the rarest.
        AchievementSort.RARITY -> lockedLast
            .thenBy { it.unlockPercent ?: Double.MAX_VALUE }
            .thenBy { it.displayName }
    }
}
