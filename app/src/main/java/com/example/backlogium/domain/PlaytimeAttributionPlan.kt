package com.example.backlogium.domain

import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * The pure placement half of missed-playtime recovery.
 *
 * [SessionDiffer] stays the sole quantity authority: it owns the high-water mark and the positive
 * per-game delta. This planner never reads Steam or a clock; the caller injects the authorized
 * delta, the observation window, the recovery zone, the evaluation date and whatever validated
 * timing evidence it has, and receives one plan describing where those already-authorized minutes
 * belong — as timestamped sessions, as dated unknown-time allocations, or as pending date-unknown
 * quantity.
 *
 * It has no Android, Room or network dependency so every branch is unit-testable, and it never
 * invents quantity: `creditedMinutes` always equals the injected authorized total. All sums and
 * ranges use wider checked arithmetic and reject unrepresentable values instead of wrapping.
 */

/** Which observer produced a piece of timing evidence. Local timing outranks cloud; estimates are never evidence. */
enum class PlaytimeEvidenceSource { LOCAL, CLOUD, ESTIMATE }

/** How a positive dated share was attributed, preserved so uncertainty survives restart and export. */
enum class PlaytimeAllocationMethod {
    /** Timed placement whose minutes stay with the persisted session's frozen start date. */
    EVIDENCE_INFORMED,

    /** A single eligible date between two same-or-consecutive-day observations. */
    SINGLE_DAY_ESTIMATE,

    /** An observation window spanning several recovery dates before evidence exclusions. */
    MULTI_DAY_ESTIMATE,

    /** A supplied targeted play instant established the activity date but no usable start. */
    KNOWN_TARGETED_DATE,

    /** Steam-authorized quantity retained without a valid calendar date yet. */
    PENDING_DATE_UNKNOWN,
}

/** Why authorized minutes could not be dated yet. */
enum class PlaytimePendingReason {
    /** The recovery window was inverted or otherwise had no valid calendar window. */
    INVERTED_WINDOW,
    /** No prior usable observation was available to normalize a baseline date. */
    NO_BASELINE_WINDOW,
}

/** Whether a placement is fresh earned credit or retroactive recovery. */
enum class PlaytimePlacementProvenance { SYNC, RETROACTIVE_PLAY }

enum class PlaytimeDiagnosticCode {
    WRONG_APP,
    WRONG_ACCOUNT,
    STALE_GENERATION,
    CLOUD_WITHOUT_ACTIVE_IDENTITY,
    ESTIMATE_NOT_VALID,
    INCOMPLETE_GAP,
    GAP_ABOVE_TOLERANCE,
    EMPTY_INTERVAL,
    OUTSIDE_WINDOW,
    UNBALANCED_FULL_COVERAGE,
    INVERTED_WINDOW,
    TARGET_OUTSIDE_WINDOW,
    REPLAN_PENDING,
}

val PlaytimeDiagnosticCode.isEvidenceRejection: Boolean
    get() = this == PlaytimeDiagnosticCode.WRONG_APP ||
        this == PlaytimeDiagnosticCode.WRONG_ACCOUNT ||
        this == PlaytimeDiagnosticCode.STALE_GENERATION ||
        this == PlaytimeDiagnosticCode.CLOUD_WITHOUT_ACTIVE_IDENTITY ||
        this == PlaytimeDiagnosticCode.ESTIMATE_NOT_VALID ||
        this == PlaytimeDiagnosticCode.INCOMPLETE_GAP ||
        this == PlaytimeDiagnosticCode.GAP_ABOVE_TOLERANCE ||
        this == PlaytimeDiagnosticCode.EMPTY_INTERVAL

data class PlaytimeDiagnostic(val code: PlaytimeDiagnosticCode, val detail: String = "")

/** A cumulative Steam range `(fromMinutes, toMinutes]`; the stable contribution identity derives from it. */
data class PlaytimeQuantityRange(val fromMinutes: Int, val toMinutes: Int) {
    init {
        require(fromMinutes >= 0) { "fromMinutes must not be negative" }
        require(fromMinutes <= toMinutes) { "toMinutes must not precede fromMinutes" }
        require(toMinutes <= Int.MAX_VALUE) { "cumulative range overflows Int" }
    }

    val authorizedMinutes: Int get() = toMinutes - fromMinutes
}

/**
 * Derived from account, app, baseline epoch and cumulative range — never from a poll timestamp or an
 * autoincrement row, so a contribution can be identified exactly once across retries and export.
 */
data class PlaytimeContributionId(
    val account: String,
    val appId: Long,
    val baselineEpoch: Long,
    val quantityRange: PlaytimeQuantityRange,
) {
    init { require(account.isNotBlank()) { "account must not be blank" } }

    val authorizedMinutes: Int get() = quantityRange.authorizedMinutes

    fun canonical(): String =
        "$account|$appId|$baselineEpoch|${quantityRange.fromMinutes}|${quantityRange.toMinutes}"
}

/**
 * One validated-candidate timing interval for a game. The planner still validates identity,
 * generation, ordering and gap tolerance; passing a value here is a candidate claim, not authority.
 * [account] is required on every validated candidate — a missing or foreign account is rejected.
 */
data class PlaytimeTimingEvidence(
    val evidenceId: String,
    val appId: Long,
    val account: String? = null,
    val readerIdentity: CloudReaderIdentity? = null,
    val source: PlaytimeEvidenceSource = PlaytimeEvidenceSource.LOCAL,
    val startAt: Long,
    val endAt: Long,
    /** An interior coverage gap, bridged only while within the request tolerance. */
    val gapFromAt: Long? = null,
    val gapRecoveredAt: Long? = null,
)

/** A span already funded by another cumulative contribution; subtracted before placement. */
data class ConsumedPlaytimeSpan(val startAt: Long, val endAt: Long) {
    init { require(endAt >= startAt) { "consumed span must not be inverted" } }
}

/** A positive share of minutes on one frozen local date. Zero shares are never representable. */
data class DatedAllocation(
    val date: LocalDate,
    val minutes: Int,
    val method: PlaytimeAllocationMethod,
) : Comparable<DatedAllocation> {
    init { require(minutes > 0) { "dated allocations store only positive shares" } }

    override fun compareTo(other: DatedAllocation): Int = date.compareTo(other.date)
}

/**
 * Minutes placed against one usable interval. A timed session is atomic by its persisted start
 * date (steam-sync/cloud specs): the whole share stays on [date], never fractionalized across the
 * dates the interval happens to cross.
 */
data class TimedPlacement(
    val evidenceIds: List<String>,
    val source: PlaytimeEvidenceSource,
    val startAt: Long,
    val endAt: Long,
    val minutes: Int,
    val date: LocalDate,
)

data class PendingDateUnknown(val minutes: Int, val reason: PlaytimePendingReason)

/** Everything the caller needs to persist one game's recovered placement. */
data class PlaytimeAttributionPlan(
    val contributionId: PlaytimeContributionId?,
    val account: String,
    val appId: Long,
    val baselineEpoch: Long,
    /** Total quantity this plan accounts for: new delta plus any retained pending quantity. */
    val authorizedMinutes: Int,
    val zone: ZoneId,
    val provenance: PlaytimePlacementProvenance,
    val sessionActions: List<SessionDiffer.SessionAction>,
    val timedPlacements: List<TimedPlacement>,
    val residual: ResidualDateRecipe?,
    val pending: PendingDateUnknown?,
    val diagnostics: List<PlaytimeDiagnostic>,
) {
    init {
        require(authorizedMinutes >= 0) { "authorizedMinutes must not be negative" }
        require(creditedMinutes == authorizedMinutes) {
            "attribution lost quantity: credited $creditedMinutes of $authorizedMinutes"
        }
    }

    val sessionMinutes: Int get() = sessionActions.sumOf { it.addedMinutes }
    val timedMinutes: Int get() = timedPlacements.sumOf { it.minutes }
    val residualMinutes: Int get() = residual?.totalMinutes ?: 0
    val pendingMinutes: Int get() = pending?.minutes ?: 0

    /** Exactly the injected authorized total, by construction. */
    val creditedMinutes: Int get() = sessionMinutes + timedMinutes + residualMinutes + pendingMinutes

    val rejectedEvidenceCount: Int get() = diagnostics.count { it.code.isEvidenceRejection }

    /** Frozen local dates this plan credits. Journals a long window; prefer [residualPage] for paging. */
    fun changedDates(): Set<LocalDate> = buildSet {
        sessionActions
            .filter { it.addedMinutes > 0 }
            .forEach { add(Instant.ofEpochMilli(it.startAt).atZone(zone).toLocalDate()) }
        timedPlacements.forEach { add(it.date) }
        residual?.let { addAll(PlaytimeAllocationRecipes.positiveAllocations(it).map { it.date }) }
    }

    fun residualAllocations(): List<DatedAllocation> =
        residual?.let { PlaytimeAllocationRecipes.positiveAllocations(it) }.orEmpty()

    /** Bounded projection over a long residual recipe; never materializes the whole window. */
    fun residualPage(pageIndex: Int, pageSize: Int): List<DatedAllocation> =
        residual?.let { PlaytimeAllocationRecipes.page(it, pageIndex, pageSize) }.orEmpty()
}

/**
 * All the caller knows when it asks where authorized minutes belong. The quantity comes from
 * [SessionDiffer]; everything else is evidence or context the planner may not infer itself.
 */
data class PlaytimeRecoveryRequest(
    val account: String,
    val appId: Long,
    val baselineEpoch: Long,
    /** Positive Steam-authorized delta computed by the existing differ this commit. */
    val authorizedDeltaMinutes: Int,
    /** Already-authorized minutes retained from a prior inverted-window commit, awaiting a valid window. */
    val unplacedPendingMinutes: Int = 0,
    /** Prior usable observation instant (normalized baseline boundary); null when unbaselined. */
    val previousObservationAt: Long? = null,
    /** This reading's observation instant, in epoch millis. */
    val recoveryObservedAt: Long,
    /** Recovery reading's device zone, frozen for the resulting date keys. */
    val zone: ZoneId,
    /** Original previous zone, retained for diagnostics only. */
    val previousZone: ZoneId? = null,
    /** The current calendar date at evaluation; the committer supplies actual today. */
    val evaluationDate: LocalDate? = null,
    /** Cumulative high-water before the *new* delta, when known, so a stable contribution ID can be derived. */
    val priorHighWaterMinutes: Int? = null,
    /**
     * The existing contribution a pending/replan placement belongs to. Pending quantity was already
     * authorized elsewhere and is never folded into a freshly derived identity.
     */
    val existingContributionId: PlaytimeContributionId? = null,
    /** Candidate open session the existing differ would otherwise extend. */
    val priorOpenSession: SessionDiffer.OpenSession? = null,
    /** Validated-candidate local/cloud timing evidence. */
    val evidence: List<PlaytimeTimingEvidence> = emptyList(),
    /** Spans already funded by another contribution; subtracted before placement. */
    val consumedSpans: List<ConsumedPlaytimeSpan> = emptyList(),
    /** Active cloud reader fence; cloud evidence must match it exactly. */
    val activeReaderIdentity: CloudReaderIdentity? = null,
    val gapToleranceMillis: Long = PlaytimeAttributionPlanner.DEFAULT_GAP_TOLERANCE_MILLIS,
    /** True when the clock moved backwards or the window is otherwise unusable. */
    val clockAnomalous: Boolean = false,
    /** A known publication-delay conflict invalidates the ordinary short-gap estimate. */
    val publicationDelayConflict: Boolean = false,
    /** A targeted fetch's supplied play instant; must lie inside the previous..recovery window. */
    val knownPlayInstantAt: Long? = null,
) {
    init {
        require(account.isNotBlank()) { "account must not be blank" }
        require(authorizedDeltaMinutes >= 0) { "authorizedDeltaMinutes must not be negative" }
        require(unplacedPendingMinutes >= 0) { "unplacedPendingMinutes must not be negative" }
        require(gapToleranceMillis >= 0L) { "gapToleranceMillis must not be negative" }
        require(authorizedDeltaMinutes == 0 || unplacedPendingMinutes == 0) {
            "a retained-pending replan must be its own request with no new delta; the caller loops instead"
        }
        require(unplacedPendingMinutes == 0 || existingContributionId != null) {
            "retained pending quantity needs its existing contribution identity; it cannot be re-authorized"
        }
    }
}

object PlaytimeAttributionPlanner {

    /** The cloud-consultation threshold: below it a same-date diff estimate is kept as a session. */
    const val SHORT_GAP_MILLIS: Long = 30L * 60 * 1000

    val DEFAULT_GAP_TOLERANCE_MILLIS: Long = CloudPresencePlaytimePlacement.DEFAULT_GAP_TOLERANCE_MILLIS

    fun plan(request: PlaytimeRecoveryRequest): PlaytimeAttributionPlan {
        val total = checkedTotalMinutes(
            delta = request.authorizedDeltaMinutes,
            pending = request.unplacedPendingMinutes,
        )
        request.existingContributionId?.let { identity ->
            require(identity.account == request.account && identity.appId == request.appId &&
                identity.baselineEpoch == request.baselineEpoch
            ) {
                "existing contribution identity must match the request's account/app/epoch"
            }
            require(identity.authorizedMinutes == total) {
                "existing contribution funded quantity ${identity.authorizedMinutes} does not match the total $total"
            }
        }
        val contributionId = contributionIdentity(request)
        val diagnostics = mutableListOf<PlaytimeDiagnostic>()

        if (total == 0) {
            return emptyPlan(request, contributionId, diagnostics)
        }

        val previous = request.previousObservationAt
        val inverted = request.clockAnomalous || (previous != null && request.recoveryObservedAt < previous)
        if (previous == null || inverted) {
            diagnostics += PlaytimeDiagnostic(
                code = PlaytimeDiagnosticCode.INVERTED_WINDOW,
                detail = "previous=$previous recovery=${request.recoveryObservedAt} anomalous=${request.clockAnomalous}",
            )
            return PlaytimeAttributionPlan(
                contributionId = contributionId,
                account = request.account,
                appId = request.appId,
                baselineEpoch = request.baselineEpoch,
                authorizedMinutes = total,
                zone = request.zone,
                provenance = PlaytimePlacementProvenance.RETROACTIVE_PLAY,
                sessionActions = emptyList(),
                timedPlacements = emptyList(),
                residual = null,
                pending = PendingDateUnknown(
                    minutes = total,
                    reason = if (previous == null) PlaytimePendingReason.NO_BASELINE_WINDOW
                    else PlaytimePendingReason.INVERTED_WINDOW,
                ),
                diagnostics = diagnostics,
            )
        }

        val usableSpans = usableSpans(request, previous, diagnostics)
        if (request.unplacedPendingMinutes > 0) {
            diagnostics += PlaytimeDiagnostic(
                PlaytimeDiagnosticCode.REPLAN_PENDING,
                "placing ${request.unplacedPendingMinutes} retained pending minute(s)",
            )
        }
        val evaluationDate = request.evaluationDate ?: localDate(request.recoveryObservedAt, request.zone)
        return datedPlan(request, contributionId, total, previous, usableSpans, evaluationDate, diagnostics)
    }

    /** Wider checked sum: an unrepresentable authorized total is rejected, never wrapped. */
    private fun checkedTotalMinutes(delta: Int, pending: Int): Int {
        val total = delta.toLong() + pending
        require(total <= Int.MAX_VALUE) {
            "authorized playtime sum $delta + $pending overflows Int"
        }
        return total.toInt()
    }

    private fun contributionIdentity(request: PlaytimeRecoveryRequest): PlaytimeContributionId? = when {
        request.existingContributionId != null -> request.existingContributionId
        request.unplacedPendingMinutes > 0 -> null
        request.priorHighWaterMinutes == null -> null
        request.authorizedDeltaMinutes <= 0 -> null
        else -> {
            val to = request.priorHighWaterMinutes.toLong() + request.authorizedDeltaMinutes
            require(to <= Int.MAX_VALUE) {
                "cumulative range ${request.priorHighWaterMinutes} + ${request.authorizedDeltaMinutes} overflows Int"
            }
            PlaytimeContributionId(
                account = request.account,
                appId = request.appId,
                baselineEpoch = request.baselineEpoch,
                quantityRange = PlaytimeQuantityRange(request.priorHighWaterMinutes, to.toInt()),
            )
        }
    }

    private fun emptyPlan(
        request: PlaytimeRecoveryRequest,
        contributionId: PlaytimeContributionId?,
        diagnostics: List<PlaytimeDiagnostic>,
    ) = PlaytimeAttributionPlan(
        contributionId = contributionId,
        account = request.account,
        appId = request.appId,
        baselineEpoch = request.baselineEpoch,
        authorizedMinutes = 0,
        zone = request.zone,
        provenance = PlaytimePlacementProvenance.SYNC,
        sessionActions = emptyList(),
        timedPlacements = emptyList(),
        residual = null,
        pending = null,
        diagnostics = diagnostics,
    )

    private fun datedPlan(
        request: PlaytimeRecoveryRequest,
        contributionId: PlaytimeContributionId?,
        total: Int,
        previous: Long,
        usableSpans: List<Span>,
        evaluationDate: LocalDate,
        diagnostics: MutableList<PlaytimeDiagnostic>,
    ): PlaytimeAttributionPlan {
        // A supplied target instant is only usable inside the valid previous..recovery window;
        // anything else keeps the conservative permitted fallback instead of inventing a date.
        val knownInstant = request.knownPlayInstantAt
        val effectiveKnown = if (knownInstant == null) null
        else if (knownInstant >= previous && knownInstant <= request.recoveryObservedAt) knownInstant
        else {
            diagnostics += PlaytimeDiagnostic(
                PlaytimeDiagnosticCode.TARGET_OUTSIDE_WINDOW,
                "known instant $knownInstant outside [$previous, ${request.recoveryObservedAt}]",
            )
            null
        }

        val playEnd = effectiveKnown ?: request.recoveryObservedAt
        val elapsed = playEnd - previous
        val sameDate = localDate(playEnd, request.zone) == localDate(previous, request.zone)
        // A supplied end-only target never invents a start from the previous snapshot: without
        // continuity evidence it stays date-known/clock-unknown. Only an ordinary periodic
        // observation (no known instant) may take the same-date short-gap session path.
        val withinShortGap = request.knownPlayInstantAt == null && elapsed in 0..SHORT_GAP_MILLIS
        val guardOk = elapsed >= 0L && total.toLong() <= ceilMinutes(elapsed) + 1L
        val continuity = coveredBy(usableSpans, previous, playEnd)

        val sessionEligible = request.unplacedPendingMinutes == 0 &&
            !request.publicationDelayConflict &&
            guardOk &&
            ((sameDate && withinShortGap) || continuity)

        if (sessionEligible) {
            val priorOpen = request.priorOpenSession
            // A prior row starting a different calendar date may only be extended when validated
            // timing really continues it from its last increase; otherwise close it and open a
            // same-day interval so the old row cannot absorb an uncovered midnight.
            val priorStartsSameDate = priorOpen != null &&
                localDate(priorOpen.startAt, request.zone) == localDate(playEnd, request.zone)
            val extendable = priorOpen != null &&
                priorOpen.lastIncreaseAt >= previous &&
                (priorStartsSameDate ||
                    coveredBy(usableSpans, priorOpen.lastIncreaseAt, playEnd))
            val actions = sessionActions(
                appId = request.appId,
                previous = previous,
                playEnd = playEnd,
                total = total,
                priorOpen = priorOpen,
                extendable = extendable,
            )
            // A session is atomic by its start date; a continuing prior row keeps that start,
            // including across midnight with trusted continuity.
            val sessionStartDate = if (extendable) localDate(priorOpen!!.startAt, request.zone)
            else localDate(previous, request.zone)
            return PlaytimeAttributionPlan(
                contributionId = contributionId,
                account = request.account,
                appId = request.appId,
                baselineEpoch = request.baselineEpoch,
                authorizedMinutes = total,
                zone = request.zone,
                provenance = if (sessionStartDate.isBefore(evaluationDate))
                    PlaytimePlacementProvenance.RETROACTIVE_PLAY else PlaytimePlacementProvenance.SYNC,
                sessionActions = actions,
                timedPlacements = emptyList(),
                residual = null,
                pending = null,
                diagnostics = diagnostics,
            )
        }

        // Fallback: the stale open row is closed at its last trustworthy boundary and supplies no
        // new minutes; the authorized delta becomes dated allocations instead of extending it.
        val actions = listOfNotNull(
            request.priorOpenSession?.let { prior ->
                SessionDiffer.SessionAction.Close(
                    appId = request.appId,
                    startAt = prior.startAt,
                    endAt = prior.lastIncreaseAt,
                )
            },
        )

        val (timed, residualMinutes) = placeEvidence(request, usableSpans, total)
        val residual = if (residualMinutes > 0) {
            if (effectiveKnown != null) {
                val date = localDate(effectiveKnown, request.zone)
                ResidualDateRecipe(
                    method = PlaytimeAllocationMethod.KNOWN_TARGETED_DATE,
                    firstDate = date,
                    lastDate = date,
                    eligibleDateCount = 1,
                    baseMinutes = residualMinutes,
                    remainderCount = 0,
                    totalMinutes = residualMinutes,
                    excludedRanges = emptyList(),
                )
            } else {
                residualRecipe(request, previous, usableSpans, residualMinutes, diagnostics)
            }
        } else {
            null
        }

        val provenance = provenanceFor(request, evaluationDate, timed, residual)

        return PlaytimeAttributionPlan(
            contributionId = contributionId,
            account = request.account,
            appId = request.appId,
            baselineEpoch = request.baselineEpoch,
            authorizedMinutes = total,
            zone = request.zone,
            provenance = provenance,
            sessionActions = actions,
            timedPlacements = timed,
            residual = residual,
            pending = null,
            diagnostics = diagnostics,
        )
    }

    private fun provenanceFor(
        request: PlaytimeRecoveryRequest,
        evaluationDate: LocalDate,
        timed: List<TimedPlacement>,
        residual: ResidualDateRecipe?,
    ): PlaytimePlacementProvenance {
        if (request.unplacedPendingMinutes > 0) return PlaytimePlacementProvenance.RETROACTIVE_PLAY
        if (residual?.method == PlaytimeAllocationMethod.MULTI_DAY_ESTIMATE) {
            return PlaytimePlacementProvenance.RETROACTIVE_PLAY
        }
        // The whole plan is corrective if any attributed date is past: mixed-fresh separation is
        // the committer's staged operation, never this planner's per-row decision.
        val attributedDates = buildList {
            timed.forEach { add(it.date) }
            residual?.let { r ->
                if (r.method == PlaytimeAllocationMethod.SINGLE_DAY_ESTIMATE ||
                    r.method == PlaytimeAllocationMethod.KNOWN_TARGETED_DATE
                ) {
                    add(r.firstDate)
                }
            }
        }
        return if (attributedDates.isEmpty() || attributedDates.none { it.isBefore(evaluationDate) }) {
            PlaytimePlacementProvenance.SYNC
        } else {
            PlaytimePlacementProvenance.RETROACTIVE_PLAY
        }
    }

    private fun sessionActions(
        appId: Long,
        previous: Long,
        playEnd: Long,
        total: Int,
        priorOpen: SessionDiffer.OpenSession?,
        extendable: Boolean,
    ): List<SessionDiffer.SessionAction> {
        val actions = mutableListOf<SessionDiffer.SessionAction>()
        if (extendable) {
            val open = priorOpen!!
            val minutes = checkedExtendSum(open.minutes, total)
            actions += SessionDiffer.SessionAction.Extend(
                appId = appId,
                startAt = open.startAt,
                minutes = minutes,
                endAt = playEnd,
                addedMinutes = total,
            )
        } else {
            if (priorOpen != null) {
                actions += SessionDiffer.SessionAction.Close(
                    appId = appId,
                    startAt = priorOpen.startAt,
                    endAt = priorOpen.lastIncreaseAt,
                )
            }
            actions += SessionDiffer.SessionAction.Open(
                appId = appId,
                startAt = previous,
                endAt = playEnd,
                minutes = total,
            )
        }
        return actions
    }

    private fun checkedExtendSum(priorMinutes: Int, added: Int): Int {
        val sum = priorMinutes.toLong() + added
        require(sum <= Int.MAX_VALUE) { "extended session minutes overflow Int" }
        return sum.toInt()
    }

    /** Bounded proportional placement of the whole confirmed minutes among usable spans. */
    private fun placeEvidence(
        request: PlaytimeRecoveryRequest,
        usableSpans: List<Span>,
        total: Int,
    ): Pair<List<TimedPlacement>, Int> {
        if (usableSpans.isEmpty()) return emptyList<TimedPlacement>() to total

        val spanMillis = usableSpans.map {
            BigInteger.valueOf(it.endAt).subtract(BigInteger.valueOf(it.startAt))
        }
        val totalSpanMillis = spanMillis.fold(BigInteger.ZERO, BigInteger::add)
        if (totalSpanMillis.signum() <= 0) return emptyList<TimedPlacement>() to total

        val confirmedMinutes = (totalSpanMillis / BigInteger.valueOf(60_000L)).toLong()
        val evidenceMinutes = minOf(total.toLong(), confirmedMinutes).toInt()
        if (evidenceMinutes <= 0) return emptyList<TimedPlacement>() to total

        val allocations = largestRemainder(evidenceMinutes, spanMillis)
        val timed = usableSpans.zip(allocations)
            .filter { (_, minutes) -> minutes > 0 }
            .map { (span, minutes) ->
                TimedPlacement(
                    evidenceIds = span.evidenceIds,
                    source = span.source,
                    startAt = span.startAt,
                    endAt = span.endAt,
                    minutes = minutes,
                    date = localDate(span.startAt, request.zone),
                )
            }
        return timed to (total - timed.sumOf { it.minutes })
    }

    private fun residualRecipe(
        request: PlaytimeRecoveryRequest,
        previous: Long,
        usableSpans: List<Span>,
        residualMinutes: Int,
        diagnostics: MutableList<PlaytimeDiagnostic>,
    ): ResidualDateRecipe {
        val previousDate = localDate(previous, request.zone)
        val recoveryDate = localDate(request.recoveryObservedAt, request.zone)
        val firstWindow = if (recoveryDate.isAfter(previousDate)) previousDate.plusDays(1) else recoveryDate
        val lastWindow = recoveryDate
        // Calendar arithmetic only — a ten-year window never materializes its date list here.
        val count = if (recoveryDate.isAfter(previousDate)) {
            val days = ChronoUnit.DAYS.between(previousDate, recoveryDate)
            require(days < Int.MAX_VALUE) { "recovery window exceeds a representable day count" }
            days.toInt()
        } else {
            1
        }
        var exclusions = fullyCoveredRanges(firstWindow, lastWindow, request.zone, usableSpans)
        var eligible = count - PlaytimeAllocationRecipes.excludedCount(exclusions)
        if (eligible <= 0) {
            diagnostics += PlaytimeDiagnostic(
                PlaytimeDiagnosticCode.UNBALANCED_FULL_COVERAGE,
                "every window date claimed covered; falling back across the permitted window",
            )
            exclusions = emptyList()
            eligible = count
        }
        val base = residualMinutes / eligible
        val remainder = residualMinutes % eligible
        val method = if (count == 1) {
            PlaytimeAllocationMethod.SINGLE_DAY_ESTIMATE
        } else {
            PlaytimeAllocationMethod.MULTI_DAY_ESTIMATE
        }
        return ResidualDateRecipe(
            method = method,
            firstDate = firstWindow,
            lastDate = lastWindow,
            eligibleDateCount = eligible,
            baseMinutes = base,
            remainderCount = remainder,
            totalMinutes = residualMinutes,
            excludedRanges = exclusions,
        )
    }

    /**
     * Dates wholly covered by usable timing evidence, expressed as merged finite ranges derived
     * only from the evidence spans themselves (bounded work regardless of window length).
     *
     * Coverage is judged on the source-agnostic contiguous union: adjacent LOCAL/CLOUD fragments
     * that together span a whole day cover it, while a genuine uncovered gap still breaks it.
     */
    private fun fullyCoveredRanges(
        windowStart: LocalDate,
        windowEnd: LocalDate,
        zone: ZoneId,
        spans: List<Span>,
    ): List<ClosedRange<LocalDate>> {
        val contiguous = mergeContiguousByTime(spans)
        val ranges = contiguous.mapNotNull { span -> fullDateRange(span, windowStart, windowEnd, zone) }
        return PlaytimeAllocationRecipes.normalizeRanges(ranges)
    }

    /** Merge spans that touch or overlap, ignoring source, without ever bridging a real gap. */
    private fun mergeContiguousByTime(spans: List<Span>): List<Span> {
        val ordered = spans.sortedBy { it.startAt }
        val merged = mutableListOf<Span>()
        for (span in ordered) {
            val last = merged.lastOrNull()
            if (last != null && span.startAt <= last.endAt) {
                merged[merged.lastIndex] = Span(
                    startAt = last.startAt,
                    endAt = maxOf(last.endAt, span.endAt),
                    source = last.source,
                    evidenceIds = (last.evidenceIds + span.evidenceIds).distinct().sorted(),
                )
            } else {
                merged += span
            }
        }
        return merged
    }

    private fun fullDateRange(
        span: Span,
        windowStart: LocalDate,
        windowEnd: LocalDate,
        zone: ZoneId,
    ): ClosedRange<LocalDate>? {
        val first = localDate(span.startAt, zone)
        val startsInside = first.atStartOfDay(zone).toInstant().toEpochMilli() < span.startAt
        val firstFull = if (startsInside) first.plusDays(1) else first
        val last = localDate(span.endAt, zone)
        val endsInside = last.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() > span.endAt
        val lastFull = if (endsInside) last.minusDays(1) else last
        if (lastFull.isBefore(firstFull)) return null
        val start = maxOf(firstFull, windowStart)
        val end = minOf(lastFull, windowEnd)
        if (end.isBefore(start)) return null
        return start..end
    }

    /** Validated, window-clipped, unioned, consumed-subtracted segments; gaps are never bridged. */
    private fun usableSpans(
        request: PlaytimeRecoveryRequest,
        previous: Long,
        diagnostics: MutableList<PlaytimeDiagnostic>,
    ): List<Span> {
        val windowEnd = request.recoveryObservedAt
        val candidates = request.evidence.flatMap { evidence ->
            validateEvidence(evidence, request, previous, windowEnd, diagnostics)
        }
        val unioned = unionSegments(candidates)
        val consumed = mergeConsumed(request.consumedSpans)
        val subtracted = unioned.flatMap { subtract(it, consumed) }.filter { it.endAt > it.startAt }
        return subtracted.sortedWith(
            compareBy<Span> { it.startAt }
                .thenBy { it.endAt }
                .thenBy { it.evidenceIds.firstOrNull().orEmpty() },
        )
    }

    private fun validateEvidence(
        evidence: PlaytimeTimingEvidence,
        request: PlaytimeRecoveryRequest,
        windowStart: Long,
        windowEnd: Long,
        diagnostics: MutableList<PlaytimeDiagnostic>,
    ): List<Span> {
        if (evidence.appId != request.appId) {
            diagnostics += PlaytimeDiagnostic(
                PlaytimeDiagnosticCode.WRONG_APP,
                "evidence ${evidence.evidenceId} appId=${evidence.appId}",
            )
            return emptyList()
        }
        // Every validated candidate carries an explicit account and it must match the request.
        val account = evidence.account
        if (account == null || account != request.account) {
            diagnostics += PlaytimeDiagnostic(
                PlaytimeDiagnosticCode.WRONG_ACCOUNT,
                "evidence ${evidence.evidenceId} account=${account ?: "missing"}",
            )
            return emptyList()
        }
        if (evidence.source == PlaytimeEvidenceSource.CLOUD) {
            if (request.activeReaderIdentity == null) {
                diagnostics += PlaytimeDiagnostic(
                    PlaytimeDiagnosticCode.CLOUD_WITHOUT_ACTIVE_IDENTITY,
                    "cloud evidence ${evidence.evidenceId} without an active reader fence",
                )
                return emptyList()
            }
            if (evidence.readerIdentity != request.activeReaderIdentity) {
                diagnostics += PlaytimeDiagnostic(
                    PlaytimeDiagnosticCode.STALE_GENERATION,
                    "evidence ${evidence.evidenceId} identity=${evidence.readerIdentity}",
                )
                return emptyList()
            }
        }
        if (evidence.source == PlaytimeEvidenceSource.ESTIMATE) {
            diagnostics += PlaytimeDiagnostic(
                PlaytimeDiagnosticCode.ESTIMATE_NOT_VALID,
                "estimate evidence ${evidence.evidenceId} is not confirmed timing",
            )
            return emptyList()
        }
        if (evidence.endAt <= evidence.startAt) {
            diagnostics += PlaytimeDiagnostic(
                PlaytimeDiagnosticCode.EMPTY_INTERVAL,
                "evidence ${evidence.evidenceId} [${evidence.startAt}, ${evidence.endAt})",
            )
            return emptyList()
        }
        val gapFrom = evidence.gapFromAt
        val gapTo = evidence.gapRecoveredAt
        if ((gapFrom == null) != (gapTo == null)) {
            diagnostics += PlaytimeDiagnostic(
                PlaytimeDiagnosticCode.INCOMPLETE_GAP,
                "evidence ${evidence.evidenceId} has one-sided gap",
            )
            return emptyList()
        }
        if (gapFrom != null) {
            // Endpoint subtraction in wider arithmetic: raw Long subtraction could overflow.
            val gap = BigInteger.valueOf(gapTo!!).subtract(BigInteger.valueOf(gapFrom))
            if (gap.signum() < 0 || gap > BigInteger.valueOf(request.gapToleranceMillis)) {
                diagnostics += PlaytimeDiagnostic(
                    PlaytimeDiagnosticCode.GAP_ABOVE_TOLERANCE,
                    "evidence ${evidence.evidenceId} gap=$gap ms tolerance=${request.gapToleranceMillis}ms",
                )
                return emptyList()
            }
        }
        val start = maxOf(evidence.startAt, windowStart)
        val end = minOf(evidence.endAt, windowEnd)
        if (end <= start) {
            diagnostics += PlaytimeDiagnostic(
                PlaytimeDiagnosticCode.OUTSIDE_WINDOW,
                "evidence ${evidence.evidenceId} outside [$windowStart, $windowEnd]",
            )
            return emptyList()
        }
        // A sub-tolerance gap is a real uncovered cut: it subtracts from the usable duration and
        // breaks continuity. The gap is clipped to the clipped interval so an out-of-range gap can
        // never fabricate a segment beyond it.
        val gapClipped = gapFrom?.let { from ->
            val to = gapTo!!
            val clippedStart = maxOf(from, start)
            val clippedEnd = minOf(to, end)
            (clippedStart to clippedEnd).takeIf { it.second > it.first }
        }
        val segments = if (gapClipped == null) {
            listOf(start to end)
        } else {
            val (gapStart, gapEnd) = gapClipped
            listOfNotNull(
                (start to gapStart).takeIf { gapStart > start },
                (gapEnd to end).takeIf { gapEnd < end },
            )
        }
        return segments
            .filter { it.second > it.first }
            .map { (s, e) -> Span(s, e, evidence.source, listOf(evidence.evidenceId)) }
    }

    /**
     * Split overlapping evidence into per-boundary segments so an uncovered gap is never bridged
     * and a cloud span never claims local certainty outside the actual local portion.
     */
    private fun unionSegments(spans: List<Span>): List<Span> {
        if (spans.isEmpty()) return emptyList()
        val bounds = spans.flatMap { listOf(it.startAt, it.endAt) }.distinct().sorted()
        val segments = mutableListOf<Span>()
        for (index in 0 until bounds.lastIndex) {
            val a = bounds[index]
            val b = bounds[index + 1]
            if (b <= a) continue
            val covering = spans.filter { it.startAt <= a && it.endAt >= b }
            if (covering.isEmpty()) continue
            val source = covering.minByOrNull { it.source.ordinal }!!.source
            val ids = covering.flatMap { it.evidenceIds }.distinct().sorted()
            segments += Span(a, b, source, ids)
        }
        return mergeIdentical(segments)
    }

    private fun mergeIdentical(spans: List<Span>): List<Span> {
        val merged = mutableListOf<Span>()
        for (span in spans) {
            val last = merged.lastOrNull()
            if (last != null && span.startAt == last.endAt &&
                span.source == last.source && span.evidenceIds == last.evidenceIds
            ) {
                merged[merged.lastIndex] = Span(last.startAt, span.endAt, span.source, last.evidenceIds)
            } else {
                merged += span
            }
        }
        return merged
    }

    private fun mergeConsumed(consumed: List<ConsumedPlaytimeSpan>): List<LongRange> =
        consumed
            .filter { it.endAt > it.startAt }
            .sortedBy { it.startAt }
            .fold(mutableListOf<LongRange>()) { acc, span ->
                val last = acc.lastOrNull()
                if (last != null && span.startAt <= last.last) {
                    acc[acc.lastIndex] = last.first..maxOf(last.last, span.endAt)
                } else {
                    acc += span.startAt..span.endAt
                }
                acc
            }

    private fun subtract(span: Span, consumed: List<LongRange>): List<Span> {
        var segments = listOf(span.startAt to span.endAt)
        for (cut in consumed) {
            segments = segments.flatMap { (start, end) ->
                when {
                    cut.last <= start || cut.first >= end -> listOf(start to end)
                    else -> buildList {
                        if (cut.first > start) add(start to cut.first)
                        if (cut.last < end) add(cut.last to end)
                    }
                }
            }
        }
        return segments.map { (start, end) -> Span(start, end, span.source, span.evidenceIds) }
    }

    /**
     * Largest-remainder split of [total] by [weights]. Callers pass weights in deterministic order
     * (start, end, first evidence id), so the input index is the stable tie break.
     */
    private fun largestRemainder(total: Int, weights: List<BigInteger>): List<Int> {
        val sum = weights.fold(BigInteger.ZERO, BigInteger::add)
        if (total <= 0 || sum.signum() <= 0) return List(weights.size) { 0 }
        val exact = weights.map { BigInteger.valueOf(total.toLong()) * it }
        val result = exact.map { it.divide(sum).toInt() }.toMutableList()
        var remainder = total - result.sum()
        if (remainder > 0) {
            val orderIndices = exact.indices.sortedWith(
                compareByDescending<Int> { exact[it].remainder(sum) }.thenBy { it },
            )
            for (index in orderIndices) {
                if (remainder == 0) break
                result[index]++
                remainder--
            }
        }
        check(result.sum() == total) { "largest-remainder split lost quantity" }
        return result
    }

    /** Whether a chain of usable segments covers [from, to) with no uncovered break. */
    private fun coveredBy(spans: List<Span>, from: Long, to: Long): Boolean {
        val ordered = spans
            .filter { it.endAt > from && it.startAt < to }
            .sortedBy { it.startAt }
        var cursor = from
        for (span in ordered) {
            if (span.startAt > cursor) return false
            cursor = maxOf(cursor, span.endAt)
            if (cursor >= to) return true
        }
        return cursor >= to
    }

    private fun localDate(epochMillis: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()

    private fun ceilMinutes(elapsedMillis: Long): Long =
        if (elapsedMillis <= 0L) 0L else {
            elapsedMillis / 60_000L + if (elapsedMillis % 60_000L == 0L) 0L else 1L
        }

    private data class Span(
        val startAt: Long,
        val endAt: Long,
        val source: PlaytimeEvidenceSource,
        val evidenceIds: List<String>,
    )
}
