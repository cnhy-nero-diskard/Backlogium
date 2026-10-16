package com.example.backlogium.ui.history

import com.example.backlogium.data.repo.AchievementUnlockSummary
import com.example.backlogium.data.repo.DayProgress
import com.example.backlogium.data.repo.LibraryGame
import com.example.backlogium.data.repo.PlaySession
import com.example.backlogium.data.repo.SessionCloudContribution
import com.example.backlogium.data.repo.ContributionState
import com.example.backlogium.domain.PlaytimeActivity
import com.example.backlogium.ui.components.RecoveryExplanationKind
import com.example.backlogium.ui.components.RecoveryWindowShare
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

/** A frozen-date share of a timestamped session, displayed separately from unknown-time activity. */
data class HistoryTimedShareUi(
    val sessionId: Long,
    val minutes: Int,
    /** The share's Focus credit under its frozen eligibility. */
    val goalMinutes: Int,
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
    /** Dated unknown-time allocations for this game, with no fabricated clock boundaries. */
    val allocations: List<HistoryAllocationUi> = emptyList(),
    /** Funded timed shares credited to this date, separate from the host session's unlinked remainder. */
    val timedShares: List<HistoryTimedShareUi> = emptyList(),
) {
    /** How much of [minutesPlayed] came from unknown-time allocations. */
    val estimatedMinutes: Int get() = allocations.sumOf { it.minutes }
}

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

/**
 * How a dated unknown-time allocation was derived; drives its honest `!` copy. These leaves
 * deliberately have no start/end or open state — the allocation is a day fact, not a session.
 */
enum class HistoryAllocationMethod {
    SINGLE_DAY_ESTIMATE,
    MULTI_DAY_ESTIMATE,
    KNOWN_TARGETED_DATE,
}

/**
 * The frozen observation window a dated estimate's residual came from. Dates and zone are the
 * allocation's stored (frozen) facts, never re-derived from the device clock at render time.
 */
data class HistoryAllocationWindow(
    val windowStartDate: LocalDate,
    val windowEndDate: LocalDate,
    val zone: String? = null,
    /** The window's recovered residual total (the whole R distributed over the window). */
    val totalMinutes: Int,
)

/** One dated unknown-time allocation as the History tree renders it — a leaf under a game group. */
data class HistoryAllocationUi(
    val placementId: String,
    val appId: Long,
    val date: LocalDate,
    val minutes: Int,
    val method: HistoryAllocationMethod,
    val window: HistoryAllocationWindow = HistoryAllocationWindow(date, date, null, minutes),
    /** Focus eligibility at credit time; falls back to today's Focus set when null. */
    val goalAtCredit: Boolean? = null,
) {
    init {
        require(minutes > 0) { "allocation leaves only carry positive shares" }
    }
}

/** One window's share of a day's (or game's) estimated minutes, for aggregate `!` copy. */
data class HistoryEstimatedShare(
    val window: HistoryAllocationWindow,
    val method: HistoryAllocationMethod,
    val shareMinutes: Int,
)

/**
 * Group unknown-time allocations by their frozen recovery window, summing each window's share on
 * the surface being explained. Deterministic order: earliest window first, then method, then id.
 */
fun estimatedShares(allocations: List<HistoryAllocationUi>): List<HistoryEstimatedShare> {
    if (allocations.isEmpty()) return emptyList()
    val grouped = LinkedHashMap<String, HistoryEstimatedShare>()
    for (allocation in allocations.sortedWith(
        compareBy<HistoryAllocationUi> { it.window.windowStartDate }
            .thenBy { it.date }
            .thenBy { it.window.zone.orEmpty() }
            .thenBy { it.placementId },
    )) {
        val window = allocation.window
        val key = "${window.windowStartDate}|${window.windowEndDate}|${window.zone}|${window.totalMinutes}"
        val existing = grouped[key]
        grouped[key] = if (existing == null) {
            HistoryEstimatedShare(window = window, method = allocation.method, shareMinutes = allocation.minutes)
        } else {
            existing.copy(shareMinutes = existing.shareMinutes + allocation.minutes)
        }
    }
    return grouped.values.toList()
}

/** The component-facing `!` facts for a set of allocations, sharing one window → one paragraph. */
fun recoveryShares(allocations: List<HistoryAllocationUi>): List<RecoveryWindowShare> =
    estimatedShares(allocations).map { share ->
        RecoveryWindowShare(
            kind = when (share.method) {
                HistoryAllocationMethod.SINGLE_DAY_ESTIMATE -> RecoveryExplanationKind.SINGLE_DAY_ESTIMATE
                HistoryAllocationMethod.MULTI_DAY_ESTIMATE -> RecoveryExplanationKind.MULTI_DAY_ESTIMATE
                HistoryAllocationMethod.KNOWN_TARGETED_DATE -> RecoveryExplanationKind.KNOWN_TARGETED_DATE
            },
            windowStart = share.window.windowStartDate,
            windowEnd = share.window.windowEndDate,
            zone = share.window.zone,
            windowTotalMinutes = share.window.totalMinutes,
            shareMinutes = share.shareMinutes,
        )
    }

/** One day of history: its games (and their sessions), its totals, and its achievement row. */
data class HistoryDayGroup(
    val date: String,
    val minutesPlayed: Int,
    val goalMinutesPlayed: Int,
    val questMet: Boolean,
    val games: List<HistoryGameGroup>,
    val gameThumbnails: HistoryGameThumbnails = HistoryGameThumbnails(),
    val achievements: HistoryAchievements,
    /** Minutes on this day that came from unknown-time allocations. */
    val estimatedMinutes: Int = 0,
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
 * - A session's unlinked legacy remainder belongs to the **local date of its `startAt`** — a
 *   midnight-crossing session remainder sits entirely on the day it began. Funded timed shares are
 *   separate dated facts and follow their frozen credited dates instead.
 * - A dated unknown-time allocation belongs to its **frozen [HistoryAllocationUi.date]** — the
 *   canonical date key the sync credits it to; it is never re-attributed by the screen's zone.
 * - A day header's total, and its Focus-games total, are both the **sum of the session remainders,
 *   frozen timed shares and allocations grouped beneath it** (current Focus tags apply only to
 *   unlinked/legacy session minutes; funded shares use their credit-time eligibility) — not the
 *   stored [DayProgress] counters. Daily progress is credited to the same session-start/allocated
 *   date by the sync worker; recomputing totals from the grouped activity keeps them internally
 *   consistent with each other and with what the breakdown actually shows. [DayProgress] still
 *   supplies `questMet`, which remains authoritative for streaks.
 * - Days with [DayProgress] but no sessions or allocations still produce a (session-less) day
 *   group; days with allocations but neither sessions nor progress still appear and expand.
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
    sessionActivities: List<PlaytimeActivity.TimestampedSession> = emptyList(),
    allocations: List<HistoryAllocationUi> = emptyList(),
    windowStartDate: String? = null,
    windowEndDate: String? = null,
): List<HistoryDayGroup> {
    val nameById = games.associate { it.appId to it.name }
    val iconById = games.associate { it.appId to it.iconUrl }
    val goalAppIds = games.filter { it.isGoal }.map { it.appId }.toSet()
    val progressByDate = dailyProgress.associateBy { it.date }
    val projectedSessionIds = sessionActivities.mapTo(mutableSetOf()) { it.id }
    val sessionsByDate = mutableMapOf<String, MutableList<HistorySessionUi>>()
    val timedSharesByDate = mutableMapOf<String, MutableList<HistoryTimedShareUi>>()
    val goalApps = goalAppIds

    // Raw rows remain only as the metadata source for timestamped session leaves. Their full
    // minutes are never used for dated totals once a canonical session projection is available.
    sessions.filterNot { it.id in projectedSessionIds }.forEach { session ->
        sessionsByDate.getOrPut(localDate(session.startAt, zone), ::mutableListOf) += HistorySessionUi(
            id = session.id,
            startAt = session.startAt,
            minutes = session.minutes,
            open = session.open,
            cloudContribution = session.cloudContribution,
        )
    }
    sessionActivities.forEach { activity ->
        val hostDate = localDate(activity.startAt, zone)
        val hostIsInWindow = (windowStartDate == null || hostDate >= windowStartDate) &&
            (windowEndDate == null || hostDate <= windowEndDate)
        if (hostIsInWindow && activity.unlinkedMinutes > 0L) {
            val cloudContribution = sessions.firstOrNull { it.id == activity.id }?.cloudContribution
                ?: SessionCloudContribution()
            sessionsByDate.getOrPut(hostDate, ::mutableListOf) += HistorySessionUi(
                id = activity.id,
                startAt = activity.startAt,
                minutes = activity.unlinkedMinutes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                open = activity.open,
                cloudContribution = cloudContribution,
            )
        }
        activity.fundedShares.forEach shareLoop@{ share ->
            val date = requireNotNull(share.creditedDate) {
                "funded share ${share.contributionId} has no frozen credited date"
            }.toString()
            if ((windowStartDate != null && date < windowStartDate) ||
                (windowEndDate != null && date > windowEndDate)
            ) return@shareLoop
            timedSharesByDate.getOrPut(date, ::mutableListOf) += HistoryTimedShareUi(
                sessionId = activity.id,
                minutes = share.minutes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                goalMinutes = if (share.goalAtCredit ?: (activity.appId in goalApps)) {
                    share.minutes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                } else {
                    0
                },
            )
        }
    }
    val allocationsByDate = allocations.groupBy { it.date.toString() }
    val achievementsByDate = achievementUnlocks.groupBy { localDate(it.unlockedAt, zone) }

    val allDates = (sessionsByDate.keys + timedSharesByDate.keys + progressByDate.keys + allocationsByDate.keys).distinct()
        .filter { date ->
            (windowStartDate == null || date >= windowStartDate) &&
                (windowEndDate == null || date <= windowEndDate)
        }.sortedDescending()

    return allDates.map { date ->
        val daySessions = sessionsByDate[date].orEmpty()
        val dayTimedShares = timedSharesByDate[date].orEmpty()
        val dayAllocations = allocationsByDate[date].orEmpty()
        val progress = progressByDate[date]
        val unlocksForDay = achievementsByDate[date].orEmpty().sortedBy { it.unlockedAt }

        val sessionAppById = sessionActivities.associate { it.id to it.appId } +
            sessions.associate { it.id to it.appId }
        val sessionsByGame = daySessions.groupBy { session ->
            sessionAppById[session.id] ?: error("missing app id for session ${session.id}")
        }
        val timedSharesByGame = dayTimedShares.groupBy { share ->
            sessionAppById[share.sessionId] ?: error("missing app id for session ${share.sessionId}")
        }
        val allocationsByGame = dayAllocations.groupBy { it.appId }
        val gameGroups = (sessionsByGame.keys + timedSharesByGame.keys + allocationsByGame.keys)
            .map { appId ->
                val sessionsForGame = sessionsByGame[appId].orEmpty()
                val timedSharesForGame = timedSharesByGame[appId].orEmpty()
                val allocationsForGame = allocationsByGame[appId].orEmpty()
                HistoryGameGroup(
                    appId = appId,
                    name = nameById[appId] ?: "App $appId",
                    iconUrl = iconById[appId] ?: "",
                    minutesPlayed = sessionsForGame.sumOf { it.minutes } +
                        timedSharesForGame.sumOf { it.minutes } +
                        allocationsForGame.sumOf { it.minutes },
                    sessions = sessionsForGame.sortedBy { it.startAt },
                    timedShares = timedSharesForGame.sortedBy { it.sessionId },
                    allocations = allocationsForGame.sortedWith(
                        compareBy<HistoryAllocationUi> { it.window.windowStartDate }
                            .thenBy { it.window.windowEndDate }
                            .thenBy { it.placementId },
                    ),
                )
            }
            .sortedWith(
                compareByDescending<HistoryGameGroup> { it.minutesPlayed }
                    .thenBy { it.name }
                    .thenBy { it.appId },
            )
        val sessionGoalMinutes = sessionsByGame
            .filterKeys { it in goalAppIds }
            .values.flatten()
            .sumOf { it.minutes }

        HistoryDayGroup(
            date = date,
            minutesPlayed = daySessions.sumOf { it.minutes } + dayTimedShares.sumOf { it.minutes } +
                dayAllocations.sumOf { it.minutes },
            goalMinutesPlayed = sessionGoalMinutes +
                dayTimedShares.sumOf { it.goalMinutes } +
                dayAllocations
                    .filter { allocation ->
                        allocation.goalAtCredit ?: (allocation.appId in goalAppIds)
                    }
                    .sumOf { it.minutes },
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
            estimatedMinutes = dayAllocations.sumOf { it.minutes },
        )
    }
}

/** Older progress rows must not make a bounded session window look fully loaded. */
internal fun historyHasOlderRecords(
    windowStartDate: String,
    earliestSessionAt: Long?,
    dailyProgress: List<DayProgress>,
    zone: ZoneId,
    earliestAllocationDate: String? = null,
): Boolean =
    earliestSessionAt?.let { localDate(it, zone) < windowStartDate } == true ||
        earliestAllocationDate?.let { it < windowStartDate } == true ||
        dailyProgress.any { it.date < windowStartDate }

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
