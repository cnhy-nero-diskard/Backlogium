package com.example.backlogium.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.backlogium.data.remote.SteamIconMapper
import com.example.backlogium.data.repo.AchievementRepository
import com.example.backlogium.data.repo.CollectionRepository
import com.example.backlogium.data.repo.CredentialsRepository
import com.example.backlogium.data.repo.CredentialsState
import com.example.backlogium.data.repo.GameRepository
import com.example.backlogium.data.repo.LiveStatusRepository
import com.example.backlogium.data.repo.NowPlaying
import com.example.backlogium.data.repo.ProfileRepository
import com.example.backlogium.data.repo.SettingsRepository
import com.example.backlogium.data.local.entity.Collection
import com.example.backlogium.data.local.entity.CollectionMember
import com.example.backlogium.domain.CollectionAccent
import com.example.backlogium.domain.CollectionBanner
import com.example.backlogium.domain.CollectionMemberSignals
import com.example.backlogium.domain.CollectionMode
import com.example.backlogium.domain.CollectionSummary
import com.example.backlogium.domain.TimeProvider
import com.example.backlogium.gamification.Gamification
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import javax.inject.Inject

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
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val liveStatusRepository: LiveStatusRepository,
    private val credentials: CredentialsRepository,
    private val settings: SettingsRepository,
    private val time: TimeProvider,
    private val collectionRepository: CollectionRepository,
    private val gameRepository: GameRepository,
    private val achievementRepository: AchievementRepository,
) : ViewModel() {

    private val baseState: Flow<HomeUiState> = combine(
        profileRepository.profile,
        profileRepository.dailyProgress,
        settings.ruleConfig,
        credentials.credentialsStateFlow,
        profileRepository.syncInProgress,
    ) { profile, days, config, credState, isSyncing ->
        val todayKey = time.today().toString()
        val todayProgress = days.firstOrNull { it.date == todayKey }
        val xpState = Gamification.levelState(profile?.totalXp ?: 0, config)
        val configured = credState as? CredentialsState.Configured
        HomeUiState(
            loading = false,
            configured = configured != null,
            level = xpState.level,
            xpIntoLevel = xpState.xpIntoLevel,
            xpForNext = xpState.xpForNext,
            totalXp = xpState.totalXp,
            questMet = todayProgress?.questMet ?: false,
            todayMinutes = todayProgress?.minutesPlayed ?: 0,
            questThreshold = config.questThresholdMin,
            currentStreak = profile?.currentStreak ?: 0,
            longestStreak = profile?.longestStreak ?: 0,
            lastSyncError = profile?.lastSyncError,
            isSyncing = isSyncing,
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

    private fun deriveCard(collection: Collection): Flow<HomeCollectionCard> =
        combine(
            collectionRepository.members(collection.id),
            gameRepository.library,
            achievementRepository.counts,
        ) { members, libraryGames, counts ->
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
                today = time.today(),
            )
            HomeCollectionCard(
                collectionId = collection.id,
                name = collection.name,
                mode = collection.mode,
                accent = collection.accent,
                banner = banner,
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
    ) { state, live, cards ->
        val withCards = state.copy(collections = cards)
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
}
