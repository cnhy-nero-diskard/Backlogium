package com.example.backlogium.ui.analytics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.backlogium.data.repo.AchievementRepository
import com.example.backlogium.data.repo.CredentialsRepository
import com.example.backlogium.data.repo.CredentialsState
import com.example.backlogium.data.repo.GameRepository
import com.example.backlogium.data.repo.PlaySession
import com.example.backlogium.data.repo.ProfileRepository
import com.example.backlogium.data.repo.SessionRepository
import com.example.backlogium.data.repo.SettingsRepository
import com.example.backlogium.domain.TimeProvider
import com.example.backlogium.gamification.Gamification
import com.example.backlogium.gamification.RarityTier
import com.example.backlogium.ui.history.historyWindowCutoffMillis
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/** One bar in the daily playtime chart: a local date and that day's tracked minutes. */
data class AnalyticsDay(val date: LocalDate, val minutes: Int)

/** One row in the most-played-games list: a game's identity and its tracked minutes in the window. */
data class AnalyticsGame(
    val appId: Long,
    val name: String,
    val iconUrl: String,
    val minutes: Int,
)

/** Count of unlocked achievements per rarity tier — the "hidden" rarity profile. */
data class RarityBreakdown(
    val common: Int = 0,
    val uncommon: Int = 0,
    val rare: Int = 0,
    val epic: Int = 0,
    val legendary: Int = 0,
) {
    val total: Int get() = common + uncommon + rare + epic + legendary
}

/** Aggregated session shape over the window — count, average, and longest session. */
data class SessionInsights(
    val sessionCount: Int = 0,
    val averageMinutes: Int = 0,
    val longestMinutes: Int = 0,
)

/** When the player tends to play, bucketed by the local hour of session start. */
data class TimeOfDayPattern(
    val morningMinutes: Int = 0,   // 5:00–11:59
    val afternoonMinutes: Int = 0, // 12:00–16:59
    val eveningMinutes: Int = 0,   // 17:00–20:59
    val nightMinutes: Int = 0,     // 21:00–4:59
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
    /** One entry per day in the 30-day window, including zero-minute days, oldest first. */
    val dailyMinutes: List<AnalyticsDay> = emptyList(),
    /** The configured daily-quest threshold, drawn as a reference line on the chart. */
    val questThreshold: Int = 30,
    val currentStreak: Int = 0,
    val longestStreak: Int = 0,
    /** Count of quest-met days within the 30-day window. */
    val questMetDaysCount: Int = 0,
    /** Up to five games ranked by tracked minutes in the window, descending. */
    val topGames: List<AnalyticsGame> = emptyList(),
    /** All-time rarity tier breakdown of unlocked achievements. */
    val rarityBreakdown: RarityBreakdown = RarityBreakdown(),
    /** Session shape over the 30-day window. */
    val sessionInsights: SessionInsights = SessionInsights(),
    /** Time-of-day play pattern over the 30-day window. */
    val timeOfDayPattern: TimeOfDayPattern = TimeOfDayPattern(),
) {
    /** True when there is at least one tracked minute in the window — gates the empty state. */
    val hasData: Boolean
        get() = dailyMinutes.any { it.minutes > 0 } || topGames.isNotEmpty()
}

/** Intermediate bundle for the first combine stage (5 flows is the direct-combine ceiling). */
private data class AnalyticsInputs(
    val sessions: List<PlaySession>,
    val minutesByGame: Map<Long, Int>,
    val library: List<com.example.backlogium.data.repo.LibraryGame>,
    val dailyProgress: List<com.example.backlogium.data.repo.DayProgress>,
    val profile: com.example.backlogium.data.repo.PlayerStats?,
)

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

    private val windowDays: Int = WINDOW_DAYS

    // combine() supports up to 5 flows directly; 8 inputs are split into two stages.
    private val inputs = combine(
        sessionRepository.sessionsSince(cutoffMillis()),
        sessionRepository.minutesByGameSince(cutoffMillis()),
        gameRepository.library,
        profileRepository.dailyProgress,
        profileRepository.profile,
    ) { sessions, minutesByGame, library, dailyProgress, profile ->
        AnalyticsInputs(sessions, minutesByGame, library, dailyProgress, profile)
    }

    val uiState: StateFlow<AnalyticsUiState> = combine(
        inputs,
        settings.ruleConfig,
        credentials.credentialsStateFlow,
        achievementRepository.unlockedRarityByGame,
    ) { inputs, ruleConfig, credState, unlockedRarityByGame ->
        val zone = time.zone()
        val today = time.today()
        val windowStart = today.minusDays((windowDays - 1).toLong())

        // Daily minutes: one entry per day in the window (oldest first), filling zero-minute days
        // so the chart renders a continuous axis rather than gaps.
        val minutesByDate = inputs.sessions.groupBy { localDate(it.startAt, zone) }
            .mapValues { (_, daySessions) -> daySessions.sumOf { it.minutes } }
        val dailyMinutes = (0 until windowDays).map { offset ->
            val date = windowStart.plusDays(offset.toLong())
            AnalyticsDay(date = date, minutes = minutesByDate[date] ?: 0)
        }

        // Quest-met days within the window, from the authoritative DailyProgress rows.
        val progressByDate = inputs.dailyProgress.associateBy {
            runCatching { LocalDate.parse(it.date) }.getOrNull()
        }
        val questMetDaysCount = (0 until windowDays).count { offset ->
            val date = windowStart.plusDays(offset.toLong())
            progressByDate[date]?.questMet == true
        }

        // Top games: join per-game minutes to the library for names/icons, take top 5.
        val gamesById = inputs.library.associateBy { it.appId }
        val topGames = inputs.minutesByGame.entries
            .mapNotNull { (appId, minutes) ->
                val game = gamesById[appId] ?: return@mapNotNull null
                AnalyticsGame(
                    appId = appId,
                    name = game.name,
                    iconUrl = game.iconUrl,
                    minutes = minutes,
                )
            }
            .sortedByDescending { it.minutes }
            .take(TOP_GAMES_LIMIT)

        // Rarity breakdown: flatten all unlocked-achievement snapshot percents across every game,
        // tier each with the gamification engine's fixed cut points, and count per tier.
        val rarityBreakdown = unlockedRarityByGame.values
            .flatten()
            .filterNotNull()
            .fold(RarityBreakdown()) { acc, percent ->
                when (Gamification.tierFor(percent)) {
                    RarityTier.COMMON -> acc.copy(common = acc.common + 1)
                    RarityTier.UNCOMMON -> acc.copy(uncommon = acc.uncommon + 1)
                    RarityTier.RARE -> acc.copy(rare = acc.rare + 1)
                    RarityTier.EPIC -> acc.copy(epic = acc.epic + 1)
                    RarityTier.LEGENDARY -> acc.copy(legendary = acc.legendary + 1)
                }
            }

        // Session insights: count, average, longest — over the window's sessions.
        val sessionInsights = if (inputs.sessions.isEmpty()) {
            SessionInsights()
        } else {
            SessionInsights(
                sessionCount = inputs.sessions.size,
                averageMinutes = inputs.sessions.sumOf { it.minutes } / inputs.sessions.size,
                longestMinutes = inputs.sessions.maxOf { it.minutes },
            )
        }

        // Time-of-day pattern: bucket each session's start hour into morning/afternoon/evening/night,
        // summing minutes. Reveals when the player tends to play.
        val timeOfDayPattern = inputs.sessions.fold(TimeOfDayPattern()) { acc, session ->
            val hour = Instant.ofEpochMilli(session.startAt).atZone(zone).hour
            val minutes = session.minutes
            when (hour) {
                in 5..11 -> acc.copy(morningMinutes = acc.morningMinutes + minutes)
                in 12..16 -> acc.copy(afternoonMinutes = acc.afternoonMinutes + minutes)
                in 17..20 -> acc.copy(eveningMinutes = acc.eveningMinutes + minutes)
                else -> acc.copy(nightMinutes = acc.nightMinutes + minutes)
            }
        }

        AnalyticsUiState(
            loading = false,
            configured = credState is CredentialsState.Configured,
            dailyMinutes = dailyMinutes,
            questThreshold = ruleConfig.questThresholdMin,
            currentStreak = inputs.profile?.currentStreak ?: 0,
            longestStreak = inputs.profile?.longestStreak ?: 0,
            questMetDaysCount = questMetDaysCount,
            topGames = topGames,
            rarityBreakdown = rarityBreakdown,
            sessionInsights = sessionInsights,
            timeOfDayPattern = timeOfDayPattern,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AnalyticsUiState(),
    )

    /** Epoch-millis cutoff for the start of the local day `windowDays - 1` days ago. */
    private fun cutoffMillis(): Long =
        historyWindowCutoffMillis(windowDays, time.today(), time.zone())

    private companion object {
        const val WINDOW_DAYS = 30
        const val TOP_GAMES_LIMIT = 5
    }
}

private fun localDate(epochMillis: Long, zone: ZoneId): LocalDate =
    Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
