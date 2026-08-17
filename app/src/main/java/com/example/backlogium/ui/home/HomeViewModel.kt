package com.example.backlogium.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.backlogium.data.local.entity.Collection
import com.example.backlogium.data.local.entity.CollectionMember
import com.example.backlogium.data.local.PresenceMonitoringAvailability
import com.example.backlogium.data.remote.SteamIconMapper
import com.example.backlogium.data.repo.AchievementRepository
import com.example.backlogium.data.repo.CollectionRepository
import com.example.backlogium.data.repo.CredentialsRepository
import com.example.backlogium.data.repo.CredentialsState
import com.example.backlogium.data.repo.DayProgress
import com.example.backlogium.data.repo.GameRepository
import com.example.backlogium.data.repo.LiveStatusRepository
import com.example.backlogium.data.repo.NowPlaying
import com.example.backlogium.data.repo.PersonalPaceRepository
import com.example.backlogium.data.repo.PlayerStats
import com.example.backlogium.data.repo.ProfileRepository
import com.example.backlogium.data.repo.ProgressEventRepository
import com.example.backlogium.data.repo.SettingsRepository
import com.example.backlogium.domain.CollectionAccent
import com.example.backlogium.domain.CollectionBanner
import com.example.backlogium.domain.CollectionMemberSignals
import com.example.backlogium.domain.CollectionMode
import com.example.backlogium.domain.CollectionSummary
import com.example.backlogium.domain.CurrentDateProvider
import com.example.backlogium.domain.ProgressEvent
import com.example.backlogium.gamification.Gamification
import com.example.backlogium.gamification.RuleConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeUiState(
    val loading: Boolean = true,
    val configured: Boolean = true,
    val level: Int = 1,
    val xpIntoLevel: Int = 0,
    val xpForNext: Int = 0,
    val totalXp: Int = 0,
    val questMet: Boolean = false,
    val todayMinutes: Int = 0,
    val questThreshold: Int = 30,
    val currentStreak: Int = 0,
    val longestStreak: Int = 0,
    val lastSyncError: String? = null,
    /** True while any sync is in flight; disables the error card's retry. */
    val isSyncing: Boolean = false,
    /** Why opt-in live monitoring is unavailable, if Android stopped or refused it. */
    val liveMonitoringAvailability: PresenceMonitoringAvailability =
        PresenceMonitoringAvailability.AVAILABLE,
    val isInGame: Boolean = false,
    val nowPlayingName: String? = null,
    val nowPlayingIconUrl: String? = null,
    /**
     * Store header art for the running game, drawn as a faint backdrop in the now-playing panel.
     * Null when Steam's running-game id didn't parse — the panel then renders without a backdrop,
     * exactly as a Library row does for a game whose header art 404s.
     */
    val nowPlayingHeaderUrl: String? = null,
    /** When the current session was first observed, for the card's elapsed-time ticker. */
    val nowPlayingSessionStartedAt: Long? = null,
    /** Mission cards derived from the player's custom collections; empty when none exist. */
    val collections: List<HomeCollectionCard> = emptyList(),
    /** Highest-priority durable progress transition waiting for a Home consumer. */
    val pendingProgressEvent: ProgressEvent? = null,
    /** Dedicated streak-break delivery so unrelated unacknowledged events cannot hide the card. */
    val pendingStreakBreak: ProgressEvent.StreakBroken? = null,
    /** Dedicated streak-milestone delivery so an unrelated pending event cannot hide the animation. */
    val pendingStreakMilestone: ProgressEvent.StreakMilestone? = null,
) {
    val xpFraction: Float
        get() = if (xpForNext > 0) (xpIntoLevel.toFloat() / xpForNext).coerceIn(0f, 1f) else 0f
}

/** One collection's Home mission card: its identity plus its freshly derived mode banner. */
data class HomeCollectionCard(
    val collectionId: Long,
    val name: String,
    val mode: CollectionMode,
    val accent: CollectionAccent?,
    val banner: CollectionBanner,
    val games: List<HomeCollectionGame>,
    val isCurrentlyPlaying: Boolean = false,
)

data class HomeCollectionGame(
    val appId: Long,
    val name: String,
    val iconUrl: String?,
)

/** The two Home fields that describe one calendar day rather than the profile as a whole. */
internal data class HomeDayFields(
    val minutesPlayed: Int,
    val questMet: Boolean,
)

/**
 * Resolve [today]'s row out of the stored per-day progress.
 *
 * A date with no stored row reads as zero and unmet, never as the nearest row that does exist:
 * absence of progress is itself the answer, and falling back to a neighbouring day is exactly the
 * bug this function was extracted to make testable — Home used to hold the previous day's values
 * past midnight because its date input never changed.
 */
internal fun homeDayFields(days: List<DayProgress>, today: LocalDate): HomeDayFields {
    val row = days.firstOrNull { it.date == today.toString() }
    return HomeDayFields(
        minutesPlayed = row?.minutesPlayed ?: 0,
        questMet = row?.questMet ?: false,
    )
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val liveStatusRepository: LiveStatusRepository,
    private val credentials: CredentialsRepository,
    private val settings: SettingsRepository,
    private val currentDate: CurrentDateProvider,
    private val collectionRepository: CollectionRepository,
    private val gameRepository: GameRepository,
    private val achievementRepository: AchievementRepository,
    private val personalPaceRepository: PersonalPaceRepository,
    private val progressEventRepository: ProgressEventRepository,
) : ViewModel() {

    /**
     * The five data inputs, gathered so the current date can join them as a sixth. Combining in one
     * step would need the untyped `combine` vararg overload; this keeps the lambda's types.
     */
    private data class HomeData(
        val profile: PlayerStats?,
        val days: List<DayProgress>,
        val config: RuleConfig,
        val credState: CredentialsState,
        val isSyncing: Boolean,
        val liveMonitoringAvailability: PresenceMonitoringAvailability =
            PresenceMonitoringAvailability.AVAILABLE,
    )

    private val homeData: Flow<HomeData> = combine(
        profileRepository.profile,
        profileRepository.dailyProgress,
        settings.ruleConfig,
        credentials.credentialsStateFlow,
        profileRepository.syncInProgress,
    ) { profile, days, config, credState, isSyncing ->
        HomeData(profile, days, config, credState, isSyncing)
    }.combine(settings.liveMonitoringAvailability) { data, availability ->
        data.copy(liveMonitoringAvailability = availability)
    }

    // The date is an input, not a call inside the lambda: crossing midnight has to re-run this
    // combine on its own, or the previous day's row stays on screen as the current day's.
    private val baseState: Flow<HomeUiState> = combine(
        homeData,
        currentDate.currentDate,
    ) { data, today ->
        val (profile, days, config, credState, isSyncing) = data
        val dayFields = homeDayFields(days, today)
        val xpState = Gamification.levelState(profile?.totalXp ?: 0, config)
        val configured = credState as? CredentialsState.Configured
        HomeUiState(
            loading = false,
            configured = configured != null,
            level = xpState.level,
            xpIntoLevel = xpState.xpIntoLevel,
            xpForNext = xpState.xpForNext,
            totalXp = xpState.totalXp,
            questMet = dayFields.questMet,
            todayMinutes = dayFields.minutesPlayed,
            questThreshold = config.questThresholdMin,
            currentStreak = profile?.currentStreak ?: 0,
            longestStreak = profile?.longestStreak ?: 0,
            lastSyncError = profile?.lastSyncError,
            isSyncing = isSyncing,
            liveMonitoringAvailability = data.liveMonitoringAvailability,
        )
    }

    /**
     * Mission cards, one per collection, derived purely from local state (offline-first). The
     * collection flows are app-owned Room data; the member signals come from the cached library
     * and achievement counts. The pure [CollectionSummary] turns those into each card's banner.
     */
    private val collectionCards: Flow<List<HomeCollectionCard>> =
        collectionRepository.collections.flatMapLatest { collections ->
            if (collections.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(collections.map(::deriveCard)) { cards -> cards.toList() }
            }
        }

    // A mission card's banner counts down to a target date, so it goes stale at midnight for the
    // same reason the quest tick does — the date joins as an input here too.
    private fun deriveCard(collection: Collection): Flow<HomeCollectionCard> =
        combine(
            collectionRepository.members(collection.id),
            gameRepository.library,
            achievementRepository.counts,
            personalPaceRepository.profile,
            currentDate.currentDate,
        ) { members, libraryGames, counts, personalPace, today ->
            val gamesById = libraryGames.associateBy { it.appId }
            val signals = members.map { member ->
                val game = gamesById[member.appId]
                CollectionMemberSignals(
                    appId = member.appId,
                    // Null when the member references a game absent from the library — the pure
                    // derivation omits it from the summary without dropping the membership row.
                    name = game?.name,
                    playtimeMinutes = game?.playtimeForever ?: 0,
                    completionistMinutes = game?.completionistMinutes,
                    mainStoryMinutes = game?.mainStoryMinutes,
                    mainExtraMinutes = game?.mainExtraMinutes,
                    allStylesMinutes = game?.allStylesMinutes,
                    achievementsUnlocked = counts[member.appId]?.unlocked,
                    achievementsTotal = counts[member.appId]?.total,
                    manualDone = member.done,
                )
            }
            val banner = CollectionSummary.derive(
                mode = collection.mode,
                sort = collection.sort,
                targetDate = collection.targetDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                members = signals,
                today = today,
                timeBasis = collection.timeBasis,
                personalPace = personalPace,
            )
            HomeCollectionCard(
                collectionId = collection.id,
                name = collection.name,
                mode = collection.mode,
                accent = collection.accent,
                banner = banner,
                games = members.map { member ->
                    val game = gamesById[member.appId]
                    HomeCollectionGame(
                        appId = member.appId,
                        name = game?.name ?: "Game ${member.appId}",
                        iconUrl = game?.iconUrl,
                    )
                },
            )
        }

    // A plain observer: PresenceService owns the poll, and BacklogiumApp's foreground observer owns
    // the one-off re-check, so collecting liveStatus here never starts or extends anything — Home
    // just reflects whatever the last poll found, degraded (no live card) but not broken while
    // nothing is polling.
    val uiState: StateFlow<HomeUiState> = combine(
        baseState,
        liveStatusRepository.liveStatus,
        collectionCards,
        progressEventRepository.pendingEvents,
    ) { state, live, cards, pendingEvents ->
        val playingAppId = (live.nowPlaying as? NowPlaying.InGame)?.gameId
        val withCards = state.copy(
            collections = cards.map { card ->
                card.copy(
                    isCurrentlyPlaying = homeCollectionContainsPlayingGame(
                        games = card.games,
                        playingAppId = playingAppId,
                    ),
                )
            },
            pendingProgressEvent = pendingEvents.firstOrNull(),
            pendingStreakBreak = pendingEvents.filterIsInstance<ProgressEvent.StreakBroken>().firstOrNull(),
            pendingStreakMilestone = pendingEvents.filterIsInstance<ProgressEvent.StreakMilestone>()
                .firstOrNull(),
        )
        when (val nowPlaying = live.nowPlaying) {
            is NowPlaying.InGame -> withCards.copy(
                isInGame = true,
                nowPlayingName = nowPlaying.name,
                nowPlayingIconUrl = nowPlaying.iconUrl,
                nowPlayingHeaderUrl = nowPlaying.gameId?.let(SteamIconMapper::headerUrl),
                nowPlayingSessionStartedAt = live.sessionStartedAt,
            )
            NowPlaying.NotPlaying -> withCards
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState(),
    )

    /** Retry a failed sync from the Home error card; the manual trigger lives in Settings. */
    fun syncNow() = profileRepository.syncNow()

    /** Acknowledge only after the corresponding progress event has actually been presented. */
    fun acknowledgeProgressEvent(event: ProgressEvent) {
        viewModelScope.launch {
            progressEventRepository.acknowledge(event)
        }
    }

    /** Persist the order of all collection cards after a completed Home drag. */
    fun reorderCollections(orderedIds: List<Long>) {
        viewModelScope.launch {
            collectionRepository.reorderCollections(orderedIds)
        }
    }
}
