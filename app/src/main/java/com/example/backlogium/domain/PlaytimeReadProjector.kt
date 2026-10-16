package com.example.backlogium.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Positive authorized minutes of one funded contribution (design.md Decision 2). */
data class ContributionCredit(val appId: Long, val authorizedMinutes: Long)

/** A timestamped session row's quantity facts. */
data class SessionCredit(
    val id: Long,
    val appId: Long,
    val startAt: Long,
    val minutes: Int,
    val open: Boolean,
    val endAt: Long? = null,
)

/**
 * A funded timed share. [sessionId] null means a retained-but-detached share whose minutes are not
 * carried by any stored session row. [creditedDate] is the frozen attribution date that every dated
 * projection must use for this share's minutes — hosted shares are *not* re-dated by the session
 * row's own start instant.
 */
data class TimedShareCredit(
    val placementId: String,
    val contributionId: String,
    val appId: Long,
    val sessionId: Long?,
    val minutes: Long,
    val creditedDate: LocalDate?,
    val frozenZone: String? = null,
    val evidenceQuality: String? = null,
    val goalAtCredit: Boolean? = null,
)

/** A placed unknown-time allocation; [date] null with [pendingDate] marks pending quantity. */
data class DatedAllocationCredit(
    val placementId: String,
    val contributionId: String,
    val appId: Long,
    val date: LocalDate?,
    val minutes: Long,
    val method: PlaytimeAllocationMethod,
    val frozenZone: String? = null,
    val windowStartAt: Long? = null,
    val windowEndAt: Long? = null,
    val pendingDate: Boolean = false,
    val hiddenAtCredit: Boolean = false,
    val goalAtCredit: Boolean? = null,
)

/**
 * A compact residual-window recipe (design.md Decision 7). Dates at or before
 * [materializedThroughDate] are already represented by allocation rows; every date strictly after
 * the cursor derives its share from the recipe's quotient/remainder arithmetic, so a reader
 * combines materialized rows and recipe-derived credit exactly once. A fully materialized recipe
 * still describes an unresolved multi-day estimate window — that label is a placement fact, not a
 * materialization state.
 */
data class RecipeCredit(
    val contributionId: String,
    val appId: Long,
    val method: PlaytimeAllocationMethod,
    val dateStart: LocalDate?,
    val dateEnd: LocalDate?,
    val baseMinutes: Long,
    val remainderCount: Long,
    val eligibleCount: Int,
    val fullyResolvedDates: List<LocalDate>,
    val materializedThroughDate: LocalDate?,
    val pending: Boolean = false,
    val frozenZone: String? = null,
    val windowStartAt: Long? = null,
    val windowEndAt: Long? = null,
    val goalAtCredit: Boolean? = null,
    /** Compact, inclusive fully-resolved ranges from finite recipe exclusions. */
    val fullyResolvedDateRanges: List<ClosedRange<LocalDate>> = emptyList(),
) {
    /** The residual total this recipe covers, before any materialization. */
    val residualTotalMinutes: Long
        get() = checkedAdd(checkedTimes(baseMinutes, eligibleCount.toLong()), remainderCount)
}

/**
 * Pure read projection of canonical credited activity (recover-missed-playtime, design.md
 * Decision 7). It has no Android, Room or network dependency and never fabricates a gameplay
 * instant: unknown-time activity is represented by dates alone.
 *
 * The load-bearing conservation identity behind every quantity here:
 * `authorized minutes per contribution = active timed shares + dated allocations + pending shares`
 * (with any residual not yet materialized from the recipe), so summing a contribution's
 * authorized quantity once and each session's *unlinked legacy remainder* (session minutes minus
 * its active funded timed shares) counts every credited minute exactly once. Dated projections
 * keep funded timed shares on their frozen [TimedShareCredit.creditedDate] — a session's own
 * current-zone start date re-buckets only its *unlinked* remainder, never a validated share.
 */
object PlaytimeReadProjector {

    /**
     * Canonical per-game credited minutes: every funded contribution counts once and each
     * timestamped session contributes only its unlinked legacy remainder (its minutes minus its
     * active funded timed shares). Hosted shares are validated before summing — an unknown hosting
     * session, an app-id mismatch, or funded shares exceeding the session's minutes is an
     * inconsistent mapping and is rejected rather than silently erasing mixed legacy. All sums use
     * checked wide arithmetic.
     *
     * A family-shared game has no owned contribution, so it keeps exactly its existing session
     * total. Pending date-unknown quantity is included here (it feeds cumulative XP), which is
     * why [creditedMinutesByGame] differs from [datedCreditedTotals], where pending stays out of
     * dated charts.
     */
    fun creditedMinutesByGame(
        contributions: List<ContributionCredit>,
        sessions: List<SessionCredit>,
        timedShares: List<TimedShareCredit>,
    ): Map<Long, Long> {
        val fundedByGame = mutableMapOf<Long, Long>()
        for (contribution in contributions) {
            fundedByGame.merge(contribution.appId, contribution.authorizedMinutes, ::checkedAdd)
        }
        val sharesBySession = validatedSharesBySession(sessions, timedShares)
        val unlinkedByGame = mutableMapOf<Long, Long>()
        for (session in sessions) {
            val funded = sharesBySession[session.id]?.sumOf { it.minutes } ?: 0L
            require(funded <= session.minutes.toLong()) {
                "overfunded session ${session.id}: $funded funded share(s) exceed its ${session.minutes} minutes"
            }
            val remainder = session.minutes.toLong() - funded
            if (remainder > 0) unlinkedByGame.merge(session.appId, remainder, ::checkedAdd)
        }
        val appIds = fundedByGame.keys + unlinkedByGame.keys
        return appIds.associateWith { appId ->
            checkedAdd(fundedByGame[appId] ?: 0L, unlinkedByGame[appId] ?: 0L)
        }
    }

    /**
     * Session activity rows for the slice [startDateInclusive, endDateExclusive), converted with
     * [zone], carrying the funded-share split (each share's frozen credited date) and the unlinked
     * remainder. Overfunded or inconsistent sessions are rejected.
     */
    fun sessionActivities(
        sessions: List<SessionCredit>,
        timedShares: List<TimedShareCredit>,
        zone: ZoneId,
        startDateInclusive: LocalDate,
        endDateExclusive: LocalDate,
        allTimedSharesForRemainder: List<TimedShareCredit> = timedShares,
    ): List<PlaytimeActivity.TimestampedSession> {
        val completeShares = completeTimedShares(timedShares, allTimedSharesForRemainder)
        val sharesBySession = validatedSharesBySession(sessions, completeShares)
        return sessions.asSequence()
            .map { session ->
                val shares = sharesBySession[session.id].orEmpty()
                val funded = shares.sumOf { it.minutes }
                require(funded <= session.minutes.toLong()) {
                    "overfunded session ${session.id}: $funded funded share(s) exceed its ${session.minutes} minutes"
                }
                PlaytimeActivity.TimestampedSession(
                    appId = session.appId,
                    id = session.id,
                    startAt = session.startAt,
                    endAt = session.endAt,
                    minutes = session.minutes,
                    open = session.open,
                    fundedShares = shares.map {
                        val creditedDate = requireNotNull(it.creditedDate) {
                            "funded share ${it.contributionId} has no frozen credited date"
                        }
                        SessionFundedShare(
                            contributionId = it.contributionId,
                            minutes = it.minutes,
                            creditedDate = creditedDate,
                            frozenZone = it.frozenZone,
                            goalAtCredit = it.goalAtCredit,
                        )
                    },
                    fundedMinutes = funded,
                    unlinkedMinutes = session.minutes.toLong() - funded,
                )
            }
            .filter { activity ->
                val date = sessionDate(activity.startAt, zone)
                (date >= startDateInclusive && date < endDateExclusive) ||
                    timedShares.any { share ->
                        share.sessionId == activity.id && share.creditedDate?.let {
                            it >= startDateInclusive && it < endDateExclusive
                        } == true
                    }
            }
            .toList()
    }

    /** Dated (non-pending) unknown-time allocation activity, enriched for `!` explanations. */
    fun allocationActivities(
        allocations: List<DatedAllocationCredit>,
        recipeTotalsByContribution: Map<String, Long> = emptyMap(),
    ): List<PlaytimeActivity.DatedAllocation> {
        // Window total per (contribution, window endpoints): the residual this window represents,
        // used when the storage layer retains no recipe for the window.
        val windowTotals = allocations
            .filter { !it.pendingDate }
            .groupBy { windowKey(it) }
            .mapValues { (_, rows) -> rows.sumOf { it.minutes } }
        return allocations
            .filter { !it.pendingDate && it.date != null }
            .map { allocation ->
                val date = allocation.date!!
                val windowStart = allocation.windowStartAt
                val windowEnd = allocation.windowEndAt
                allocate(
                    contributionId = allocation.contributionId,
                    appId = allocation.appId,
                    date = date,
                    selectedDayMinutes = allocation.minutes,
                    frozenZone = allocation.frozenZone,
                    windowDateStart = windowStart?.let { windowDate(it, allocation.frozenZone) },
                    windowDateEnd = windowEnd?.let { windowDate(it, allocation.frozenZone) },
                    residualWindowTotalMinutes = recipeTotalsByContribution[allocation.contributionId]
                        ?: windowTotals[windowKey(allocation)] ?: allocation.minutes,
                    method = allocation.method,
                    placementId = allocation.placementId,
                    goalAtCredit = allocation.goalAtCredit,
                    windowStartAt = windowStart,
                    windowEndAt = windowEnd,
                )
            }
    }

    /** Detached funded timed shares (no hosting session row) as dated activity on their frozen date. */
    fun detachedTimedActivities(shares: List<TimedShareCredit>): List<PlaytimeActivity.DatedAllocation> {
        return shares
            .filter { it.sessionId == null && it.creditedDate != null }
            .map { share ->
                val date = requireNotNull(share.creditedDate) {
                    "detached timed share ${share.contributionId} has no frozen credited date"
                }
                allocate(
                    contributionId = share.contributionId,
                    appId = share.appId,
                    date = date,
                    selectedDayMinutes = share.minutes,
                    frozenZone = share.frozenZone,
                    windowDateStart = date,
                    windowDateEnd = date,
                    residualWindowTotalMinutes = share.minutes,
                    method = PlaytimeAllocationMethod.EVIDENCE_INFORMED,
                    placementId = share.placementId,
                    goalAtCredit = share.goalAtCredit,
                    evidenceQuality = share.evidenceQuality,
                )
            }
    }

    /**
     * Recipe-derived dated activity for the unmaterialized tail of each window (dates strictly
     * after [RecipeCredit.materializedThroughDate]). Work is proportional to the requested slice,
     * never to the whole window; zero-share dates produce no rows, and every row carries the
     * recipe's window identity/total for `!` explanations.
     */
    fun recipeActivities(
        recipes: List<RecipeCredit>,
        startDateInclusive: LocalDate,
        endDateExclusive: LocalDate,
    ): List<PlaytimeActivity.DatedAllocation> {
        val activities = mutableListOf<PlaytimeActivity.DatedAllocation>()
        for (recipe in recipes) {
            if (recipe.pending) continue
            val windowStart = recipe.dateStart ?: continue
            val windowEnd = recipe.dateEnd ?: continue
            val resumeAt = recipe.materializedThroughDate?.plusDays(1) ?: windowStart
            val first = maxOf(resumeAt, windowStart, startDateInclusive)
            val last = minOf(windowEnd, endDateExclusive.minusDays(1))
            if (last.isBefore(first)) continue
            val excluded = PlaytimeAllocationRecipes.normalizeRanges(
                sortedRanges(recipe.fullyResolvedDates) + recipe.fullyResolvedDateRanges,
            )
            var eligibleIndex = eligibleBefore(excluded, windowStart, first)
            var date = first
            while (!date.isAfter(last)) {
                if (!PlaytimeAllocationRecipes.isExcluded(excluded, date)) {
                    val selectedMinutes = checkedAdd(
                        recipe.baseMinutes,
                        if (eligibleIndex < recipe.remainderCount) 1L else 0L,
                    )
                    if (selectedMinutes > 0) {
                        activities += allocate(
                            contributionId = recipe.contributionId,
                            appId = recipe.appId,
                            date = date,
                            selectedDayMinutes = selectedMinutes,
                            frozenZone = recipe.frozenZone,
                            windowDateStart = windowStart,
                            windowDateEnd = windowEnd,
                            residualWindowTotalMinutes = recipe.residualTotalMinutes,
                            method = recipe.method,
                            placementId = "recipe|${recipe.contributionId}|$date",
                            goalAtCredit = recipe.goalAtCredit,
                            windowStartAt = recipe.windowStartAt,
                            windowEndAt = recipe.windowEndAt,
                        )
                    }
                    eligibleIndex++
                }
                date = date.plusDays(1)
            }
        }
        return activities
    }

    /** Every dated activity kind for a slice, newest date first. */
    fun datedActivityBetween(
        sessions: List<SessionCredit>,
        timedShares: List<TimedShareCredit>,
        allocations: List<DatedAllocationCredit>,
        recipes: List<RecipeCredit>,
        zone: ZoneId,
        startDateInclusive: LocalDate,
        endDateExclusive: LocalDate,
        allTimedSharesForRemainder: List<TimedShareCredit> = timedShares,
    ): List<PlaytimeActivity> {
        val recipeTotals = recipes.filterNot { it.pending }.associate { it.contributionId to it.residualTotalMinutes }
        val activities = mutableListOf<PlaytimeActivity>()
        activities += sessionActivities(
            sessions = sessions,
            timedShares = timedShares,
            zone = zone,
            startDateInclusive = startDateInclusive,
            endDateExclusive = endDateExclusive,
            allTimedSharesForRemainder = allTimedSharesForRemainder,
        )
        activities += allocationActivities(allocations, recipeTotals)
            .filter { it.date >= startDateInclusive && it.date < endDateExclusive }
        activities += detachedTimedActivities(timedShares)
            .filter { it.date >= startDateInclusive && it.date < endDateExclusive }
        activities += recipeActivities(recipes, startDateInclusive, endDateExclusive)
        return activities.sortedWith(
            compareByDescending<PlaytimeActivity> { activityDate(it, zone) }.thenBy { it.appId },
        )
    }

    /**
     * Dated unknown-time allocations for an *inclusive* `[startDateInclusive, endDateInclusive]`
     * window as History/Analytics render them (tasks 7.1/7.2): materialized rows, detached timed
     * shares and the unmaterialized recipe tail, each carrying the identity/window context its `!`
     * explanation needs. Pending date-unknown quantity is excluded by construction.
     */
    fun creditedAllocationsBetween(
        timedShares: List<TimedShareCredit>,
        allocations: List<DatedAllocationCredit>,
        recipes: List<RecipeCredit>,
        startDateInclusive: LocalDate,
        endDateInclusive: LocalDate,
    ): List<CreditedAllocation> {
        val recipeTotals = recipes.filterNot { it.pending }.associate { it.contributionId to it.residualTotalMinutes }
        return (allocationActivities(allocations, recipeTotals) +
            detachedTimedActivities(timedShares) +
            recipeActivities(recipes, startDateInclusive, endDateInclusive.plusDays(1)))
            .filter { it.date >= startDateInclusive && it.date <= endDateInclusive }
            .map { it.toCreditedAllocation() }
            .sortedWith(compareByDescending<CreditedAllocation> { it.date }.thenBy { it.appId })
    }

    /**
     * Per-date canonical credited totals for a window. Each session contributes only its *unlinked*
     * remainder on its current-zone start date, while **every** funded timed share — hosted or
     * detached — is credited on its own frozen [TimedShareCredit.creditedDate] exactly once, so a
     * travel-induced zone shift can never re-bucket a validated share to the session's current-zone
     * date. Dated allocations and the recipe tail add their frozen dates too. Pending date-unknown
     * quantity is deliberately absent — it participates in XP, never in dated charts.
     */
    fun datedCreditedTotals(
        sessions: List<SessionCredit>,
        timedShares: List<TimedShareCredit>,
        allocations: List<DatedAllocationCredit>,
        recipes: List<RecipeCredit>,
        zone: ZoneId,
        startDateInclusive: LocalDate,
        endDateExclusive: LocalDate,
        allTimedSharesForRemainder: List<TimedShareCredit> = timedShares,
    ): List<DatedCreditedTotal> {
        val minutesByDate = sortedMapOf<LocalDate, MutableMap<Long, Long>>()
        fun add(date: LocalDate, appId: Long, minutes: Long) {
            if (date < startDateInclusive || date >= endDateExclusive || minutes <= 0) return
            minutesByDate.getOrPut(date) { mutableMapOf() }
                .merge(appId, minutes, ::checkedAdd)
        }

        // 1) Unlinked legacy remainder follows the session's own start-date attribution.
        val sharesBySession = validatedSharesBySession(
            sessions,
            completeTimedShares(timedShares, allTimedSharesForRemainder),
        )
        for (session in sessions) {
            val funded = sharesBySession[session.id]?.sumOf { it.minutes } ?: 0L
            require(funded <= session.minutes.toLong()) {
                "overfunded session ${session.id}: $funded funded share(s) exceed its ${session.minutes} minutes"
            }
            add(sessionDate(session.startAt, zone), session.appId, session.minutes.toLong() - funded)
        }
        // 2) Every funded timed share lands on its frozen credited date exactly once (hosted + detached).
        for (share in timedShares) {
            val frozenDate = requireNotNull(share.creditedDate) {
                "funded share ${share.contributionId} has no frozen credited date"
            }
            add(frozenDate, share.appId, share.minutes)
        }
        // 3) Dated allocations and the unmaterialized recipe tail. Detached timed shares were
        // already credited exactly once by loop 2, so they are not added again here.
        val recipeTotals = recipes.filterNot { it.pending }.associate { it.contributionId to it.residualTotalMinutes }
        for (allocation in allocationActivities(allocations, recipeTotals)) add(allocation.date, allocation.appId, allocation.minutes)
        for (allocation in recipeActivities(recipes, startDateInclusive, endDateExclusive)) {
            add(allocation.date, allocation.appId, allocation.minutes)
        }
        return minutesByDate.map { (date, byGame) ->
            DatedCreditedTotal(date = date, minutes = byGame.values.sum(), minutesByGame = byGame)
        }
    }

    /**
     * Personal Pace training totals for the inclusive `[firstDateInclusive, lastDateInclusive]`
     * window (tasks 9.1-9.3): closed sessions deposit their *unlinked legacy remainder* on their
     * current-zone start date, every funded timed share of a **closed** session (plus detached
     * shares) trains on its **frozen credited date**, and dated allocations plus the bounded
     * recipe tail add their frozen dates. Open sessions contribute nothing — even when a hosted
     * share's frozen date falls inside the window — and pending date-unknown quantity is absent.
     * Multi-day estimate dates are included here but masked by the caller's window mask, so they
     * never train and never synthesize zero-play days.
     *
     * The inputs are already window-scoped (bounded reads): sessions are the closed window
     * sessions plus the host sessions of the window's shares. A funded share frozen more than the
     * lookback before its host session's date is loaded for the host rows too, so it still reduces
     * the session's remainder without being credited outside the requested date slice.
     */
    fun trainingTotalsBetween(
        sessions: List<SessionCredit>,
        timedShares: List<TimedShareCredit>,
        allocations: List<DatedAllocationCredit>,
        recipes: List<RecipeCredit>,
        zone: ZoneId,
        firstDateInclusive: LocalDate,
        lastDateInclusive: LocalDate,
        allTimedSharesForRemainder: List<TimedShareCredit> = timedShares,
    ): Map<LocalDate, Long> {
        val closedById = sessions.filterNot { it.open }.associateBy { it.id }
        val completeSharesBySession = validatedSharesBySession(
            sessions,
            completeTimedShares(timedShares, allTimedSharesForRemainder),
        )
        val perDate = mutableMapOf<LocalDate, Long>()
        fun add(date: LocalDate, minutes: Long, retainObservedZero: Boolean = false) {
            if (minutes < 0 || date < firstDateInclusive || date > lastDateInclusive) return
            if (minutes == 0L) {
                if (retainObservedZero) perDate.putIfAbsent(date, 0L)
                return
            }
            perDate.merge(date, minutes, ::checkedAdd)
        }
        for (share in timedShares) {
            val sessionId = share.sessionId
            if (sessionId != null) {
                // A share hosted by an open (or absent) session never trains, even when its
                // frozen date lands inside the window.
                val host = closedById[sessionId] ?: continue
                if (host.appId != share.appId) {
                    throw IllegalArgumentException(
                        "funded share ${share.contributionId} appId=${share.appId} does not match " +
                            "hosting session $sessionId appId=${host.appId}",
                    )
                }
            }
            val frozenDate = requireNotNull(share.creditedDate) {
                "funded share ${share.contributionId} has no frozen credited date"
            }
            add(frozenDate, share.minutes)
        }
        for (session in sessions) {
            if (session.open) continue
            val funded = completeSharesBySession[session.id]?.sumOf { it.minutes } ?: 0L
            require(funded <= session.minutes.toLong()) {
                "overfunded session ${session.id}: $funded funded share(s) exceed its ${session.minutes} minutes"
            }
            add(
                sessionDate(session.startAt, zone),
                session.minutes.toLong() - funded,
                retainObservedZero = true,
            )
        }
        val recipeTotals = recipes.filterNot { it.pending }.associate { it.contributionId to it.residualTotalMinutes }
        for (allocation in allocationActivities(allocations, recipeTotals)) add(allocation.date, allocation.minutes)
        for (allocation in recipeActivities(recipes, firstDateInclusive, lastDateInclusive.plusDays(1))) {
            add(allocation.date, allocation.minutes)
        }
        return perDate
    }

    /**
     * Earliest canonical dated activity: the first session start date, first *positive* dated
     * allocation, first frozen timed-share date, or first positive recipe-derived date —
     * whichever came first. Excluded/zero rows never anchor history, and a recipe whose early
     * dates are fully resolved does not drag the anchor backwards.
     */
    fun earliestCanonicalDate(
        sessions: List<SessionCredit>,
        timedShares: List<TimedShareCredit>,
        allocations: List<DatedAllocationCredit>,
        recipes: List<RecipeCredit>,
        zone: ZoneId,
    ): LocalDate? {
        val dates = buildSet {
            sessions.forEach { add(sessionDate(it.startAt, zone)) }
            allocations.forEach {
                if (!it.pendingDate && it.date != null) add(it.date)
            }
            timedShares.forEach { it.creditedDate?.let(::add) }
            recipes.forEach { recipe -> recipeEarliestPositiveDate(recipe)?.let(::add) }
        }
        return dates.minOrNull()
    }

    /**
     * Recovery estimate windows for Personal Pace eligibility. Only genuinely multi-day estimate
     * recipes produce windows — a fully materialized recipe still labels its window unresolved —
     * and single-day/known-targeted-date activity stays trainable.
     */
    fun recoveryEstimateWindows(recipes: List<RecipeCredit>): List<RecoveryEstimateWindow> =
        recipes
            .filter { !it.pending && it.method == PlaytimeAllocationMethod.MULTI_DAY_ESTIMATE }
            .mapNotNull { recipe ->
                val start = recipe.dateStart ?: return@mapNotNull null
                val end = recipe.dateEnd ?: return@mapNotNull null
                RecoveryEstimateWindow(
                    contributionId = recipe.contributionId,
                    appId = recipe.appId,
                    dateStart = start,
                    dateEnd = end,
                    fullyResolvedDates = recipe.fullyResolvedDates.toSet(),
                    fullyResolvedDateRanges = recipe.fullyResolvedDateRanges,
                )
            }

    /**
     * Recovery estimate windows recovered from allocation rows alone when storage retained no
     * recipe (e.g. an independently imported multi-day graph). The window spans the full observed
     * window — the date after the normalized previous observation through the recovery date, in
     * the rows' frozen zone — so zero-share gap dates stay unknown, never synthesized as observed
     * zero-play days. Positive-share dates alone would under-mask; the stored window metadata is
     * authoritative here. A retained recipe for the same contribution always wins (its exclusions
     * are known); callers pass only contributions without a recipe to this fallback.
     */
    fun allocationEstimateWindows(allocations: List<DatedAllocationCredit>): List<RecoveryEstimateWindow> {
        val multiDay = allocations
            .filter { !it.pendingDate && it.method == PlaytimeAllocationMethod.MULTI_DAY_ESTIMATE }
        return multiDay
            .groupBy { Triple(it.contributionId, it.windowStartAt, it.windowEndAt) }
            .mapNotNull { (key, rows) ->
                val zone = rows.firstNotNullOfOrNull { it.frozenZone }
                val windowStart = key.second
                val windowEnd = key.third
                val allocationDates = rows.mapNotNull { it.date }
                val explicitFirst = windowStart?.let { windowDate(it, zone) }
                val explicitLast = windowEnd?.let { windowDate(it, zone) }
                val first = explicitFirst?.plusDays(1)
                    ?: allocationDates.minOrNull()
                    ?: return@mapNotNull null
                val last = maxOf(explicitLast ?: allocationDates.maxOrNull() ?: first,
                    allocationDates.maxOrNull() ?: first)
                if (last.isBefore(first)) return@mapNotNull null
                RecoveryEstimateWindow(
                    contributionId = key.first,
                    appId = rows.first().appId,
                    dateStart = first.coerceAtMost(last),
                    dateEnd = last.coerceAtLeast(first),
                )
            }
    }

    fun sessionDate(epochMillis: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()

    /** Earliest date carrying a positive recipe-derived share, or null when none exists. */
    fun earliestPositiveRecipeDate(recipe: RecipeCredit): LocalDate? = recipeEarliestPositiveDate(recipe)

    /** Map a dated allocation activity to the History/Analytics leaf model. */
    fun PlaytimeActivity.DatedAllocation.toCreditedAllocation(): CreditedAllocation = CreditedAllocation(
        placementId = placementId ?: "recipe|$contributionId|$date",
        appId = appId,
        date = date,
        minutes = selectedDayMinutes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        method = method,
        zone = frozenZone,
        windowStartDate = windowDateStart,
        windowEndDate = windowDateEnd,
        windowTotalMinutes = residualWindowTotalMinutes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        goalAtCredit = goalAtCredit,
    )

    private fun windowDate(epochMillis: Long, zone: String?): LocalDate? {
        val z = zone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: return null
        return Instant.ofEpochMilli(epochMillis).atZone(z).toLocalDate()
    }

    private fun allocate(
        contributionId: String,
        appId: Long,
        date: LocalDate,
        selectedDayMinutes: Long,
        frozenZone: String?,
        windowDateStart: LocalDate?,
        windowDateEnd: LocalDate?,
        residualWindowTotalMinutes: Long,
        method: PlaytimeAllocationMethod,
        placementId: String? = null,
        goalAtCredit: Boolean? = null,
        windowStartAt: Long? = null,
        windowEndAt: Long? = null,
        evidenceQuality: String? = null,
    ) = PlaytimeActivity.DatedAllocation(
        appId = appId,
        contributionId = contributionId,
        date = date,
        selectedDayMinutes = selectedDayMinutes,
        frozenZone = frozenZone,
        windowDateStart = windowDateStart ?: date,
        windowDateEnd = windowDateEnd ?: date,
        residualWindowTotalMinutes = residualWindowTotalMinutes,
        method = method,
        placementId = placementId,
        goalAtCredit = goalAtCredit,
        windowStartAt = windowStartAt,
        windowEndAt = windowEndAt,
        evidenceQuality = evidenceQuality,
    )

    private fun windowKey(allocation: DatedAllocationCredit) =
        Triple(allocation.contributionId, allocation.windowStartAt, allocation.windowEndAt)

    /**
     * Validated per-session share groups. Every hosted share must reference an existing session of
     * the same game; detached shares are validated separately. Called before any sum so a
     * malformed graph is rejected before it can mislead a projection.
     */
    private fun validatedSharesBySession(
        sessions: List<SessionCredit>,
        timedShares: List<TimedShareCredit>,
    ): Map<Long, List<TimedShareCredit>> {
        val sessionsById = sessions.associateBy { it.id }
        val result = mutableMapOf<Long, MutableList<TimedShareCredit>>()
        for (share in timedShares) {
            val sessionId = share.sessionId ?: continue
            val session = sessionsById[sessionId] ?: throw IllegalArgumentException(
                "funded share ${share.contributionId} references unknown session $sessionId",
            )
            require(session.appId == share.appId) {
                "funded share ${share.contributionId} appId=${share.appId} does not match hosting " +
                    "session $sessionId appId=${session.appId}"
            }
            result.getOrPut(sessionId) { mutableListOf() } += share
        }
        return result
    }

    /** Merge the dated slice with the complete host-session shares, without duplicate placement ids. */
    private fun completeTimedShares(
        datedShares: List<TimedShareCredit>,
        allSharesForRemainder: List<TimedShareCredit>,
    ): List<TimedShareCredit> = (allSharesForRemainder + datedShares)
        .distinctBy(TimedShareCredit::placementId)

    private fun recipeEarliestPositiveDate(recipe: RecipeCredit): LocalDate? {
        if (recipe.pending) return null
        val start = recipe.dateStart ?: return null
        val end = recipe.dateEnd ?: return null
        if (recipe.baseMinutes <= 0 && recipe.remainderCount <= 0) return null
        // The first eligible (non-excluded) date yields a positive share whenever base > 0 or the
        // remainder reaches at least one date.
        val cursor = recipe.materializedThroughDate
        var date = maxOf(start, cursor?.plusDays(1) ?: start)
        val excluded = PlaytimeAllocationRecipes.normalizeRanges(
            sortedRanges(recipe.fullyResolvedDates) + recipe.fullyResolvedDateRanges,
        )
        while (!date.isAfter(end)) {
            if (excluded.none { date in it }) return date
            date = date.plusDays(1)
        }
        return null
    }

    private fun activityDate(activity: PlaytimeActivity, zone: ZoneId): LocalDate = when (activity) {
        is PlaytimeActivity.TimestampedSession -> sessionDate(activity.startAt, zone)
        is PlaytimeActivity.DatedAllocation -> activity.date
        is PlaytimeActivity.PendingDateUnknown -> LocalDate.MAX
    }

    /** Number of eligible (non-excluded) dates in [windowStart, before). */
    private fun eligibleBefore(
        excluded: List<ClosedRange<LocalDate>>,
        windowStart: LocalDate,
        before: LocalDate,
    ): Long {
        if (before.isBefore(windowStart)) return 0L
        val days = PlaytimeAllocationRecipes.calendarDays(windowStart, before.minusDays(1))
        val excludedBefore = PlaytimeAllocationRecipes.excludedCount(excluded, windowStart, before.minusDays(1))
        return (days - excludedBefore).coerceAtLeast(0).toLong()
    }

    /** Sorted, merged, non-overlapping exclusion ranges from individual dates. */
    private fun sortedRanges(dates: List<LocalDate>): List<ClosedRange<LocalDate>> {
        if (dates.isEmpty()) return emptyList()
        val sorted = dates.distinct().sorted()
        val ranges = mutableListOf<ClosedRange<LocalDate>>()
        var start = sorted.first()
        var end = sorted.first()
        for (date in sorted.drop(1)) {
            if (date == end.plusDays(1)) {
                end = date
            } else {
                ranges += start..end
                start = date
                end = date
            }
        }
        ranges += start..end
        return ranges
    }
}

/** File-scope checked wide arithmetic shared by the recipe model and the projector. */
private fun checkedAdd(a: Long, b: Long): Long {
    val sum = a + b
    require((a xor sum) and (b xor sum) >= 0) { "Long sum overflow: $a + $b" }
    return sum
}

private fun checkedTimes(a: Long, b: Long): Long {
    val product = a * b
    require((a == 0L || product / a == b) && !(a == Long.MIN_VALUE && b == -1L)) {
        "Long product overflow: $a * $b"
    }
    return product
}
