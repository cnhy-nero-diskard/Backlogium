package com.example.backlogium.ui.history

import com.example.backlogium.data.repo.AchievementUnlockSummary
import com.example.backlogium.data.repo.DayProgress
import com.example.backlogium.data.repo.LibraryGame
import com.example.backlogium.data.repo.PlaySession
import com.example.backlogium.data.repo.SessionCloudContribution
import com.example.backlogium.data.repo.ContributionState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Achievement thumbnails cap per day header before collapsing into a "+N" badge. */
const val HISTORY_ACHIEVEMENT_CAP = 5

/** Game thumbnails use the same compact five-item cap as the achievement row. */
const val HISTORY_GAME_THUMBNAIL_CAP = HISTORY_ACHIEVEMENT_CAP

/** One session as the History tree renders it — a leaf under a [HistoryGameGroup]. */
data class HistorySessionUi(
    val id: Long,
    val startAt: Long,
    val minutes: Int,
    val open: Boolean,
    val cloudContribution: SessionCloudContribution = SessionCloudContribution(),
)

internal fun SessionCloudContribution.hasRecordedContribution(): Boolean =
    recoveredSharedPlay == ContributionState.FULL || recoveredSharedPlay == ContributionState.PARTIAL ||
        timingInformedSteamPlay == ContributionState.FULL || timingInformedSteamPlay == ContributionState.PARTIAL

data class HistoryContribution(val date: String, val game: HistoryGameGroup, val session: HistorySessionUi)

data class HistoryCloudActivity(
    val recovered: List<HistoryContribution>,
    val timed: List<HistoryContribution>,
) {
    val hasContributions: Boolean get() = recovered.isNotEmpty() || timed.isNotEmpty()
}

/** A dual-fact session appears in both lists; only already visible games are included. */
fun historyCloudActivity(days: List<HistoryDayGroup>, readerConfigured: Boolean): HistoryCloudActivity {
    if (!readerConfigured) return HistoryCloudActivity(emptyList(), emptyList())
    val visible = days.flatMap { day -> day.games.flatMap { game ->
        game.sessions.map { session -> HistoryContribution(day.date, game, session) }
    } }
    return HistoryCloudActivity(
        recovered = visible.filter { it.session.cloudContribution.recoveredSharedPlay == ContributionState.FULL ||
            it.session.cloudContribution.recoveredSharedPlay == ContributionState.PARTIAL },
        timed = visible.filter { it.session.cloudContribution.timingInformedSteamPlay == ContributionState.FULL ||
            it.session.cloudContribution.timingInformedSteamPlay == ContributionState.PARTIAL },
    )
}

/** A game played on a given day, holding that day's sessions for that game. */
data class HistoryGameGroup(
    val appId: Long,
    val name: String,
    val iconUrl: String,
    val minutesPlayed: Int,
    val sessions: List<HistorySessionUi>,
)

/** A day header's achievement row: up to [HISTORY_ACHIEVEMENT_CAP] icons, plus any overflow count. */
data class HistoryAchievements(
    val iconUrls: List<String?>,
    val overflowCount: Int,
)

/** A day header's game thumbnails, capped before collapsing into a "+N" badge. */
data class HistoryGameThumbnails(
    val games: List<HistoryGameGroup> = emptyList(),
    val overflowCount: Int = 0,
)

/** One day of history: its games (and their sessions), its totals, and its achievement row. */
data class HistoryDayGroup(
    val date: String,
    val minutesPlayed: Int,
    val goalMinutesPlayed: Int,
    val questMet: Boolean,
    val games: List<HistoryGameGroup>,
    val gameThumbnails: HistoryGameThumbnails = HistoryGameThumbnails(),
    val achievements: HistoryAchievements,
)

/** The two presentation groups used to frame the History timeline. */
data class HistorySections(
    val today: HistoryDayGroup?,
    val earlier: List<HistoryDayGroup>,
)

/**
 * Splits the already date-ordered timeline without changing its contents or order. A missing
 * current day is meaningful: the remaining rows are still explicitly framed as earlier history.
 */
fun historySections(days: List<HistoryDayGroup>, today: String): HistorySections = HistorySections(
    today = days.firstOrNull { it.date == today },
    earlier = days.filterNot { it.date == today },
)

/** Render an ISO local-date key without changing the date used for attribution or grouping. */
fun formatHistoryDate(date: String, locale: Locale = Locale.getDefault()): String = runCatching {
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        .withLocale(locale)
        .format(LocalDate.parse(date))
}.getOrDefault(date)

/** Local-day epoch bounds shared by History and Analytics; the end is exclusive. */
data class HistoryWindowBounds(
    val startInclusiveMillis: Long,
    val endExclusiveMillis: Long,
)

/**
 * Joins sessions, the library, per-day progress, and achievement unlocks into the day → game →
 * session tree the History screen renders (regroup-history design).
 *
 * Pure and zone-parameterized so it is fully unit-testable without Android/Room: callers pass the
 * device zone in production and a fixed zone in tests.
 *
 * Key decisions this function encodes:
 * - A session belongs to the **local date of its `startAt`** — a midnight-crossing session sits
 *   entirely on the day it began, never split across two days.
 * - A day header's total, and its Focus-games total, are both the **sum of the sessions grouped
 *   beneath it** (the latter filtered to games tagged [LibraryGame.isGoal]) — not the stored
 *   [DayProgress] counters. Daily progress is credited to the same session-start date by the sync
 *   worker; recomputing both totals from the sessions keeps them internally consistent with each
 *   other and with what the breakdown actually shows. [DayProgress] still supplies `questMet`,
 *   which remains authoritative for streaks.
 * - Days with [DayProgress] but no sessions still produce a (session-less) day group.
 * - Achievement unlocks are matched to a day by the local date of `unlockedAt`, across every game
 *   — not just the games played that day — since an achievement can unlock retroactively or from
 *   idle progress.
 */
fun groupHistory(
    sessions: List<PlaySession>,
    games: List<LibraryGame>,
    dailyProgress: List<DayProgress>,
    achievementUnlocks: List<AchievementUnlockSummary>,
    zone: ZoneId = ZoneId.systemDefault(),
): List<HistoryDayGroup> {
    val nameById = games.associate { it.appId to it.name }
    val iconById = games.associate { it.appId to it.iconUrl }
    val goalAppIds = games.filter { it.isGoal }.map { it.appId }.toSet()
    val progressByDate = dailyProgress.associateBy { it.date }
    val sessionsByDate = sessions.groupBy { localDate(it.startAt, zone) }
    val achievementsByDate = achievementUnlocks.groupBy { localDate(it.unlockedAt, zone) }

    val allDates = (sessionsByDate.keys + progressByDate.keys).distinct().sortedDescending()

    return allDates.map { date ->
        val daySessions = sessionsByDate[date].orEmpty()
        val progress = progressByDate[date]
        val unlocksForDay = achievementsByDate[date].orEmpty().sortedBy { it.unlockedAt }

        val gameGroups = daySessions.groupBy { it.appId }
            .map { (appId, sessionsForGame) ->
                HistoryGameGroup(
                    appId = appId,
                    name = nameById[appId] ?: "App $appId",
                    iconUrl = iconById[appId] ?: "",
                    minutesPlayed = sessionsForGame.sumOf { it.minutes },
                    sessions = sessionsForGame.sortedBy { it.startAt }.map {
                        HistorySessionUi(
                            id = it.id,
                            startAt = it.startAt,
                            minutes = it.minutes,
                            open = it.open,
                            cloudContribution = it.cloudContribution,
                        )
                    },
                )
            }
            .sortedWith(
                compareByDescending<HistoryGameGroup> { it.minutesPlayed }
                    .thenBy { it.name }
                    .thenBy { it.appId },
            )

        HistoryDayGroup(
            date = date,
            minutesPlayed = daySessions.sumOf { it.minutes },
            goalMinutesPlayed = daySessions.filter { it.appId in goalAppIds }.sumOf { it.minutes },
            questMet = progress?.questMet ?: false,
            games = gameGroups,
            gameThumbnails = HistoryGameThumbnails(
                games = gameGroups.take(HISTORY_GAME_THUMBNAIL_CAP),
                overflowCount = (gameGroups.size - HISTORY_GAME_THUMBNAIL_CAP).coerceAtLeast(0),
            ),
            achievements = HistoryAchievements(
                iconUrls = unlocksForDay.take(HISTORY_ACHIEVEMENT_CAP).map { it.iconUrl },
                overflowCount = (unlocksForDay.size - HISTORY_ACHIEVEMENT_CAP).coerceAtLeast(0),
            ),
        )
    }
}

private fun localDate(epochMillis: Long, zone: ZoneId): String =
    Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate().toString()

/**
 * Epoch millis for the start of the local day [windowDays] ago (inclusive) — the History screen's
 * session/achievement query cutoff.
 *
 * Computed at the local day boundary rather than `now - windowDays * 24h`: the latter anchors to
 * the current time-of-day, so the oldest day in the window would be partial (e.g. a 30-day window
 * opened at 3pm would only include play after 3pm on day 30). A day-boundary cutoff makes every day
 * in the window whole, including the oldest.
 */
fun historyWindowCutoffMillis(windowDays: Int, today: LocalDate, zone: ZoneId): Long {
    require(windowDays > 0) { "windowDays must be positive, was $windowDays" }
    return historyWindowBounds(
        start = today.minusDays((windowDays - 1).toLong()),
        endInclusive = today,
        zone = zone,
    ).startInclusiveMillis
}

/**
 * Epoch bounds for complete local days from [start] through [endInclusive]. The exclusive upper
 * bound keeps a session starting at the next local midnight out of the selected window.
 */
fun historyWindowBounds(
    start: LocalDate,
    endInclusive: LocalDate,
    zone: ZoneId,
): HistoryWindowBounds {
    require(!endInclusive.isBefore(start)) {
        "endInclusive must not be before start"
    }
    return HistoryWindowBounds(
        startInclusiveMillis = start.atStartOfDay(zone).toInstant().toEpochMilli(),
        endExclusiveMillis = endInclusive.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
    )
}
