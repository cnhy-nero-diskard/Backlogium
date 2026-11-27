package com.example.backlogium.ui.analytics

import com.example.backlogium.data.repo.GameGenre
import com.example.backlogium.data.repo.PlaySession
import com.example.backlogium.domain.DatedCreditedTotal
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
