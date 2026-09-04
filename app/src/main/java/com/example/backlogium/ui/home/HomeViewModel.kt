package com.example.backlogium.ui.home

import androidx.lifecycle.ViewModel
import com.example.backlogium.data.local.dao.AchievementCounts
import androidx.lifecycle.viewModelScope
import com.example.backlogium.data.local.entity.Collection
import com.example.backlogium.data.local.entity.CollectionMember
import com.example.backlogium.data.local.AcquiredGamesAnnouncement
import com.example.backlogium.data.local.SharedGameAnnouncement
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
import com.example.backlogium.data.repo.LibraryGame
import com.example.backlogium.data.repo.ProfileRepository
import com.example.backlogium.data.repo.ProgressEventRepository
import com.example.backlogium.data.repo.SessionRepository
import com.example.backlogium.data.repo.SettingsRepository
import com.example.backlogium.domain.CollectionAccent
import com.example.backlogium.domain.CollectionBanner
import com.example.backlogium.domain.CollectionMemberSignals
import com.example.backlogium.domain.CollectionMode
import com.example.backlogium.domain.CollectionSummary
import com.example.backlogium.domain.CurrentDateProvider
import com.example.backlogium.domain.exactExpiryTicks
import com.example.backlogium.domain.GameRecencyState
import com.example.backlogium.domain.displayedPlaytimeMinutes
import com.example.backlogium.domain.ProgressEvent
import com.example.backlogium.domain.SmartCollectionFeed
import com.example.backlogium.domain.SmartCollectionId
import com.example.backlogium.domain.TimeProvider
import com.example.backlogium.gamification.Gamification
import com.example.backlogium.gamification.RuleConfig
import com.example.backlogium.ui.collections.smartCollectionName
import com.example.backlogium.work.setup.SetupCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeUiState(
    val loading: Boolean = true,
    val configured: Boolean = true,
    /**
     * True while a first configuration still owes the user the setup step. Durable state, so the
     * onboarding takeover is restored after an abrupt process death — by then credentials are stored
     * and [configured] alone can no longer tell a half-finished first run from a settled install.
     */
    val firstRunSetupActive: Boolean = false,
    val level: Int = 1,
    val xpIntoLevel: Long = 0L,
    val xpForNext: Long = 0L,
    val totalXp: Long = 0L,
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
    /**
     * The running game's recency state, if it carries one. Home's one genuine game surface, so
     * this is where the badge appears here — the collection cards' 26dp thumbnail strip is both
     * too small for a legible glyph and a collection member list, which this change excludes.
     */
    val nowPlayingRecencyState: GameRecencyState? = null,
    /** Mission cards derived from the player's custom collections; empty when none exist. */
    val collections: List<HomeCollectionCard> = emptyList(),
    /**
     * Derived collections, in their fixed order, presented beneath the custom ones. Read-only:
     * they carry no accent, no mode, and no position the player can change.
     */
    val smartCollections: List<HomeSmartCollectionCard> = emptyList(),
    /** Highest-priority durable progress transition waiting for a Home consumer. */
    val pendingProgressEvent: ProgressEvent? = null,
    /** Dedicated streak-break delivery so unrelated unacknowledged events cannot hide the card. */
    val pendingStreakBreak: ProgressEvent.StreakBroken? = null,
    /** Dedicated streak-milestone delivery so an unrelated pending event cannot hide the animation. */
    val pendingStreakMilestone: ProgressEvent.StreakMilestone? = null,
    /** Durable fallback cue for automatic family-shared admission when notifications were unavailable. */
    val sharedGameAnnouncement: SharedGameAnnouncement? = null,
    /** The unexpired, undismissed newly-acquired-games announcement, or null when there is none. */
    val acquiredGames: AcquiredGamesUi? = null,
) {
    val xpFraction: Float
        get() = if (xpForNext > 0) (xpIntoLevel.toFloat() / xpForNext).coerceIn(0f, 1f) else 0f
}

/**
 * The newly-acquired-games banner's content: the names it can show and how many arrived beyond
 * them.
 *
 * Names rather than ids, resolved against the library the ViewModel already holds. The common case
 * (one or two games) then reads concretely, and a sale reads as a number — which is what a banner
 * announcing eight games should say rather than listing all eight.
 */
data class AcquiredGamesUi(
    val namedGames: List<String>,
    val unnamedCount: Int,
) {
    val totalCount: Int get() = namedGames.size + unnamedCount
}

/**
 * How many games the banner names individually before it starts counting instead. Three keeps the
 * common case (one or two purchases) concrete without a sale turning the banner into a list.
 */
private const val MAX_NAMED_ACQUIRED_GAMES = 3

/**
 * The banner's content for this announcement, or null when there is nothing to present.
 *
 * A game the backup or the library no longer knows the name of is *counted* rather than dropped:
 * the count is the announcement's load-bearing claim ("eight games arrived"), and silently omitting
 * an unnamed one would make the banner understate what happened.
 */
internal fun AcquiredGamesAnnouncement.toUi(
    namesByAppId: Map<Long, String>,
    now: Long,
): AcquiredGamesUi? {
    if (!isLive(now)) return null
    val named = appIds.mapNotNull(namesByAppId::get).sorted().take(MAX_NAMED_ACQUIRED_GAMES)
    return AcquiredGamesUi(namedGames = named, unnamedCount = appIds.size - named.size)
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

/**
 * One derived collection's Home entry: an identity and a count, and deliberately nothing else.
 *
 * A custom collection's card carries the intent the player declared — a deadline, a queue, an
 * accent. A derived list carries an observation the app made, so it has none of those to show and
 * no order of its own to keep.
 */
data class HomeSmartCollectionCard(
    val id: SmartCollectionId,
    val name: String,
    val memberCount: Int,
)


/**
 * The banner-and-announcement half of the Home combine, gathered so the typed `combine` overloads
 * still reach every input without dropping to the untyped vararg form.
 */
private data class HomeAnnouncements(
    val library: List<LibraryGame>,
    val acquiredBatch: AcquiredGamesAnnouncement,
    val sharedGameAnnouncement: SharedGameAnnouncement?,
    val smartCollections: List<HomeSmartCollectionCard>,
)

private data class HomeCollectionInputs(
    val members: List<CollectionMember>,
    val libraryGames: List<LibraryGame>,
    val counts: Map<Long, AchievementCounts>,
    val trackedMinutesByGame: Map<Long, Int>,
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
    private val sessionRepository: SessionRepository,
    private val progressEventRepository: ProgressEventRepository,
    private val setupCoordinator: SetupCoordinator,
    private val smartCollectionFeed: SmartCollectionFeed,
    private val time: TimeProvider,
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
        val xpState = Gamification.levelState(profile?.totalXp ?: 0L, config)
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
    }.combine(setupCoordinator.firstRunSetupActive) { state, firstRunActive ->
        state.copy(firstRunSetupActive = firstRunActive)
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
            combine(
                collectionRepository.members(collection.id),
                gameRepository.library,
                achievementRepository.counts,
                sessionRepository.trackedMinutesByGame,
            ) { members, libraryGames, counts, trackedMinutesByGame ->
                HomeCollectionInputs(members, libraryGames, counts, trackedMinutesByGame)
            },
            personalPaceRepository.profile,
            currentDate.currentDate,
        ) { inputs, personalPace, today ->
            val members = inputs.members
            val libraryGames = inputs.libraryGames
            val counts = inputs.counts
            val trackedMinutesByGame = inputs.trackedMinutesByGame
            val gamesById = libraryGames.associateBy { it.appId }
            val signals = members.map { member ->
                val game = gamesById[member.appId]
                CollectionMemberSignals(
                    appId = member.appId,
                    // Null when the member references a game absent from the library — the pure
                    // derivation omits it from the summary without dropping the membership row.
                    name = game?.name,
                    playtimeMinutes = game?.let {
                        it.source.displayedPlaytimeMinutes(
                            it.playtimeForever,
                            trackedMinutesByGame[member.appId] ?: 0,
                            it.manualSharedMinutes,
                        )
                    } ?: 0,
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

    /**
     * Derived collections for Home. Hidden and empty lists are absent for exactly the reasons they
     * are absent from the Collections screen: the visibility preference is one setting, not one per
     * surface, and an empty list says nothing worth a row on the first screen the player sees.
     */
    private val smartCollectionCards: Flow<List<HomeSmartCollectionCard>> = combine(
        smartCollectionFeed.snapshot,
        settings.smartCollectionVisibility,
    ) { snapshot, visibility ->
        SmartCollectionId.entries
            .filter(visibility::isVisible)
            .mapNotNull { id ->
                snapshot.result[id].size
                    .takeIf { it > 0 }
                    ?.let { count ->
                        HomeSmartCollectionCard(
                            id = id,
                            name = smartCollectionName(id),
                            memberCount = count,
                        )
                    }
            }
    }

    /**
     * The stored acquisition batch, re-emitted at its exact expiry deadline so its window is re-evaluated.
     *
     * Expiry is computed rather than persisted — no worker, no alarm — and a collector-scoped delay
     * re-emits at the actual deadline. The banner therefore disappears on time even when it expires
     * between local midnights or while the app was closed.
     *
     * The game *names* are resolved downstream, against the library the ui-state combine already
     * collects, so the stored batch carries only app ids and the library is subscribed to once.
     */
    private val acquiredBatch: Flow<AcquiredGamesAnnouncement> = settings.acquiredGames
        .flatMapLatest { announcement ->
            exactExpiryTicks(
                nowMillis = time::nowMillis,
                nextExpiryAt = { now ->
                    announcement.takeIf { it.appIds.isNotEmpty() && !it.dismissed }
                        ?.let { it.acquiredAt + AcquiredGamesAnnouncement.LIFETIME_MILLIS }
                        ?.takeIf { it > now }
                },
            ).map { announcement }
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
        combine(
            gameRepository.library,
            acquiredBatch,
            settings.sharedGameAnnouncement,
            smartCollectionCards,
        ) { library, batch, shared, smartCards -> HomeAnnouncements(library, batch, shared, smartCards) },
    ) { state, live, cards, pendingEvents, announcements ->
        val library = announcements.library
        val batch = announcements.acquiredBatch
        val shared = announcements.sharedGameAnnouncement
        val playingAppId = (live.nowPlaying as? NowPlaying.InGame)?.gameId
        val acquired = batch.toUi(library.associate { it.appId to it.name }, time.nowMillis())
        val withCards = state.copy(
            collections = cards.map { card ->
                card.copy(
                    isCurrentlyPlaying = homeCollectionContainsPlayingGame(
                        games = card.games,
                        playingAppId = playingAppId,
                    ),
                )
            },
            smartCollections = announcements.smartCollections,
            pendingProgressEvent = pendingEvents.firstOrNull(),
            // Present one durable event at a time. This keeps a queue of simultaneous events from
            // producing concurrent overlays/animations or more than one haptic for one moment.
            pendingStreakBreak = pendingEvents.firstOrNull() as? ProgressEvent.StreakBroken,
            pendingStreakMilestone = pendingEvents.firstOrNull() as? ProgressEvent.StreakMilestone,
            acquiredGames = acquired,
            sharedGameAnnouncement = shared,
        )
        when (val nowPlaying = live.nowPlaying) {
            is NowPlaying.InGame -> withCards.copy(
                isInGame = true,
                nowPlayingName = nowPlaying.name,
                nowPlayingIconUrl = nowPlaying.iconUrl,
                nowPlayingHeaderUrl = nowPlaying.gameId?.let(SteamIconMapper::headerUrl),
                nowPlayingSessionStartedAt = live.sessionStartedAt,
                // Already derived by the repository, so Home neither re-implements the precedence
                // nor needs its own clock to expire it.
                nowPlayingRecencyState = library
                    .firstOrNull { it.appId == nowPlaying.gameId }
                    ?.recencyState,
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

    /** Dismiss the acquisition banner. Scoped to this batch: a later purchase announces again. */
    fun dismissAcquiredGames() {
        viewModelScope.launch { settings.setAcquiredGamesDismissed() }
    }

    /**
     * Acknowledge the durable fallback cue after the user sees or dismisses it. Scoped to the
     * queue entry currently on screen, so a game admitted while this one was still unseen keeps
     * its own cue rather than being dismissed along with it.
     */
    fun dismissSharedGameAnnouncement() {
        val appId = uiState.value.sharedGameAnnouncement?.appId ?: return
        viewModelScope.launch { settings.clearSharedGameAnnouncement(appId) }
    }

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
