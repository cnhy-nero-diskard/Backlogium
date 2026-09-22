package com.example.backlogium.ui.analytics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.backlogium.data.repo.AchievementRepository
import com.example.backlogium.data.repo.CredentialsRepository
import com.example.backlogium.data.repo.CredentialsState
import com.example.backlogium.data.repo.DayProgress
import com.example.backlogium.data.repo.GameRepository
import com.example.backlogium.data.repo.LibraryGame
import com.example.backlogium.domain.GameSource
import com.example.backlogium.data.repo.PlaySession
import com.example.backlogium.data.repo.PlayerStats
import com.example.backlogium.data.repo.ProfileRepository
import com.example.backlogium.data.repo.SessionRepository
import com.example.backlogium.data.repo.SettingsRepository
import com.example.backlogium.data.repo.UnlockedAchievementRarity
import com.example.backlogium.domain.CurrentDateProvider
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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
    /** Steam header art, carried for the overview hero; blank when unknown. */
    val headerUrl: String = "",
    /** Steam portrait hero capsule, carried for the overview hero; blank when unknown. */
    val heroCapsuleUrl: String = "",
    /**
     * Played through Family Sharing. Its minutes are in every total on this screen exactly like an
     * owned game's, and this is what lets the reader tell the two apart — the totals are honest
     * either way, but a shared game's minutes are what the app observed rather than a Steam total.
     */
    val isFamilyShared: Boolean = false,
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
enum class TimeOfDayBucket {
    MORNING,
    AFTERNOON,
    EVENING,
    NIGHT,
}

data class TimeOfDayPattern(
    val morningMinutes: Int = 0,   // 5:00-11:59
    val afternoonMinutes: Int = 0, // 12:00-16:59
    val eveningMinutes: Int = 0,   // 17:00-20:59
    val nightMinutes: Int = 0,     // 21:00-4:59
) {
    /** The bucket with the most minutes, or null if all are zero. */
    val peakBucket: TimeOfDayBucket?
        get() = listOf(
            TimeOfDayBucket.MORNING to morningMinutes,
            TimeOfDayBucket.AFTERNOON to afternoonMinutes,
            TimeOfDayBucket.EVENING to eveningMinutes,
            TimeOfDayBucket.NIGHT to nightMinutes,
        ).filter { it.second > 0 }.maxByOrNull { it.second }?.first
}

/** A factual first sentence for the selected Analytics window. */
sealed interface AnalyticsHeadline {
    data object NoData : AnalyticsHeadline

    data class Compared(
        val currentMinutes: Int,
        val previousMinutes: Int,
    ) : AnalyticsHeadline {
        val changeMinutes: Int get() = currentMinutes - previousMinutes
    }

    data class LeadingGame(
        val gameName: String,
        val minutes: Int,
    ) : AnalyticsHeadline

    data class ActiveDays(
        val activeDays: Int,
        val totalDays: Int,
    ) : AnalyticsHeadline
}

/** Inputs kept deliberately small so headline priority can be tested without Android or Room. */
fun deriveAnalyticsHeadline(
    totalMinutes: Int,
    activeDays: Int,
    totalDays: Int,
    leadingGame: AnalyticsGame?,
    previousMinutes: Int? = null,
): AnalyticsHeadline {
    if (totalMinutes <= 0 && leadingGame == null) return AnalyticsHeadline.NoData
    if (totalMinutes > 0 && previousMinutes != null && previousMinutes > 0) {
        return AnalyticsHeadline.Compared(
            currentMinutes = totalMinutes,
            previousMinutes = previousMinutes,
        )
    }
    if (leadingGame != null) {
        return AnalyticsHeadline.LeadingGame(
            gameName = leadingGame.name,
            minutes = leadingGame.minutes,
        )
    }
    return AnalyticsHeadline.ActiveDays(
        activeDays = activeDays,
        totalDays = totalDays,
    )
}

/** Clamp every chart selection path to the same represented-day index. */
fun analyticsDaySelectionIndex(dayCount: Int, requestedIndex: Int): Int? =
    if (dayCount == 0) null else requestedIndex.coerceIn(0, dayCount - 1)

/** Select the last active day, or the final represented day when the window has no activity. */
fun initialAnalyticsDaySelection(days: List<AnalyticsDay>): Int? =
    analyticsDaySelectionIndex(
        dayCount = days.size,
        requestedIndex = days.indexOfLast { it.minutes > 0 }.takeIf { it >= 0 } ?: days.lastIndex,
    )

/** Move a chart selection by one or more represented days, returning null for an empty chart. */
fun stepAnalyticsDaySelection(currentIndex: Int, dayCount: Int, delta: Int): Int? =
    analyticsDaySelectionIndex(dayCount, currentIndex + delta)

data class AnalyticsUiState(
    val loading: Boolean = true,
    /** True while the selected period is being recomputed from a new bounded query. */
    val updating: Boolean = false,
    val configured: Boolean = true,
    val window: AnalyticsWindow = INITIAL_WINDOW,
    /** The full calendar period identity used for the period label and navigation. */
    val windowBounds: AnalyticsWindowBounds = INITIAL_WINDOW.resolve(),
    val earliestTrackedDate: LocalDate? = null,
    val canStepEarlier: Boolean = false,
    val canStepLater: Boolean = false,
    val isCurrentWindow: Boolean = true,
    val headline: AnalyticsHeadline = AnalyticsHeadline.NoData,
    /** One entry per represented day through today, including zero-minute days, oldest first. */
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
    /**
     * Of the window's tracked minutes, how many came from family-shared games. Zero for the
     * overwhelmingly common case of a library with none, where the figure is not shown at all
     * rather than stated as a zero.
     */
    val familySharedMinutes: Int = 0,
) {
    /** True when there is at least one tracked minute in the selected window. */
    val hasData: Boolean
        get() = dailyMinutes.any { it.minutes > 0 } || topGames.isNotEmpty()

    /**
     * Days actually represented as activity: the elapsed day count through today for the current
     * calendar month/year, otherwise the full period. Falls back to the period identity when no
     * represented days are loaded yet.
     */
    val representedDayCount: Int
        get() = dailyMinutes.size.takeIf { it > 0 } ?: windowBounds.dayCount
}

private data class AnalyticsInputs(
    val window: AnalyticsWindow,
    val sessions: List<PlaySession>,
    val minutesByGame: Map<Long, Int>,
    val previousMinutesByGame: Map<Long, Int>,
    val library: List<LibraryGame>,
    val dailyProgress: List<DayProgress>,
    val profile: PlayerStats?,
)

/** A selected window remembers whether it should continue following the current period. */
internal data class AnalyticsWindowSelection(
    val window: AnalyticsWindow,
    val followsCurrent: Boolean,
)

internal fun AnalyticsWindowSelection.forDate(today: LocalDate): AnalyticsWindow =
    if (followsCurrent) window.copy(anchor = today) else window

internal fun AnalyticsWindowSelection.stepEarlierFrom(
    effectiveWindow: AnalyticsWindow,
    earliestTrackedDate: LocalDate,
): AnalyticsWindowSelection =
    effectiveWindow.stepEarlier().takeIf { candidate ->
        candidate.resolve().endInclusive >= earliestTrackedDate
    }?.let { candidate ->
        AnalyticsWindowSelection(window = candidate, followsCurrent = false)
    } ?: this

private data class DatedAnalyticsWindow(
    val window: AnalyticsWindow,
    val today: LocalDate,
)

private data class ResolvedAnalyticsWindow(
    val window: AnalyticsWindow,
    val today: LocalDate,
    val bounds: AnalyticsWindowBounds,
    val epochBounds: HistoryWindowBounds,
    val earliestTrackedDate: LocalDate?,
    val canStepEarlier: Boolean,
    val canStepLater: Boolean,
    val isCurrentWindow: Boolean,
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
    private val currentDate: CurrentDateProvider,
    private val time: TimeProvider,
) : ViewModel() {

    private val initialToday = time.today()
    private val selectedWindow = MutableStateFlow(
        AnalyticsWindowSelection(
            window = AnalyticsWindow(
                anchor = initialToday,
                length = AnalyticsWindowLength.THIRTY_DAYS,
            ),
            followsCurrent = true,
        ),
    )

    private val today: StateFlow<LocalDate> = currentDate.currentDate
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = initialToday,
        )

    private val datedWindow: StateFlow<DatedAnalyticsWindow> = combine(
        selectedWindow,
        today,
    ) { selection, today ->
        DatedAnalyticsWindow(
            window = selection.forDate(today),
            today = today,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DatedAnalyticsWindow(
            window = selectedWindow.value.window,
            today = today.value,
        ),
    )

    /** The current selection, exposed separately for callers that need controls outside uiState. */
    val window: StateFlow<AnalyticsWindow> = datedWindow
        .map { it.window }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = datedWindow.value.window,
        )

    private val earliestTrackedDate: StateFlow<LocalDate?> = sessionRepository.earliestSessionStart
        .map { startAt -> startAt?.let { localDate(it, time.zone()) } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null,
        )

    private fun resolveWindow(
        window: AnalyticsWindow,
        earliest: LocalDate?,
        today: LocalDate,
    ): ResolvedAnalyticsWindow {
        val bounds = window.resolve()
        return ResolvedAnalyticsWindow(
            window = window,
            today = today,
            bounds = bounds,
            epochBounds = historyWindowBounds(
                start = bounds.start,
                endInclusive = bounds.endInclusive,
                zone = time.zone(),
            ),
            earliestTrackedDate = earliest,
            canStepEarlier = window.canStepEarlier(earliest),
            canStepLater = window.canStepLater(today),
            isCurrentWindow = window.isCurrentWindow(today),
        )
    }

    private val resolvedWindow: StateFlow<ResolvedAnalyticsWindow> = combine(
        datedWindow,
        earliestTrackedDate,
    ) { dated, earliest -> resolveWindow(dated.window, earliest, dated.today) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = resolveWindow(datedWindow.value.window, null, datedWindow.value.today),
        )

    /** Select a new length while keeping the current anchor period. */
    fun selectWindowLength(length: AnalyticsWindowLength) {
        val dated = datedWindow.value
        val window = dated.window.copy(length = length)
        selectedWindow.update {
            AnalyticsWindowSelection(
                window = window,
                followsCurrent = window.isCurrentWindow(dated.today),
            )
        }
    }

    /** Move the selected anchor to the immediately preceding reachable period. */
    fun stepAnchorEarlier() {
        val earliest = earliestTrackedDate.value ?: return
        val dated = datedWindow.value
        selectedWindow.update { current ->
            current.stepEarlierFrom(
                effectiveWindow = dated.window,
                earliestTrackedDate = earliest,
            )
        }
    }

    /** Move the selected anchor to the immediately following period when it remains bounded by today. */
    fun stepAnchorLater() {
        val dated = datedWindow.value
        selectedWindow.update { selection ->
            val current = dated.window
            current.stepLater().takeIf { candidate ->
                candidate.resolve().endInclusive <= AnalyticsWindow(dated.today, current.length)
                    .resolve().endInclusive
            }?.let { candidate ->
                AnalyticsWindowSelection(
                    window = candidate,
                    followsCurrent = candidate.isCurrentWindow(dated.today),
                )
            } ?: selection
        }
    }

    /** Return to the current period while preserving the selected length and chart display mode. */
    fun returnToCurrentWindow() {
        val today = today.value
        selectedWindow.update { current ->
            AnalyticsWindowSelection(
                window = current.window.copy(anchor = today),
                followsCurrent = true,
            )
        }
    }

    // Re-query every windowed source when the same resolved bounds change. Room keeps the reads
    // indexed in SQL; no wider history is fetched and pruned in memory.
    private val inputs: Flow<AnalyticsInputs> = resolvedWindow.flatMapLatest { resolved ->
        // A current calendar month/year is only elapsed-to-date, so compare the same elapsed
        // subrange in the prior period rather than a full month/year against a partial one.
        // When the elapsed day-count does not fit in the prior period, or tracking began inside
        // either range, omit the previous-period headline and fall back.
        val comparisonBounds = resolved.window.comparablePreviousBoundsIfFullyObserved(
            today = resolved.today,
            earliestTrackedDate = resolved.earliestTrackedDate,
        )
        val previousMinutesFlow: Flow<Map<Long, Int>> = if (comparisonBounds == null) {
            flowOf(emptyMap())
        } else {
            val previousBounds = historyWindowBounds(
                start = comparisonBounds.start,
                endInclusive = comparisonBounds.endInclusive,
                zone = time.zone(),
            )
            sessionRepository.minutesByGameBetween(
                startInclusiveMillis = previousBounds.startInclusiveMillis,
                endExclusiveMillis = previousBounds.endExclusiveMillis,
            )
        }
        combine(
            combine(
                sessionRepository.sessionsBetween(
                    startInclusiveMillis = resolved.epochBounds.startInclusiveMillis,
                    endExclusiveMillis = resolved.epochBounds.endExclusiveMillis,
                ),
                sessionRepository.minutesByGameBetween(
                    startInclusiveMillis = resolved.epochBounds.startInclusiveMillis,
                    endExclusiveMillis = resolved.epochBounds.endExclusiveMillis,
                ),
                previousMinutesFlow,
            ) { sessions, minutesByGame, previousMinutesByGame ->
                Triple(sessions, minutesByGame, previousMinutesByGame)
            },
            gameRepository.library,
            profileRepository.dailyProgress,
            profileRepository.profile,
        ) { current, library, dailyProgress, profile ->
            AnalyticsInputs(
                window = resolved.window,
                sessions = current.first,
                minutesByGame = current.second,
                previousMinutesByGame = current.third,
                library = library,
                dailyProgress = dailyProgress,
                profile = profile,
            )
        }
    }

    val uiState: StateFlow<AnalyticsUiState> = combine(
        inputs,
        resolvedWindow,
        settings.ruleConfig,
        credentials.credentialsStateFlow,
        achievementRepository.unlockedRarityDetails,
    ) { inputs, resolved, ruleConfig, credState, rarityDetails ->
        // The period identity stays the full calendar period for labels and navigation, while
        // activity is represented only within observed history through today.
        val periodBounds = inputs.window.resolve()
        val dataBounds = inputs.window.resolveActivityBounds(
            today = resolved.today,
            earliestTrackedDate = resolved.earliestTrackedDate,
        )
        val dates = dataBounds.dates()
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
        val totalMinutes = dailyMinutes.sumOf { it.minutes }
        // Shared games are counted in the window's totals; this is the slice of them, so the
        // contribution can be told apart rather than silently folded in.
        val familySharedMinutes = inputs.minutesByGame
            .filter { (appId, _) -> gamesById[appId]?.source == GameSource.FAMILY_SHARED }
            .values
            .sum()
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

        val headline = deriveAnalyticsHeadline(
            totalMinutes = totalMinutes,
            activeDays = dailyMinutes.count { it.minutes > 0 },
            totalDays = dataBounds.dayCount,
            leadingGame = topGames.firstOrNull(),
            previousMinutes = inputs.previousMinutesByGame.values.sum().takeIf { it > 0 },
        )
        // The figures below all derive from inputs.window. Keep the visible period metadata
        // on that same snapshot until the new bounded query lands, so old figures are never
        // relabelled as the newly selected period. Navigation affordances still follow the
        // selection via resolved, while loading stays false once a snapshot exists so the
        // empty state reflects the displayed snapshot rather than a zero-value stack.
        val updating = inputs.window != resolved.window

        AnalyticsUiState(
            loading = false,
            updating = updating,
            configured = credState is CredentialsState.Configured,
            window = inputs.window,
            windowBounds = periodBounds,
            earliestTrackedDate = resolved.earliestTrackedDate,
            canStepEarlier = resolved.canStepEarlier,
            canStepLater = resolved.canStepLater,
            isCurrentWindow = resolved.isCurrentWindow,
            headline = headline,
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
            familySharedMinutes = familySharedMinutes,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AnalyticsUiState(
            window = datedWindow.value.window,
            windowBounds = datedWindow.value.window.resolve(),
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
            headerUrl = game?.headerUrl.orEmpty(),
            heroCapsuleUrl = game?.heroCapsuleUrl.orEmpty(),
            isFamilyShared = game?.source == GameSource.FAMILY_SHARED,
        )
    }
    .sortedWith(compareByDescending<AnalyticsGame> { it.minutes }.thenBy { it.name })

private fun localDate(epochMillis: Long, zone: ZoneId): LocalDate =
    Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
