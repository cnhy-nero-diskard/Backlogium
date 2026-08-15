package com.example.backlogium.ui.analytics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.backlogium.data.repo.AchievementRepository
import com.example.backlogium.data.repo.CredentialsRepository
import com.example.backlogium.data.repo.CredentialsState
import com.example.backlogium.data.repo.DayProgress
import com.example.backlogium.data.repo.GameRepository
import com.example.backlogium.data.repo.LibraryGame
import com.example.backlogium.data.repo.PlaySession
import com.example.backlogium.data.repo.PlayerStats
import com.example.backlogium.data.repo.ProfileRepository
import com.example.backlogium.data.repo.SessionRepository
import com.example.backlogium.data.repo.SettingsRepository
import com.example.backlogium.data.repo.UnlockedAchievementRarity
import com.example.backlogium.domain.TimeProvider
import com.example.backlogium.gamification.Gamification
import com.example.backlogium.gamification.RarityTier
import com.example.backlogium.ui.history.HistoryWindowBounds
import com.example.backlogium.ui.history.historyWindowBounds
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/** One bar in the daily playtime chart: a local date and that day's tracked minutes. */
data class AnalyticsDay(val date: LocalDate, val minutes: Int)

/** One row in a most-played or inspected-day games list. */
data class AnalyticsGame(
    val appId: Long,
    val name: String,
    val iconUrl: String,
    val minutes: Int,
)

/** One all-time unlocked achievement in the rarity drill-down. */
data class AnalyticsRarityAchievement(
    val appId: Long,
    val gameName: String,
    val achievementName: String,
    val rarityPercent: Double,
    val tier: RarityTier,
)

/** Count of unlocked achievements per rarity tier - the all-time rarity profile. */
data class RarityBreakdown(
    val common: Int = 0,
    val uncommon: Int = 0,
    val rare: Int = 0,
    val epic: Int = 0,
    val legendary: Int = 0,
) {
    val total: Int get() = common + uncommon + rare + epic + legendary
}

/** Aggregated session shape over the selected window - count, average, and longest session. */
data class SessionInsights(
    val sessionCount: Int = 0,
    val averageMinutes: Int = 0,
    val longestMinutes: Int = 0,
)

/** When the player tends to play, bucketed by the local hour of session start. */
data class TimeOfDayPattern(
    val morningMinutes: Int = 0,   // 5:00-11:59
    val afternoonMinutes: Int = 0, // 12:00-16:59
    val eveningMinutes: Int = 0,   // 17:00-20:59
    val nightMinutes: Int = 0,     // 21:00-4:59
) {
    /** The bucket with the most minutes, or null if all are zero. */
    val peakBucket: String?
        get() = listOf(
            "Morning" to morningMinutes,
            "Afternoon" to afternoonMinutes,
            "Evening" to eveningMinutes,
            "Night" to nightMinutes,
        ).filter { it.second > 0 }.maxByOrNull { it.second }?.first
}

data class AnalyticsUiState(
    val loading: Boolean = true,
    val configured: Boolean = true,
    val window: AnalyticsWindow = INITIAL_WINDOW,
    val windowBounds: AnalyticsWindowBounds = INITIAL_WINDOW.resolve(),
    val earliestTrackedDate: LocalDate? = null,
    val canStepEarlier: Boolean = false,
    /** One entry per local day in the selected window, including zero-minute days, oldest first. */
    val dailyMinutes: List<AnalyticsDay> = emptyList(),
    /** The configured daily-quest threshold, drawn as a reference line on the chart. */
    val questThreshold: Int = 30,
    val currentStreak: Int = 0,
    val longestStreak: Int = 0,
    /** Count of quest-met days inside the selected window. */
    val questMetDaysCount: Int = 0,
    /** Up to five games ranked by tracked minutes in the selected window, descending. */
    val topGames: List<AnalyticsGame> = emptyList(),
    /** All-time rarity tier breakdown of unlocked achievements. */
    val rarityBreakdown: RarityBreakdown = RarityBreakdown(),
    /** Up to twenty all-time rarest unlocked achievements, ordered by frozen rarity percent. */
    val rarestAchievements: List<AnalyticsRarityAchievement> = emptyList(),
    /** Session shape over the selected window. */
    val sessionInsights: SessionInsights = SessionInsights(),
    /** Time-of-day play pattern over the selected window. */
    val timeOfDayPattern: TimeOfDayPattern = TimeOfDayPattern(),
    /** Per-day game totals used by chart-day inspection; absent days have no breakdown. */
    val gamesByDate: Map<LocalDate, List<AnalyticsGame>> = emptyMap(),
) {
    /** True when there is at least one tracked minute in the selected window. */
    val hasData: Boolean
        get() = dailyMinutes.any { it.minutes > 0 } || topGames.isNotEmpty()
}

private data class AnalyticsInputs(
    val sessions: List<PlaySession>,
    val minutesByGame: Map<Long, Int>,
    val library: List<LibraryGame>,
    val dailyProgress: List<DayProgress>,
    val profile: PlayerStats?,
)

private data class ResolvedAnalyticsWindow(
    val window: AnalyticsWindow,
    val bounds: AnalyticsWindowBounds,
    val epochBounds: HistoryWindowBounds,
    val earliestTrackedDate: LocalDate?,
    val canStepEarlier: Boolean,
)

private val INITIAL_WINDOW = AnalyticsWindow(
    anchor = LocalDate.of(1970, 1, 1),
    length = AnalyticsWindowLength.THIRTY_DAYS,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AnalyticsViewModel @Inject constructor(
    private val sessionRepository: SessionRepository,
    private val gameRepository: GameRepository,
    private val profileRepository: ProfileRepository,
    private val achievementRepository: AchievementRepository,
    private val settings: SettingsRepository,
    private val credentials: CredentialsRepository,
    private val time: TimeProvider,
) : ViewModel() {

    private val selectedWindow = MutableStateFlow(
        AnalyticsWindow(
            anchor = time.today(),
            length = AnalyticsWindowLength.THIRTY_DAYS,
        ),
    )

    /** The current selection, exposed separately for callers that need controls outside uiState. */
    val window: StateFlow<AnalyticsWindow> = selectedWindow.asStateFlow()

    private val earliestTrackedDate: StateFlow<LocalDate?> = sessionRepository.earliestSessionStart
        .map { startAt -> startAt?.let { localDate(it, time.zone()) } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null,
        )

    private fun resolveWindow(window: AnalyticsWindow, earliest: LocalDate?): ResolvedAnalyticsWindow {
        val bounds = window.resolve()
        return ResolvedAnalyticsWindow(
            window = window,
            bounds = bounds,
            epochBounds = historyWindowBounds(
                start = bounds.start,
                endInclusive = bounds.endInclusive,
                zone = time.zone(),
            ),
            earliestTrackedDate = earliest,
            canStepEarlier = window.canStepEarlier(earliest),
        )
    }

    private val resolvedWindow: StateFlow<ResolvedAnalyticsWindow> = combine(
        selectedWindow,
        earliestTrackedDate,
    ) { window, earliest -> resolveWindow(window, earliest) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = resolveWindow(selectedWindow.value, null),
        )

    /** Select a new length while keeping the current anchor period. */
    fun selectWindowLength(length: AnalyticsWindowLength) {
        selectedWindow.update { it.copy(length = length) }
    }

    /** Move the selected anchor to the immediately preceding reachable period. */
    fun stepAnchorEarlier() {
        val earliest = earliestTrackedDate.value ?: return
        selectedWindow.update { current ->
            current.stepEarlier().takeIf { candidate ->
                candidate.resolve().endInclusive >= earliest
            } ?: current
        }
    }

    // Re-query every windowed source when the same resolved bounds change. Room keeps the reads
    // indexed in SQL; no wider history is fetched and pruned in memory.
    private val inputs: Flow<AnalyticsInputs> = resolvedWindow.flatMapLatest { resolved ->
        combine(
            sessionRepository.sessionsBetween(
                startInclusiveMillis = resolved.epochBounds.startInclusiveMillis,
                endExclusiveMillis = resolved.epochBounds.endExclusiveMillis,
            ),
            sessionRepository.minutesByGameBetween(
                startInclusiveMillis = resolved.epochBounds.startInclusiveMillis,
                endExclusiveMillis = resolved.epochBounds.endExclusiveMillis,
            ),
            gameRepository.library,
            profileRepository.dailyProgress,
            profileRepository.profile,
        ) { sessions, minutesByGame, library, dailyProgress, profile ->
            AnalyticsInputs(sessions, minutesByGame, library, dailyProgress, profile)
        }
    }

    val uiState: StateFlow<AnalyticsUiState> = combine(
        inputs,
        resolvedWindow,
        settings.ruleConfig,
        credentials.credentialsStateFlow,
        achievementRepository.unlockedRarityDetails,
    ) { inputs, resolved, ruleConfig, credState, rarityDetails ->
        val dates = resolved.bounds.dates()
        val gamesById = inputs.library.associateBy { it.appId }
        // Session start date is the canonical attribution shared with sync daily progress and
        // History, including for sessions that cross local midnight.
        val sessionsByDate = inputs.sessions.groupBy { localDate(it.startAt, time.zone()) }
        val minutesByDate = sessionsByDate.mapValues { (_, daySessions) -> daySessions.sumOf { it.minutes } }
        val dailyMinutes = dates.map { date ->
            AnalyticsDay(date = date, minutes = minutesByDate[date] ?: 0)
        }

        val progressByDate = inputs.dailyProgress.associateBy {
            runCatching { LocalDate.parse(it.date) }.getOrNull()
        }
        val questMetDaysCount = dates.count { date -> progressByDate[date]?.questMet == true }

        val topGames = joinGameMinutes(inputs.minutesByGame, gamesById)
            .take(TOP_GAMES_LIMIT)
        val gamesByDate = sessionsByDate
            .mapValues { (_, daySessions) ->
                val minutesByDayGame = daySessions.groupBy { it.appId }
                    .mapValues { (_, gameSessions) -> gameSessions.sumOf { it.minutes } }
                joinGameMinutes(minutesByDayGame, gamesById)
            }

        val rarityBreakdown = rarityDetails.fold(RarityBreakdown()) { acc, achievement ->
            when (Gamification.tierFor(achievement.rarityPercent)) {
                RarityTier.COMMON -> acc.copy(common = acc.common + 1)
                RarityTier.UNCOMMON -> acc.copy(uncommon = acc.uncommon + 1)
                RarityTier.RARE -> acc.copy(rare = acc.rare + 1)
                RarityTier.EPIC -> acc.copy(epic = acc.epic + 1)
                RarityTier.LEGENDARY -> acc.copy(legendary = acc.legendary + 1)
            }
        }
        val rarestAchievements = rarityDetails
            .sortedWith(compareBy<UnlockedAchievementRarity> { it.rarityPercent }.thenBy { it.achievementName })
            .take(RAREST_ACHIEVEMENTS_LIMIT)
            .map { achievement ->
                AnalyticsRarityAchievement(
                    appId = achievement.appId,
                    gameName = achievement.gameName,
                    achievementName = achievement.achievementName,
                    rarityPercent = achievement.rarityPercent,
                    tier = Gamification.tierFor(achievement.rarityPercent),
                )
            }

        val sessionInsights = if (inputs.sessions.isEmpty()) {
            SessionInsights()
        } else {
            SessionInsights(
                sessionCount = inputs.sessions.size,
                averageMinutes = inputs.sessions.sumOf { it.minutes } / inputs.sessions.size,
                longestMinutes = inputs.sessions.maxOf { it.minutes },
            )
        }

        val timeOfDayPattern = inputs.sessions.fold(TimeOfDayPattern()) { acc, session ->
            val hour = Instant.ofEpochMilli(session.startAt).atZone(time.zone()).hour
            when (hour) {
                in 5..11 -> acc.copy(morningMinutes = acc.morningMinutes + session.minutes)
                in 12..16 -> acc.copy(afternoonMinutes = acc.afternoonMinutes + session.minutes)
                in 17..20 -> acc.copy(eveningMinutes = acc.eveningMinutes + session.minutes)
                else -> acc.copy(nightMinutes = acc.nightMinutes + session.minutes)
            }
        }

        AnalyticsUiState(
            loading = false,
            configured = credState is CredentialsState.Configured,
            window = resolved.window,
            windowBounds = resolved.bounds,
            earliestTrackedDate = resolved.earliestTrackedDate,
            canStepEarlier = resolved.canStepEarlier,
            dailyMinutes = dailyMinutes,
            questThreshold = ruleConfig.questThresholdMin,
            currentStreak = inputs.profile?.currentStreak ?: 0,
            longestStreak = inputs.profile?.longestStreak ?: 0,
            questMetDaysCount = questMetDaysCount,
            topGames = topGames,
            rarityBreakdown = rarityBreakdown,
            rarestAchievements = rarestAchievements,
            sessionInsights = sessionInsights,
            timeOfDayPattern = timeOfDayPattern,
            gamesByDate = gamesByDate,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AnalyticsUiState(
            window = selectedWindow.value,
            windowBounds = selectedWindow.value.resolve(),
        ),
    )

    private companion object {
        const val TOP_GAMES_LIMIT = 5
        const val RAREST_ACHIEVEMENTS_LIMIT = 20
    }
}

private fun joinGameMinutes(
    minutesByGame: Map<Long, Int>,
    gamesById: Map<Long, LibraryGame>,
): List<AnalyticsGame> = minutesByGame.entries
    .mapNotNull { (appId, minutes) ->
        if (minutes <= 0) return@mapNotNull null
        val game = gamesById[appId]
        AnalyticsGame(
            appId = appId,
            name = game?.name ?: "App $appId",
            iconUrl = game?.iconUrl.orEmpty(),
            minutes = minutes,
        )
    }
    .sortedWith(compareByDescending<AnalyticsGame> { it.minutes }.thenBy { it.name })

private fun localDate(epochMillis: Long, zone: ZoneId): LocalDate =
    Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
