package com.example.backlogium.ui.analytics

import com.example.backlogium.data.repo.GameGenre
import com.example.backlogium.data.repo.PlaySession
import com.example.backlogium.domain.DatedCreditedTotal
import com.example.backlogium.data.repo.AchievementEvidenceSnapshot
import com.example.backlogium.data.repo.DatedAchievementEvidence
import com.example.backlogium.data.repo.DatedCompletionEvidence
import com.example.backlogium.data.repo.RecordedActivityBounds
import com.example.backlogium.gamification.Gamification
import com.example.backlogium.gamification.RarityTier
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

data class AnalyticsGenreShare(val genre: GameGenre?, val minutes: Double, val percent: Double)
data class AnalyticsGenreMix(
    val shares: List<AnalyticsGenreShare> = emptyList(),
    val coveredGames: Int = 0,
    val knownMinutes: Long = 0,
    val totalMinutes: Long = 0,
) {
    val supportsDrift: Boolean get() = coveredGames >= 3 && knownMinutes >= totalMinutes / 2.0
}

data class AnalyticsGenreDrift(val genre: GameGenre?, val percentagePoints: Double)

/** Current distinct Store identities divide credit; Unknown retains the same denominator. */
internal fun deriveGenreMix(totals: List<DatedCreditedTotal>, genres: Map<Long, List<GameGenre>>): AnalyticsGenreMix {
    val byGame = totals.flatMap { it.minutesByGame.entries }.groupBy({ it.key }, { it.value })
        .mapValues { (_, minutes) -> minutes.sum() }.filterValues { it > 0 }
    val allocated = mutableMapOf<String?, Double>()
    val labels = mutableMapOf<String, GameGenre>()
    var covered = 0
    var known = 0L
    byGame.toSortedMap().forEach { (appId, minutes) ->
        val distinct = genres[appId].orEmpty().filter { it.id.isNotBlank() && it.label.isNotBlank() }.distinctBy { it.id }
        if (distinct.isEmpty()) allocated[null] = (allocated[null] ?: 0.0) + minutes
        else {
            covered++
            known += minutes
            distinct.forEach { genre ->
                labels.putIfAbsent(genre.id, genre)
                allocated[genre.id] = (allocated[genre.id] ?: 0.0) + minutes.toDouble() / distinct.size
            }
        }
    }
    val total = byGame.values.sum()
    return AnalyticsGenreMix(allocated.entries.sortedWith(compareByDescending<Map.Entry<String?, Double>> { it.value }.thenBy { it.key })
        .map { AnalyticsGenreShare(labels[it.key], it.value, it.value * 100 / total) }, covered, known, total)
}

internal fun deriveGenreDrift(current: AnalyticsGenreMix, previous: AnalyticsGenreMix, comparable: Boolean): List<AnalyticsGenreDrift> {
    if (!comparable || !current.supportsDrift || !previous.supportsDrift) return emptyList()
    val before = previous.shares.associate { it.genre?.id to it.percent }
    val after = current.shares.associate { it.genre?.id to it.percent }
    return (current.shares + previous.shares).distinctBy { it.genre?.id }.map {
        AnalyticsGenreDrift(it.genre, (after[it.genre?.id] ?: 0.0) - (before[it.genre?.id] ?: 0.0))
    }
}

data class AnalyticsClockCell(val weekday: DayOfWeek, val bucket: TimeOfDayBucket, val minutes: Long)
data class AnalyticsClockMatrix(val cells: List<AnalyticsClockCell> = emptyList(), val activeDates: Int = 0) {
    // Three recorded start dates are required before naming a dominant pattern.
    val dominant: AnalyticsClockCell? get() = cells.takeIf { activeDates >= 3 }?.maxByOrNull { it.minutes }
}

internal fun deriveClockMatrix(sessions: List<PlaySession>, zone: ZoneId): AnalyticsClockMatrix {
    val positive = sessions.filter { it.minutes > 0 }
    val grouped = positive.groupBy {
        val start = Instant.ofEpochMilli(it.startAt).atZone(zone)
        start.dayOfWeek to when (start.hour) {
            in 5..11 -> TimeOfDayBucket.MORNING
            in 12..16 -> TimeOfDayBucket.AFTERNOON
            in 17..20 -> TimeOfDayBucket.EVENING
            else -> TimeOfDayBucket.NIGHT
        }
    }
    return AnalyticsClockMatrix(DayOfWeek.entries.flatMap { day -> TimeOfDayBucket.entries.mapNotNull { bucket ->
        grouped[day to bucket]?.let { AnalyticsClockCell(day, bucket, it.sumOf { s -> s.minutes.toLong() }) }
    } }, positive.map { Instant.ofEpochMilli(it.startAt).atZone(zone).toLocalDate() }.distinct().size)
}

data class AnalyticsAchievementActivity(
    val unlocks: List<DatedAchievementEvidence> = emptyList(),
    val completions: List<DatedCompletionEvidence> = emptyList(),
    val rarity: Map<RarityTier, Int> = emptyMap(),
    val previousCount: Int? = null,
    val unsupportedUnlocks: Int = 0,
    val unsupportedCompletions: Int = 0,
) {
    val missingRarity: Int get() = unlocks.count { it.rarityPercent == null }
}

internal fun deriveAchievementActivity(
    evidence: AchievementEvidenceSnapshot,
    bounds: AnalyticsWindowBounds?,
    comparison: AnalyticsComparison?,
    zone: ZoneId,
): AnalyticsAchievementActivity {
    fun date(at: Long) = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
    val selected = evidence.unlocks.filter { bounds != null && date(it.at) in bounds.start..bounds.endInclusive }
    val previous = evidence.unlocks.filter { comparison != null && date(it.at) in comparison.previousBounds.start..comparison.previousBounds.endInclusive }
    val comparable = comparison != null && selected.map { date(it.at) }.distinct().size >= 3 &&
        previous.map { date(it.at) }.distinct().size >= 3
    return AnalyticsAchievementActivity(
        selected,
        evidence.completions.filter { bounds != null && date(it.observedAt) in bounds.start..bounds.endInclusive },
        selected.mapNotNull { it.rarityPercent?.let(Gamification::tierFor) }.groupingBy { it }.eachCount(),
        previous.size.takeIf { comparable }, evidence.unsupportedUnlocks, evidence.unsupportedCompletions,
    )
}

data class AnalyticsDayRecord(val date: LocalDate, val amount: Long)
data class AnalyticsGameRun(val appId: Long, val start: LocalDate, val end: LocalDate) {
    val days: Long get() = ChronoUnit.DAYS.between(start, end) + 1
}
data class AnalyticsReturn(val appId: Long, val previous: LocalDate, val returned: LocalDate) {
    val quietDates: Long get() = ChronoUnit.DAYS.between(previous, returned) - 1
}
data class AnalyticsRecords(
    val longestSession: PlaySession? = null,
    val busiestDay: AnalyticsDayRecord? = null,
    val mostVariedDay: AnalyticsDayRecord? = null,
    val gameRuns: List<AnalyticsGameRun> = emptyList(),
    val longestReturn: AnalyticsReturn? = null,
    val rarestUnlock: DatedAchievementEvidence? = null,
)

/** Ties prefer the earliest date/start, then app id and stable row identity. Runs stop at the window edge. */
internal fun deriveRecords(
    totals: List<DatedCreditedTotal>,
    sessions: List<PlaySession>,
    history: Map<Long, RecordedActivityBounds>,
    unlocks: List<DatedAchievementEvidence>,
): AnalyticsRecords {
    val days = totals.filter { it.minutes > 0 }.sortedBy { it.date }
    val byGame = days.flatMap { day -> day.minutesByGame.filterValues { it > 0 }.keys.map { it to day.date } }
        .groupBy({ it.first }, { it.second })
    val returns = mutableListOf<AnalyticsReturn>()
    val runs = byGame.toSortedMap().map { (appId, recorded) ->
        val ordered = recorded.distinct().sorted()
        (listOfNotNull(history[appId]?.last) + ordered).zipWithNext().forEach { (a, b) ->
            if (ChronoUnit.DAYS.between(a, b) >= 31) returns += AnalyticsReturn(appId, a, b)
        }
        var start = ordered.first()
        var end = start
        val candidates = mutableListOf<AnalyticsGameRun>()
        ordered.drop(1).forEach { next ->
            if (next != end.plusDays(1)) {
                candidates += AnalyticsGameRun(appId, start, end)
                start = next
            }
            end = next
        }
        candidates += AnalyticsGameRun(appId, start, end)
        candidates.sortedWith(compareByDescending<AnalyticsGameRun> { it.days }.thenBy { it.start }).first()
    }
    return AnalyticsRecords(
        sessions.filter { !it.open && it.minutes > 0 }.sortedWith(compareByDescending<PlaySession> { it.minutes }
            .thenBy { it.startAt }.thenBy { it.appId }.thenBy { it.id }).firstOrNull(),
        days.maxByOrNull { it.minutes }?.let { AnalyticsDayRecord(it.date, it.minutes) },
        days.maxByOrNull { day -> day.minutesByGame.count { it.value > 0 } }?.let {
            AnalyticsDayRecord(it.date, it.minutesByGame.count { entry -> entry.value > 0 }.toLong())
        },
        runs,
        returns.sortedWith(compareByDescending<AnalyticsReturn> { it.quietDates }.thenBy { it.returned }.thenBy { it.appId }).firstOrNull(),
        unlocks.filter { it.rarityPercent != null }.sortedWith(compareBy<DatedAchievementEvidence> { it.rarityPercent }
            .thenBy { it.at }.thenBy { it.appId }.thenBy { it.apiName }).firstOrNull(),
    )
}
