package com.example.backlogium.domain

import java.math.BigInteger

/**
 * Places a Steam playtime delta against cloud-observed intervals.
 *
 * Steam remains the authority for the quantity. The intervals contribute only their confirmed
 * span, which is used as a weight for an exact largest-remainder allocation. A null result means
 * the record could not improve the ordinary [SessionDiffer] estimate and the caller should keep
 * its unaided action unchanged.
 */
object CloudPresencePlaytimePlacement {
    const val DEFAULT_GAP_TOLERANCE_MILLIS =
        CloudPresenceSessionIngest.DEFAULT_GAP_TOLERANCE_MILLIS

    const val MINIMUM_PLACEMENT_PERIOD_MILLIS = 30L * 60 * 1_000

    data class Input(
        val intervals: List<CloudPresenceInterval>,
    )

    /** Whether a stale playtime estimate is long enough to justify an optional cloud read. */
    fun shouldConsult(periodStartAt: Long, periodEndAt: Long): Boolean =
        periodStartAt > 0L &&
            periodEndAt > periodStartAt &&
            periodEndAt - periodStartAt >= MINIMUM_PLACEMENT_PERIOD_MILLIS

    /** Replace only positive diff actions; zero-delta close actions remain unchanged. */
    fun replacePositiveActions(
        actions: List<SessionDiffer.SessionAction>,
        input: Input,
        periodStartAt: Long,
        periodEndAt: Long,
        priorOpenSessionsByAppId: Map<Long, SessionDiffer.OpenSession>,
    ): List<SessionDiffer.SessionAction> = actions.flatMap { action ->
        if (action.addedMinutes <= 0) {
            listOf(action)
        } else {
            place(
                Request(
                    appId = action.appId,
                    diffedMinutes = action.addedMinutes,
                    periodStartAt = periodStartAt,
                    periodEndAt = periodEndAt,
                    intervals = input.intervals,
                    priorOpenSession = priorOpenSessionsByAppId[action.appId],
                ),
            ) ?: listOf(action)
        }
    }

    data class Request(
        val appId: Long,
        val diffedMinutes: Int,
        val periodStartAt: Long,
        val periodEndAt: Long,
        val intervals: List<CloudPresenceInterval>,
        val priorOpenSession: SessionDiffer.OpenSession? = null,
        val gapToleranceMillis: Long = DEFAULT_GAP_TOLERANCE_MILLIS,
    )

    /** Return replacement actions, or null when the unaided diff action must be retained. */
    fun place(request: Request): List<SessionDiffer.SessionAction>? {
        require(request.diffedMinutes >= 0) { "diffedMinutes must not be negative" }
        require(request.gapToleranceMillis >= 0L) { "gapToleranceMillis must not be negative" }
        if (request.diffedMinutes == 0 || request.periodEndAt <= request.periodStartAt) {
            return null
        }

        val candidates = request.intervals
            .asSequence()
            .filter { it.appId == request.appId }
            .sortedWith(compareBy<CloudPresenceInterval> { it.startAt }.thenBy { it.endAt ?: Long.MAX_VALUE })
            .mapNotNull {
                it.confirmedCandidate(
                    periodStartAt = request.periodStartAt,
                    periodEndAt = request.periodEndAt,
                    gapToleranceMillis = request.gapToleranceMillis,
                )
            }
            .toList()
        if (candidates.isEmpty()) return null

        val totalSpan = candidates.fold(BigInteger.ZERO) { total, candidate ->
            total + candidate.confirmedSpanMillis.toBigInteger()
        }
        if (totalSpan.signum() <= 0) return null

        val allocations = allocate(request.diffedMinutes, candidates, totalSpan)
        val positiveCandidates = candidates.zip(allocations)
            .filter { (_, minutes) -> minutes > 0 }
        return buildActions(
            request,
            positiveCandidates.map { it.first },
            positiveCandidates.map { it.second },
        )
    }

    private fun allocate(
        diffedMinutes: Int,
        candidates: List<Candidate>,
        totalSpan: BigInteger,
    ): List<Int> {
        val exact = candidates.map { candidate ->
            BigInteger.valueOf(diffedMinutes.toLong()) * candidate.confirmedSpanMillis.toBigInteger()
        }
        val floors = exact.map { value -> value.divide(totalSpan).toInt() }
        val remainder = diffedMinutes - floors.sum()
        if (remainder == 0) return floors

        val order = exact.indices.sortedWith(
            compareByDescending<Int> { exact[it].remainder(totalSpan) }.thenBy { it },
        )
        val result = floors.toMutableList()
        order.take(remainder).forEach { index -> result[index]++ }
        check(result.sum() == diffedMinutes)
        return result
    }

    private fun buildActions(
        request: Request,
        candidates: List<Candidate>,
        allocations: List<Int>,
    ): List<SessionDiffer.SessionAction> {
        val actions = mutableListOf<SessionDiffer.SessionAction>()
        var prior = request.priorOpenSession

        candidates.forEachIndexed { index, candidate ->
            val final = index == candidates.lastIndex
            val remainsOpen = final && candidate.interval.ongoing &&
                candidate.endAt >= request.periodEndAt
            val continuesPrior = index == 0 && prior != null &&
                candidate.startAt <= request.periodStartAt

            if (index == 0 && prior != null && !continuesPrior) {
                actions += SessionDiffer.SessionAction.Close(
                    appId = request.appId,
                    startAt = prior.startAt,
                    endAt = prior.lastIncreaseAt,
                )
                prior = null
            }

            if (continuesPrior) {
                val open = prior ?: error("prior session disappeared")
                actions += SessionDiffer.SessionAction.Extend(
                    appId = request.appId,
                    startAt = open.startAt,
                    minutes = open.minutes + allocations[index],
                    endAt = candidate.endAt,
                    addedMinutes = allocations[index],
                )
                if (!remainsOpen) {
                    actions += SessionDiffer.SessionAction.Close(
                        appId = request.appId,
                        startAt = open.startAt,
                        endAt = candidate.endAt,
                    )
                    prior = null
                }
            } else {
                actions += SessionDiffer.SessionAction.Open(
                    appId = request.appId,
                    startAt = candidate.startAt,
                    endAt = candidate.endAt,
                    minutes = allocations[index],
                )
                if (!remainsOpen) {
                    actions += SessionDiffer.SessionAction.Close(
                        appId = request.appId,
                        startAt = candidate.startAt,
                        endAt = candidate.endAt,
                    )
                }
            }
        }
        return actions
    }

    private data class Candidate(
        val interval: CloudPresenceInterval,
        val startAt: Long,
        val endAt: Long,
        val confirmedSpanMillis: Long,
    )

    private fun CloudPresenceInterval.confirmedCandidate(
        periodStartAt: Long,
        periodEndAt: Long,
        gapToleranceMillis: Long,
    ): Candidate? {
        val rawEnd = endAt ?: periodEndAt.takeIf { ongoing } ?: return null
        val observedEnd = when (coverage) {
            CloudCoverageState.CONTINUOUS -> rawEnd
            CloudCoverageState.OBSERVED_UNTIL -> observedUntil ?: return null
            CloudCoverageState.UNKNOWN -> return null
        }.coerceAtMost(rawEnd)
        val start = maxOf(startAt, periodStartAt)
        val end = minOf(observedEnd, periodEndAt)
        if (end <= start) return null

        val lapseFrom = coverageLapseFrom
        val lapseRecoveredAt = coverageLapseRecoveredAt
        val confirmedSpan = if (lapseFrom == null && lapseRecoveredAt == null) {
            end - start
        } else {
            if (lapseFrom == null || lapseRecoveredAt == null) return null
            val rawLapse = lapseRecoveredAt - lapseFrom
            if (rawLapse < 0L || rawLapse > gapToleranceMillis) return null
            val gapStart = maxOf(start, lapseFrom)
            val gapEnd = minOf(end, lapseRecoveredAt)
            (end - start) - (gapEnd - gapStart).coerceAtLeast(0L)
        }
        return confirmedSpan.takeIf { it > 0L }?.let {
            Candidate(this, start, end, it)
        }
    }
}
