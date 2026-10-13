package com.example.backlogium.domain

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pure quantity/date projection rules of the canonical read model (recover-missed-playtime,
 * design.md Decision 7 + 9). No Room, no Android: every fixture here pins the conservation
 * identity `contribution authorized = timed shares + dated + pending (+ unmaterialized recipe)`,
 * the frozen-date attribution of funded shares, and the distinction between quantity-bearing
 * projections and date-only projections.
 */
class PlaytimeReadProjectorTest {

    private val zone = ZoneId.of("UTC")
    private val day = LocalDate.of(2026, 6, 8)

    @Test
    fun `mixed session counts contributions once plus its unlinked remainder`() {
        // A recovery contribution funded 10 minutes into a 40-minute legacy session.
        val credited = PlaytimeReadProjector.creditedMinutesByGame(
            contributions = listOf(ContributionCredit(appId = 1L, authorizedMinutes = 10)),
            sessions = listOf(session(id = 7, appId = 1L, minutes = 40)),
            timedShares = listOf(share("c1", appId = 1L, sessionId = 7, minutes = 10)),
        )
        // 10 contribution minutes + 30 unlinked legacy minutes = the session's own 40; no double count.
        assertEquals(mapOf(1L to 40L), credited)
    }

    @Test
    fun `shared session without a contribution keeps its exact total`() {
        val credited = PlaytimeReadProjector.creditedMinutesByGame(
            contributions = emptyList(),
            sessions = listOf(session(id = 1, appId = 9L, minutes = 30)),
            timedShares = emptyList(),
        )
        assertEquals(mapOf(9L to 30L), credited)
    }

    @Test
    fun `fully funded session contributes zero remainder and the contribution counts once`() {
        val credited = PlaytimeReadProjector.creditedMinutesByGame(
            contributions = listOf(ContributionCredit(1L, 20)),
            sessions = listOf(session(id = 1, appId = 1L, minutes = 20)),
            timedShares = listOf(share("c1", 1L, sessionId = 1, minutes = 20)),
        )
        assertEquals(mapOf(1L to 20L), credited)
    }

    @Test
    fun `pending and dated quantity are both inside the contribution so totals reconcile`() {
        val credited = PlaytimeReadProjector.creditedMinutesByGame(
            contributions = listOf(ContributionCredit(2L, 105)),
            sessions = listOf(session(id = 3, appId = 2L, minutes = 35)),
            timedShares = listOf(share("c2", 2L, sessionId = 3, minutes = 35)),
        )
        assertEquals(mapOf(2L to 105L), credited)
        val dated = PlaytimeReadProjector.datedCreditedTotals(
            sessions = listOf(session(id = 3, appId = 2L, minutes = 35)),
            timedShares = listOf(share("c2", 2L, sessionId = 3, minutes = 35, date = day)),
            allocations = listOf(
                datedAllocation("c2", 2L, day, 70, PlaytimeAllocationMethod.MULTI_DAY_ESTIMATE),
            ),
            recipes = emptyList(),
            zone = zone,
            startDateInclusive = day, endDateExclusive = day.plusDays(1),
        )
        assertEquals(105L, dated.single().minutes)
    }

    @Test
    fun `overfunded session is rejected instead of erasing mixed legacy`() {
        try {
            PlaytimeReadProjector.creditedMinutesByGame(
                contributions = listOf(ContributionCredit(1L, 50)),
                sessions = listOf(session(id = 1, appId = 1L, minutes = 40)),
                timedShares = listOf(share("c1", 1L, sessionId = 1, minutes = 50)),
            )
            fail("an overfunded mapping must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.startsWith("overfunded session"))
        }
    }

    @Test
    fun `hosted share referencing an unknown session is rejected`() {
        try {
            PlaytimeReadProjector.creditedMinutesByGame(
                contributions = emptyList(),
                sessions = listOf(session(id = 1, appId = 1L, minutes = 5)),
                timedShares = listOf(share("c1", 1L, sessionId = 99, minutes = 5)),
            )
            fail("a share referencing a missing session must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.startsWith("funded share"))
        }
    }

    @Test
    fun `share whose app does not match its hosting session is rejected before summing`() {
        try {
            PlaytimeReadProjector.creditedMinutesByGame(
                contributions = listOf(ContributionCredit(1L, 5)),
                sessions = listOf(session(id = 1, appId = 1L, minutes = 5)),
                // Same session id, different game — an inconsistent share graph.
                timedShares = listOf(share("c1", appId = 2L, sessionId = 1, minutes = 5)),
            )
            fail("an app-mismatched share must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.startsWith("funded share"))
        }
    }

    @Test
    fun `pending date-unknown quantity is excluded from dated totals`() {
        val totals = PlaytimeReadProjector.datedCreditedTotals(
            sessions = emptyList(),
            timedShares = emptyList(),
            allocations = listOf(
                DatedAllocationCredit(
                    placementId = "p1", contributionId = "c1", appId = 1L, date = null, minutes = 70,
                    method = PlaytimeAllocationMethod.SINGLE_DAY_ESTIMATE, pendingDate = true,
                ),
            ),
            recipes = emptyList(),
            zone = zone,
            startDateInclusive = day, endDateExclusive = day.plusDays(1),
        )
        assertTrue(totals.isEmpty())
    }

    @Test
    fun `funded share stays on its frozen date even when the session would date elsewhere`() {
        // The session's start instant falls on `day` in the current zone, but the validated share
        // is frozen to a different date (timezone/travel change on the original recovery zone).
        val frozenDate = day.plusDays(1)
        val totals = PlaytimeReadProjector.datedCreditedTotals(
            sessions = listOf(session(id = 5, appId = 1L, minutes = 60)),
            timedShares = listOf(share("c1", 1L, sessionId = 5, minutes = 20, date = frozenDate)),
            allocations = emptyList(),
            recipes = emptyList(),
            zone = zone,
            startDateInclusive = day, endDateExclusive = frozenDate.plusDays(1),
        )
        // The unlinked remainder stays on the session's own date; the funded share never follows
        // the session row's current-zone date — no travel re-bucketing of a validated share.
        assertEquals(
            mapOf(day to 40L, frozenDate to 20L),
            totals.associate { it.date to it.minutes },
        )
    }

    @Test
    fun `session deposits only its unlinked remainder and each share its frozen date exactly once`() {
        // Two hosted shares with different frozen dates inside one session.
        val totals = PlaytimeReadProjector.datedCreditedTotals(
            sessions = listOf(session(id = 5, appId = 1L, minutes = 70)),
            timedShares = listOf(
                share("c1", 1L, sessionId = 5, minutes = 10, date = day),
                share("c2", 1L, sessionId = 5, minutes = 20, date = day.plusDays(1)),
            ),
            allocations = emptyList(),
            recipes = emptyList(),
            zone = zone,
            startDateInclusive = day, endDateExclusive = day.plusDays(2),
        )
        assertEquals(
            mapOf(day to 10L + 40L, day.plusDays(1) to 20L),
            totals.associate { it.date to it.minutes },
        )
    }

    @Test
    fun `session activity exposes the funded-share split without a second row`() {
        val activities = PlaytimeReadProjector.datedActivityBetween(
            sessions = listOf(session(id = 5, appId = 1L, minutes = 60)),
            timedShares = listOf(
                share("c1", 1L, sessionId = 5, minutes = 20, date = day.plusDays(1)),
            ),
            allocations = listOf(datedAllocation("c9", 1L, day, 70, PlaytimeAllocationMethod.SINGLE_DAY_ESTIMATE)),
            recipes = emptyList(),
            zone = zone,
            startDateInclusive = day, endDateExclusive = day.plusDays(2),
        )
        val sessionActivity = activities.filterIsInstance<PlaytimeActivity.TimestampedSession>().single()
        assertEquals(listOf(SessionFundedShare("c1", 20L, day.plusDays(1))), sessionActivity.fundedShares)
        assertEquals(20L, sessionActivity.fundedMinutes)
        assertEquals(40L, sessionActivity.unlinkedMinutes)
        val allocationActivity = activities.filterIsInstance<PlaytimeActivity.DatedAllocation>().single()
        assertEquals(day, allocationActivity.date)
        assertEquals(70L, allocationActivity.selectedDayMinutes)
        assertEquals(70L, allocationActivity.residualWindowTotalMinutes)
        assertEquals("c9", allocationActivity.contributionId)
        assertEquals(PlaytimeAllocationMethod.SINGLE_DAY_ESTIMATE, allocationActivity.method)
    }

    @Test
    fun `recipe tail derives bounded exact shares after the materialized cursor`() {
        // R = 105 over four eligible dates -> 27/26/26/26; cursor materialized the first date.
        val recipe = RecipeCredit(
            contributionId = "c1", appId = 1L,
            method = PlaytimeAllocationMethod.MULTI_DAY_ESTIMATE,
            dateStart = day, dateEnd = day.plusDays(3),
            baseMinutes = 26, remainderCount = 1, eligibleCount = 4,
            fullyResolvedDates = emptyList(),
            materializedThroughDate = day,
        )
        val derived = PlaytimeReadProjector.recipeActivities(
            recipes = listOf(recipe),
            startDateInclusive = day, endDateExclusive = day.plusDays(4),
        )
        assertEquals(
            listOf(day.plusDays(1) to 26L, day.plusDays(2) to 26L, day.plusDays(3) to 26L),
            derived.map { it.date to it.minutes },
        )
        // The derived rows retain the whole window's residual total for ! explanations.
        assertEquals(105L, derived.first().residualWindowTotalMinutes)
        assertEquals(day, derived.first().windowDateStart)
        assertEquals(day.plusDays(3), derived.first().windowDateEnd)
        // Combined with the materialized first-date row the full 105 is accounted exactly once.
        val allDates = PlaytimeReadProjector.datedCreditedTotals(
            sessions = emptyList(),
            timedShares = emptyList(),
            allocations = listOf(datedAllocation("c1", 1L, day, 27, PlaytimeAllocationMethod.MULTI_DAY_ESTIMATE)),
            recipes = listOf(recipe),
            zone = zone,
            startDateInclusive = day, endDateExclusive = day.plusDays(4),
        )
        assertEquals(105L, allDates.sumOf { it.minutes })
    }

    @Test
    fun `earliest canonical date precedes the first session when allocations come first`() {
        val earliest = PlaytimeReadProjector.earliestCanonicalDate(
            sessions = listOf(session(id = 1, appId = 1L, minutes = 10, startAt = at(day.plusDays(2)))),
            timedShares = emptyList(),
            allocations = listOf(datedAllocation("c1", 1L, day, 70, PlaytimeAllocationMethod.SINGLE_DAY_ESTIMATE)),
            recipes = emptyList(),
            zone = zone,
        )
        assertEquals(day, earliest)
    }

    @Test
    fun `excluded earliest recipe dates never drag the canonical anchor backwards`() {
        // The recipe's window starts before the session, but its early dates are fully resolved
        // (excluded) and carry no materialized rows, so the anchor stays at the session date.
        val recipe = RecipeCredit(
            contributionId = "c1", appId = 1L,
            method = PlaytimeAllocationMethod.MULTI_DAY_ESTIMATE,
            dateStart = day.minusDays(2), dateEnd = day.plusDays(1),
            baseMinutes = 1, remainderCount = 0, eligibleCount = 4,
            fullyResolvedDates = listOf(day.minusDays(2), day.minusDays(1)),
            materializedThroughDate = day.minusDays(1),
        )
        val earliest = PlaytimeReadProjector.earliestCanonicalDate(
            sessions = listOf(session(id = 1, appId = 1L, minutes = 10, startAt = at(day))),
            timedShares = listOf(share("c9", 1L, sessionId = null, minutes = 5, date = day.minusDays(3))),
            allocations = emptyList(),
            recipes = listOf(recipe),
            zone = zone,
        )
        assertEquals(day.minusDays(3), earliest)
    }

    @Test
    fun `multi-day estimate window masks every date except fully resolved ones`() {
        val windows = PlaytimeReadProjector.recoveryEstimateWindows(
            listOf(
                RecipeCredit(
                    contributionId = "c1", appId = 1L,
                    method = PlaytimeAllocationMethod.MULTI_DAY_ESTIMATE,
                    dateStart = day, dateEnd = day.plusDays(3),
                    baseMinutes = 1, remainderCount = 0, eligibleCount = 4,
                    fullyResolvedDates = listOf(day.plusDays(1)),
                    materializedThroughDate = null,
                ),
            ),
        )
        val window = windows.single()
        assertTrue(window.isUnknown(day))
        assertFalse(window.isUnknown(day.plusDays(1)))
        assertEquals(
            setOf(day, day.plusDays(2), day.plusDays(3)),
            window.unknownDatesBetween(day, day.plusDays(3)),
        )
    }

    @Test
    fun `estimate window masking is bounded to the requested horizon`() {
        // A ten-year estimate window must never be enumerated in full.
        val farStart = LocalDate.of(2016, 1, 1)
        val farEnd = LocalDate.of(2026, 1, 1)
        val window = RecoveryEstimateWindow(
            contributionId = "c1", appId = 1L,
            dateStart = farStart, dateEnd = farEnd,
        )
        val horizon = farEnd.minusDays(56)..farEnd
        val masked = window.unknownDatesBetween(horizon.start, horizon.endInclusive)
        assertEquals(57, masked.size)
        assertTrue(masked.all { it in horizon })
    }

    @Test
    fun `single-day and targeted estimates are not masked`() {
        val windows = PlaytimeReadProjector.recoveryEstimateWindows(
            listOf(
                RecipeCredit(
                    contributionId = "s", appId = 1L,
                    method = PlaytimeAllocationMethod.SINGLE_DAY_ESTIMATE,
                    dateStart = day, dateEnd = day,
                    baseMinutes = 70, remainderCount = 0, eligibleCount = 1,
                    fullyResolvedDates = emptyList(), materializedThroughDate = null,
                ),
            ),
        )
        assertTrue(windows.isEmpty())
    }

    @Test
    fun `zero-share dates inside a recipe window are not materialized as activity`() {
        // R = 2 over four eligible dates -> 1/1/0/0; only the earliest two dates produce rows.
        val recipe = RecipeCredit(
            contributionId = "c1", appId = 1L,
            method = PlaytimeAllocationMethod.MULTI_DAY_ESTIMATE,
            dateStart = day, dateEnd = day.plusDays(3),
            baseMinutes = 0, remainderCount = 2, eligibleCount = 4,
            fullyResolvedDates = emptyList(), materializedThroughDate = null,
        )
        val derived = PlaytimeReadProjector.recipeActivities(
            recipes = listOf(recipe),
            startDateInclusive = day, endDateExclusive = day.plusDays(4),
        )
        assertEquals(
            listOf(day to 1L, day.plusDays(1) to 1L),
            derived.map { it.date to it.minutes },
        )
        // The zero-share gap dates remain part of the estimate window: still unknown for pace.
        val window = PlaytimeReadProjector.recoveryEstimateWindows(listOf(recipe)).single()
        assertTrue(window.isUnknown(day.plusDays(3)))
    }

    @Test
    fun `compact resolved date ranges restore pace eligibility without expansion`() {
        val start = LocalDate.of(2000, 1, 1)
        val end = LocalDate.of(2030, 12, 31)
        val resolvedStart = LocalDate.of(2010, 1, 1)
        val resolvedEnd = LocalDate.of(2020, 12, 31)
        val recipe = RecipeCredit(
            contributionId = "long", appId = 1L,
            method = PlaytimeAllocationMethod.MULTI_DAY_ESTIMATE,
            dateStart = start, dateEnd = end,
            baseMinutes = 1L, remainderCount = 0L,
            eligibleCount = (
                java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1 -
                    java.time.temporal.ChronoUnit.DAYS.between(resolvedStart, resolvedEnd) - 1
                ).toInt(),
            fullyResolvedDates = emptyList(),
            materializedThroughDate = null,
            fullyResolvedDateRanges = listOf(resolvedStart..resolvedEnd),
        )
        val window = PlaytimeReadProjector.recoveryEstimateWindows(listOf(recipe)).single()

        assertTrue(window.isUnknown(start))
        assertFalse(window.isUnknown(resolvedStart.plusDays(10)))
        assertEquals(emptySet<LocalDate>(), window.unknownDatesBetween(resolvedStart, resolvedEnd))
        assertEquals(
            3,
            window.unknownDatesBetween(resolvedEnd.plusDays(1), resolvedEnd.plusDays(3)).size,
        )
    }

    @Test
    fun `recipe tail expansion skips compact excluded ranges and keeps weighted dates`() {
        val recipe = RecipeCredit(
            contributionId = "c-range", appId = 1L,
            method = PlaytimeAllocationMethod.MULTI_DAY_ESTIMATE,
            dateStart = day, dateEnd = day.plusDays(3),
            baseMinutes = 35, remainderCount = 0, eligibleCount = 3,
            fullyResolvedDates = emptyList(), materializedThroughDate = null,
            fullyResolvedDateRanges = listOf(day.plusDays(1)..day.plusDays(1)),
        )
        val activities = PlaytimeReadProjector.recipeActivities(
            listOf(recipe), day, day.plusDays(4),
        )
        assertEquals(listOf(day, day.plusDays(2), day.plusDays(3)), activities.map { it.date })
        assertTrue(activities.all { it.minutes == 35L })
    }

    @Test
    fun `fully materialized recipe still labels the window unresolved`() {
        val recipe = RecipeCredit(
            contributionId = "c1", appId = 1L,
            method = PlaytimeAllocationMethod.MULTI_DAY_ESTIMATE,
            dateStart = day, dateEnd = day.plusDays(3),
            baseMinutes = 26, remainderCount = 1, eligibleCount = 4,
            fullyResolvedDates = emptyList(), materializedThroughDate = day.plusDays(3),
        )
        val windows = PlaytimeReadProjector.recoveryEstimateWindows(listOf(recipe))
        assertEquals(1, windows.size)
        assertEquals(4, windows.single().unknownDatesBetween(day, day.plusDays(3)).size)
    }

    @Test
    fun `credited allocations retain identity window and share details for explanations`() {
        val allocations = PlaytimeReadProjector.creditedAllocationsBetween(
            timedShares = emptyList(),
            allocations = listOf(
                DatedAllocationCredit(
                    placementId = "a1", contributionId = "c1", appId = 1L, date = day,
                    minutes = 26, method = PlaytimeAllocationMethod.MULTI_DAY_ESTIMATE,
                    frozenZone = "Europe/Berlin", windowStartAt = at(day.minusDays(1)),
                    windowEndAt = at(day.plusDays(3)), goalAtCredit = true,
                ),
            ),
            recipes = emptyList(),
            startDateInclusive = day,
            endDateInclusive = day,
        )
        val allocation = allocations.single()
        assertEquals("a1", allocation.placementId)
        assertEquals(26, allocation.minutes)
        assertEquals(26, allocation.windowTotalMinutes)
        assertEquals("Europe/Berlin", allocation.zone)
        assertEquals(day.minusDays(1), allocation.windowStartDate)
        assertEquals(day.plusDays(3), allocation.windowEndDate)
        assertEquals(true, allocation.goalAtCredit)
        assertEquals(PlaytimeAllocationMethod.MULTI_DAY_ESTIMATE, allocation.method)
    }

    @Test
    fun `single-day unmaterialized recipe feeds training totals within the window`() {
        val recipe = RecipeCredit(
            contributionId = "c1", appId = 1L,
            method = PlaytimeAllocationMethod.SINGLE_DAY_ESTIMATE,
            dateStart = day, dateEnd = day,
            baseMinutes = 70, remainderCount = 0, eligibleCount = 1,
            fullyResolvedDates = emptyList(), materializedThroughDate = null,
        )
        val totals = PlaytimeReadProjector.trainingTotalsBetween(
            sessions = emptyList(),
            timedShares = emptyList(),
            allocations = emptyList(),
            recipes = listOf(recipe),
            zone = zone,
            firstDateInclusive = day.minusDays(1),
            lastDateInclusive = day.plusDays(1),
        )
        assertEquals(mapOf(day to 70L), totals)
    }

    @Test
    fun `training totals keep hosted shares on frozen dates after travel`() {
        // The closed session's start instant is on `day`, but the validated share is frozen to
        // `day+1` (travel): the share trains on its frozen date, the remainder on the session date.
        val totals = PlaytimeReadProjector.trainingTotalsBetween(
            sessions = listOf(session(id = 5, appId = 1L, minutes = 60)),
            timedShares = listOf(share("c1", 1L, sessionId = 5, minutes = 20, date = day.plusDays(1))),
            allocations = emptyList(),
            recipes = emptyList(),
            zone = zone,
            firstDateInclusive = day,
            lastDateInclusive = day.plusDays(1),
        )
        assertEquals(mapOf(day to 40L, day.plusDays(1) to 20L), totals)
    }

    @Test
    fun `open session funded share never trains even when frozen inside the window`() {
        val totals = PlaytimeReadProjector.trainingTotalsBetween(
            sessions = listOf(session(id = 5, appId = 1L, minutes = 60).copy(open = true)),
            timedShares = listOf(share("c1", 1L, sessionId = 5, minutes = 20, date = day.plusDays(1))),
            allocations = emptyList(),
            recipes = emptyList(),
            zone = zone,
            firstDateInclusive = day,
            lastDateInclusive = day.plusDays(1),
        )
        assertTrue(totals.isEmpty())
    }

    @Test
    fun `allocation-only multi-day window masks its full observed span including gaps`() {
        // No recipe retained: the allocation rows keep the observed window instants + frozen zone,
        // so the mask covers the whole span (zero-share gap dates stay unknown).
        val windows = PlaytimeReadProjector.allocationEstimateWindows(
            listOf(
                DatedAllocationCredit(
                    placementId = "a1", contributionId = "c1", appId = 1L, date = day,
                    minutes = 26, method = PlaytimeAllocationMethod.MULTI_DAY_ESTIMATE,
                    frozenZone = "UTC", windowStartAt = at(day.minusDays(1)),
                    windowEndAt = at(day.plusDays(3)),
                ),
            ),
        )
        val window = windows.single()
        assertEquals(day, window.dateStart)
        assertEquals(day.plusDays(3), window.dateEnd)
        assertEquals(setOf(day, day.plusDays(1), day.plusDays(2), day.plusDays(3)),
            window.unknownDatesBetween(day.minusDays(1), day.plusDays(4)))
    }

    private fun session(id: Long, appId: Long, minutes: Int, startAt: Long = at(day)) =
        SessionCredit(id = id, appId = appId, startAt = startAt, minutes = minutes, open = false)

    private fun share(
        contributionId: String,
        appId: Long,
        sessionId: Long?,
        minutes: Long,
        date: LocalDate? = day,
    ) = TimedShareCredit(
        placementId = "placement-$contributionId",
        contributionId = contributionId, appId = appId, sessionId = sessionId, minutes = minutes,
        creditedDate = date, frozenZone = "UTC",
    )

    private fun datedAllocation(
        contributionId: String, appId: Long, date: LocalDate, minutes: Long,
        method: PlaytimeAllocationMethod,
    ) = DatedAllocationCredit(
        placementId = "placement-$contributionId",
        contributionId = contributionId, appId = appId, date = date, minutes = minutes,
        method = method, frozenZone = "UTC",
    )

    private fun at(date: LocalDate): Long = date.atStartOfDay(zone).toInstant().toEpochMilli()
}
