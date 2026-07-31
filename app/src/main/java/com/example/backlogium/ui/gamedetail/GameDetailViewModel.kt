package com.example.backlogium.ui.gamedetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.backlogium.data.repo.AchievementRepository
import com.example.backlogium.data.repo.GameAchievement
import com.example.backlogium.data.repo.GameRepository
import com.example.backlogium.data.repo.LibraryGame
import com.example.backlogium.data.repo.SessionRepository
import com.example.backlogium.data.repo.SettingsRepository
import com.example.backlogium.domain.GameXpInput
import com.example.backlogium.domain.LibraryXp
import com.example.backlogium.gamification.AchievementInput
import com.example.backlogium.gamification.Gamification
import com.example.backlogium.gamification.RarityTier
import com.example.backlogium.gamification.RuleConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
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
     * The game's current Steam concurrent-player count, fetched once when the screen opens.
     * Null until that fetch resolves, and null forever on failure — never persisted, never a
     * placeholder zero.
     */
    val activePlayers: Int? = null,
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
}

data class GameDetailUiState(
    val loading: Boolean = true,
    val gameName: String = "",
    val summary: GameSummaryUi = GameSummaryUi(),
    val achievements: List<AchievementUi> = emptyList(),
    val sort: AchievementSort = AchievementSort.DATE_ACHIEVED,
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
@HiltViewModel
class GameDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    achievementRepository: AchievementRepository,
    gameRepository: GameRepository,
    sessionRepository: SessionRepository,
    settings: SettingsRepository,
) : ViewModel() {

    private val appId: Long = checkNotNull(savedStateHandle["appId"])

    /** Transient: a lens on the list, reset every visit rather than persisted as a preference. */
    private val sort = MutableStateFlow(AchievementSort.DATE_ACHIEVED)

    /**
     * Fetched once per screen visit, not part of [content] — [content] combines only local,
     * offline-safe flows, and a slow or failed network call must never hold up the rest of the
     * summary or the achievement list.
     */
    private val activePlayers = MutableStateFlow<Int?>(null)

    private val content = combine(
        gameRepository.library,
        achievementRepository.observeForGame(appId),
        sessionRepository.trackedMinutesByGame,
        settings.ruleConfig,
    ) { games, achievements, trackedByGame, config ->
        Content(games.firstOrNull { it.appId == appId }, achievements, trackedByGame[appId] ?: 0, config)
    }

    val uiState: StateFlow<GameDetailUiState> = combine(content, sort, activePlayers) { content, sort, activePlayers ->
        val rows = content.achievements.map { it.toUi(content.config) }
        GameDetailUiState(
            loading = false,
            gameName = content.game?.name ?: "",
            summary = content.toSummary(rows, activePlayers),
            achievements = rows.sortedWith(sort.comparator()),
            sort = sort,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = GameDetailUiState(),
    )

    init {
        viewModelScope.launch {
            activePlayers.value = gameRepository.currentPlayerCount(appId)
        }
    }

    fun setSort(value: AchievementSort) {
        sort.value = value
    }
}

/** The four flows the screen derives from, gathered before any per-row work. */
private data class Content(
    val game: LibraryGame?,
    val achievements: List<GameAchievement>,
    val trackedMinutes: Int,
    val config: RuleConfig,
)

private fun Content.toSummary(rows: List<AchievementUi>, activePlayers: Int?): GameSummaryUi {
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
