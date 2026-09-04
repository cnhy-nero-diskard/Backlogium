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
import com.example.backlogium.domain.GameRecencyState
import com.example.backlogium.domain.GameXpInput
import com.example.backlogium.domain.LibraryXp
import com.example.backlogium.domain.SetSharedGamePlaytimeUseCase
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
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
    /**
     * A family-shared game's own hours-played estimate, in minutes; 0 for an owned game or when
     * unset (add-shared-game-playtime-and-filter).
     */
    val manualMinutes: Int = 0,
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
    /** The one recency signal this game carries, already derived; null when it carries none. */
    val recencyState: GameRecencyState? = null,
    /** Steam's last-played time, or null where Steam reported none. */
    val lastPlayedAt: Long? = null,
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
     * beside a history of real sessions. Tracked minutes are what the app actually knows, plus
     * [manualMinutes] — the player's own estimate on top (add-shared-game-playtime-and-filter).
     */
    val headlineMinutes: Int get() = if (isFamilyShared) trackedMinutes + manualMinutes else playtimeMinutes

    val lastPlayed: LastPlayed
        get() = when {
            headlineMinutes == 0 -> LastPlayed.Never
            lastPlayedAt == null -> LastPlayed.Unknown
            else -> LastPlayed.At(lastPlayedAt)
        }
}

/** What the summary can say about a game's last-played time. */
sealed interface LastPlayed {
    data object Never : LastPlayed
    data object Unknown : LastPlayed
    data class At(val epochMillis: Long) : LastPlayed
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
    private val setSharedGamePlaytime: SetSharedGamePlaytimeUseCase,
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
    private val _removedSharedGameEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val removedSharedGameEvents: SharedFlow<Unit> = _removedSharedGameEvents.asSharedFlow()

    private val content = appIdState
        .filterNotNull()
        .distinctUntilChanged()
        .flatMapLatest { appId ->
            combine(
                combine(
                    gameRepository.library,
                    achievementRepository.observeForGame(appId),
                    sessionRepository.trackedMinutesByGame,
                    sessionRepository.latestSessionAtByGame,
                ) { games, achievements, trackedByGame, latestByGame ->
                    DetailLocalInputs(games, achievements, trackedByGame, latestByGame)
                },
                settings.ruleConfig,
                settings.liveMonitorEnabled,
            ) { inputs, config, liveMonitorEnabled ->
                Content(
                    inputs.games.firstOrNull { it.appId == appId },
                    inputs.achievements,
                    inputs.trackedByGame[appId] ?: 0,
                    inputs.latestByGame[appId],
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
        viewModelScope.launch {
            if (sharedGames.remove(appId)) _removedSharedGameEvents.emit(Unit)
        }
    }

    /**
     * Set (or clear, with 0 hours) a family-shared game's manual playtime estimate. A no-op for
     * an owned game, a negative input, or a non-finite/out-of-range one (`NaN`/`Infinity` would
     * otherwise throw in `roundToInt()` or overflow the minutes total) — [SetSharedGamePlaytimeUseCase]
     * guards the owned/negative cases and [manualHoursToMinutes] the rest
     * (add-shared-game-playtime-and-filter). [content] already recomputes its summary from the
     * same `GameRepository`/`GamificationUpdater` state this write updates, so no separate
     * refresh event is needed here.
     */
    fun setManualPlaytime(hours: Double) {
        val appId = appIdState.value ?: return
        val minutes = manualHoursToMinutes(hours) ?: return
        viewModelScope.launch { setSharedGamePlaytime(appId, minutes) }
    }

    internal companion object {
        const val ACTIVE_PLAYERS_POLL_INTERVAL_MS = 30_000L
        const val MINUTES_PER_HOUR = 60
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

/**
 * Validate a manual-hours estimate before it reaches `roundToInt()`. `String.toDoubleOrNull()`
 * accepts `NaN`/`Infinity`, and `NaN.roundToInt()` throws while unbounded values can overflow
 * the minutes total downstream — so non-finite, negative, and out-of-range inputs are rejected
 * here and in the dialog, which disables Save for them (add-shared-game-playtime-and-filter).
 *
 * @return the estimate in whole minutes, or null when [hours] must be rejected rather than written.
 */
internal fun manualHoursToMinutes(hours: Double): Int? {
    if (!hours.isFinite() || hours < 0.0) return null
    val minutes = hours * GameDetailViewModel.MINUTES_PER_HOUR
    if (minutes > Int.MAX_VALUE) return null
    return minutes.roundToInt()
}

/**
 * Parse the dialog's raw text into the minutes [manualHoursToMinutes] accepts. Blank clears the
 * estimate (0); anything that parses to a rejected value — text, a pasted `NaN`/`Infinity`, a
 * negative, or an out-of-range total — yields null so the dialog can disable Save.
 *
 * @return 0 for blank input, whole minutes for a valid estimate, null when Save must stay disabled.
 */
internal fun parseManualHoursInput(input: String): Int? {
    if (input.isBlank()) return 0
    val hours = input.toDoubleOrNull() ?: return null
    return manualHoursToMinutes(hours)
}

/** The flows the screen derives from, gathered before any per-row work. */
private data class DetailLocalInputs(
    val games: List<LibraryGame>,
    val achievements: List<GameAchievement>,
    val trackedByGame: Map<Long, Int>,
    val latestByGame: Map<Long, Long>,
)

internal data class Content(
    val game: LibraryGame?,
    val achievements: List<GameAchievement>,
    val trackedMinutes: Int,
    val latestTrackedAt: Long? = null,
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
        manualMinutes = game.manualSharedMinutes,
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
                minutesPlayed = game.backfillMinutes + game.manualSharedMinutes + trackedMinutes,
                completionistMinutes = game.completionistMinutes,
                unlockedRarityPercents = achievements.filter { it.unlocked }.map { it.rarityPercent },
            ),
            config,
        ),
        activePlayers = activePlayers,
        genres = game.genres,
        recencyState = game.recencyState,
        lastPlayedAt = when (game.source) {
            GameSource.FAMILY_SHARED -> latestTrackedAt
            GameSource.STEAM_OWNED -> game.lastPlayedAt
        },
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
