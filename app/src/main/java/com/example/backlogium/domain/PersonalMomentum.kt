package com.example.backlogium.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class MomentumKey(val accountId: String, val today: LocalDate, val zone: ZoneId)
data class CompletedWeeks(val baselineStart: LocalDate, val baselineEnd: LocalDate,
    val currentStart: LocalDate, val currentEnd: LocalDate) {
    fun startMillis(zone: ZoneId): Long = baselineStart.atStartOfDay(zone).toInstant().toEpochMilli()
    fun endExclusiveMillis(zone: ZoneId): Long = currentEnd.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
}
fun completedWeeks(today: LocalDate) = CompletedWeeks(today.minusDays(14), today.minusDays(8),
    today.minusDays(7), today.minusDays(1))

data class MomentumGame(val appId: Long, val name: String, val iconUrl: String)
data class MomentumRecord(val appId: Long, val startAt: Long, val minutes: Long, val open: Boolean)
data class MomentumDay(val appId: Long, val date: LocalDate, val minutes: Long)
enum class MomentumKind { GROWTH, NEWLY_RECORDED }
enum class MomentumAvailability { LEARNING, EMPTY_LIBRARY, NO_INCREASE, CANDIDATES }
data class MomentumCandidate(val game: MomentumGame, val currentMinutes: Long,
    val baselineMinutes: Long, val activeDates: Int, val kind: MomentumKind) {
    val additionalMinutes: Long get() = currentMinutes - baselineMinutes
}
data class PersonalMomentum(val key: MomentumKey, val weeks: CompletedWeeks,
    val availability: MomentumAvailability, val candidates: List<MomentumCandidate>)

/** Whole positive finalized records belong to their local start date, without elapsed-time allocation. */
fun momentumDays(records: List<MomentumRecord>, weeks: CompletedWeeks, zone: ZoneId): List<MomentumDay> =
    records.asSequence().filter { !it.open && it.minutes > 0 }
        .map { it to Instant.ofEpochMilli(it.startAt).atZone(zone).toLocalDate() }
        .filter { (_, date) -> date >= weeks.baselineStart && date <= weeks.currentEnd }
        .groupBy { (record, date) -> record.appId to date }
        .map { (identity, rows) -> MomentumDay(identity.first, identity.second, rows.sumOf { it.first.minutes }) }

fun personalMomentum(key: MomentumKey, games: List<MomentumGame>, days: List<MomentumDay>,
    earliestEligibleRecordDate: LocalDate?): PersonalMomentum {
    val weeks = completedWeeks(key.today)
    fun result(availability: MomentumAvailability, rows: List<MomentumCandidate> = emptyList()) =
        PersonalMomentum(key, weeks, availability, rows)
    if (games.isEmpty()) return result(MomentumAvailability.EMPTY_LIBRARY)
    if (earliestEligibleRecordDate == null || earliestEligibleRecordDate > weeks.baselineStart) {
        return result(MomentumAvailability.LEARNING)
    }
    val byGame = days.filter { it.minutes > 0 && it.date >= weeks.baselineStart && it.date <= weeks.currentEnd }
        .groupBy { it.appId }
    val candidates = games.mapNotNull { game ->
        val rows = byGame[game.appId].orEmpty()
        val current = rows.filter { it.date >= weeks.currentStart }
        val c = current.sumOf { it.minutes }
        val active = current.map { it.date }.distinct().size
        val p = rows.filter { it.date <= weeks.baselineEnd }.sumOf { it.minutes }
        if (c < 60 || active < 2) return@mapNotNull null
        val delta = c - p
        val kind = when {
            p == 0L -> MomentumKind.NEWLY_RECORDED
            // ceil(P / 4) is exactly 4 * delta >= P, without multiplication overflow or rounding.
            p >= 30 && delta >= 30 && delta >= p / 4 + (if (p % 4 == 0L) 0L else 1L) -> MomentumKind.GROWTH
            else -> return@mapNotNull null
        }
        MomentumCandidate(game, c, p, active, kind)
    }
    val growth = candidates.filter { it.kind == MomentumKind.GROWTH }.sortedWith(
        compareByDescending<MomentumCandidate> { it.additionalMinutes }.thenByDescending { it.currentMinutes }
            .thenBy { it.game.appId })
    val newlyRecorded = candidates.filter { it.kind == MomentumKind.NEWLY_RECORDED }.sortedWith(
        compareByDescending<MomentumCandidate> { it.currentMinutes }.thenBy { it.game.appId })
    val ranked = (growth + newlyRecorded).take(5)
    return result(if (ranked.isEmpty()) MomentumAvailability.NO_INCREASE else MomentumAvailability.CANDIDATES, ranked)
}
