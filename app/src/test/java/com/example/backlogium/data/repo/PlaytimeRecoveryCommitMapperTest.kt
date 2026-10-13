package com.example.backlogium.data.repo

import com.example.backlogium.data.local.entity.PlaytimeContribution
import com.example.backlogium.data.local.entity.RecoveryAllocationMethods
import com.example.backlogium.data.local.entity.RecoveryEvidenceQuality
import com.example.backlogium.data.local.entity.UnknownTimeAllocationRecipe
import com.example.backlogium.data.local.recovery.PlaytimeRecoveryIdentity
import com.example.backlogium.data.repo.PlaytimeRecoveryCommitMapper.RecoveryCommitContext
import com.example.backlogium.domain.PlaytimeAttributionPlan
import com.example.backlogium.domain.PlaytimeAttributionPlanner
import com.example.backlogium.domain.PlaytimePlacementProvenance
import com.example.backlogium.domain.CloudReaderIdentity
import com.example.backlogium.domain.PlaytimeEvidenceSource
import com.example.backlogium.domain.PlaytimeRecoveryRequest
import com.example.backlogium.domain.PlaytimeTimingEvidence
import com.example.backlogium.domain.SessionDiffer
import com.example.backlogium.domain.TimedPlacement
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure mapping tests for [PlaytimeRecoveryCommitMapper] (recover-missed-playtime tasks 4.1/4.2):
 * the plan-to-row conversion is deterministic and storage-free, so these run with no Room at all.
 */
class PlaytimeRecoveryCommitMapperTest {

    private val utc = ZoneId.of("UTC")
    private val now = 1_800_000_000_000L

    private fun context(
        windowStartAt: Long? = ts("2024-01-08T12:00:00"),
        windowEndAt: Long = ts("2024-01-10T12:00:00"),
        hidden: Boolean = false,
        goal: Boolean = true,
    ) = RecoveryCommitContext(
        windowStartAt = windowStartAt,
        windowEndAt = windowEndAt,
        hiddenAtCredit = hidden,
        goalAtCredit = goal,
        now = now,
    )

    private fun contribution(plan: PlaytimeAttributionPlan, ctx: RecoveryCommitContext = context()) =
        PlaytimeRecoveryCommitMapper.contributionRow(plan, ctx, existing = null, revision = 1L)!!

    @Test
    fun storageIdMatchesTheStorageIdentityConvention() {
        val plan = plan(authorizedDeltaMinutes = 70, priorHighWaterMinutes = 500)

        assertEquals(
            PlaytimeRecoveryIdentity.contributionId("steam:1", 1L, 7L, 500L, 570L),
            PlaytimeRecoveryCommitMapper.storageContributionId(plan),
        )
    }

    @Test
    fun contributionRowForARecoveryPlanCarriesThePlannedRange() {
        val plan = plan(authorizedDeltaMinutes = 70, priorHighWaterMinutes = 500)
        val row = contribution(plan)

        assertEquals(500L, row.fromMinutes)
        assertEquals(570L, row.toMinutes)
        assertEquals(70L, row.authorizedMinutes)
        assertEquals(1L, row.revision)
        assertEquals("UTC", row.zone)
        assertEquals(ts("2024-01-08T12:00:00"), row.windowStartAt)
    }

    @Test
    fun aReplacementContributionKeepsItsStoredWindowAndZone() {
        val plan = plan(authorizedDeltaMinutes = 70, priorHighWaterMinutes = 500)
        val stored = contribution(plan).copy(revision = 2L)
        val replaced = PlaytimeRecoveryCommitMapper.contributionRow(
            plan, context(windowStartAt = ts("2024-01-12T00:00:00")), stored, revision = 2L,
        )!!

        // Pending placement never moves a retained contribution's window or frozen zone, even
        // though the current reading's window/zone may be newer.
        assertEquals(stored.windowStartAt, replaced.windowStartAt)
        assertEquals(stored.windowEndAt, replaced.windowEndAt)
        assertEquals(stored.zone, replaced.zone)
        assertEquals(2L, replaced.revision)
    }

    @Test
    fun pendingPlanMapsToAPendingAllocationOnly() {
        val plan = plan(
            authorizedDeltaMinutes = 70,
            previousObservationAt = ts("2024-01-10T12:00:00"),
            recoveryObservedAt = ts("2024-01-09T12:00:00"),
        )
        val (allocations, recipe) = PlaytimeRecoveryCommitMapper.allocationsAndRecipe(
            plan, contribution(plan), 1L, context(),
        )
        assertNull(recipe)
        assertEquals(1, allocations.size)
        assertTrue(allocations.single().pendingDate)
        assertEquals(70L, allocations.single().minutes)
        assertEquals(RecoveryAllocationMethods.PENDING_DATE_UNKNOWN, allocations.single().method)
    }

    @Test
    fun boundedWindowMaterializesEveryPositiveDateAndKeepsItsRecipe() {
        val plan = plan(
            authorizedDeltaMinutes = 105,
            previousObservationAt = ts("2024-01-08T12:00:00"),
            recoveryObservedAt = ts("2024-01-12T12:00:00"),
            priorHighWaterMinutes = 500,
        )
        val ctx = context(
            windowStartAt = ts("2024-01-08T12:00:00"),
            windowEndAt = ts("2024-01-12T12:00:00"),
        )
        val (allocations, recipe) = PlaytimeRecoveryCommitMapper.allocationsAndRecipe(
            plan, contribution(plan, ctx), 1L, ctx,
        )
        assertNotNull("the recipe is preserved even when its dates are materialized inline", recipe)
        assertEquals(4, allocations.size)
        assertTrue(allocations.all { !it.pendingDate && it.minutes > 0L })
        assertEquals(listOf(27L, 26L, 26L, 26L), allocations.map { it.minutes })
        assertEquals("2024-01-09", allocations.first().date)
        val r = recipe!!
        assertEquals(26L, r.quotient)
        assertEquals(1L, r.remainderCount)
        assertEquals(4, r.eligibleCount)
    }

    @Test
    fun tenYearWindowMapsToACompactRecipeWithNoExpandedRows() {
        val plan = plan(
            authorizedDeltaMinutes = 100_000,
            previousObservationAt = ts("2014-01-01T12:00:00"),
            recoveryObservedAt = ts("2024-01-01T12:00:00"),
        )
        val ctx = context(
            windowStartAt = ts("2014-01-01T12:00:00"),
            windowEndAt = ts("2024-01-01T12:00:00"),
        )
        val (allocations, recipe) = PlaytimeRecoveryCommitMapper.allocationsAndRecipe(
            plan, contribution(plan, ctx), 1L, ctx,
        )
        assertTrue(allocations.isEmpty())
        assertNotNull(recipe)
        val r: UnknownTimeAllocationRecipe = recipe!!
        assertEquals(100_000L, r.quotient!! * r.eligibleCount + r.remainderCount!!)
        assertEquals("2014-01-02", r.dateStart)
        assertEquals("2024-01-01", r.dateEnd)
        assertEquals(0L, r.materializedMinutes)
        assertTrue(r.eligibleCount > 3_600)
    }

    @Test
    fun longCoveredEvidenceRangeRemainsACompactRecipeExclusion() {
        val previous = ts("2014-01-01T00:00:00")
        val recovery = ts("2024-01-01T00:00:00")
        val plan = PlaytimeAttributionPlanner.plan(
            PlaytimeRecoveryRequest(
                account = "steam:1",
                appId = 1L,
                baselineEpoch = 7L,
                authorizedDeltaMinutes = 1_000_000,
                previousObservationAt = previous,
                recoveryObservedAt = recovery,
                zone = utc,
                evaluationDate = LocalDate.parse("2024-01-02"),
                priorHighWaterMinutes = 0,
                publicationDelayConflict = true,
                evidence = listOf(
                    PlaytimeTimingEvidence(
                        evidenceId = "year-covered",
                        appId = 1L,
                        account = "steam:1",
                        source = PlaytimeEvidenceSource.LOCAL,
                        startAt = ts("2015-01-01T00:00:00"),
                        endAt = ts("2016-01-01T00:00:00"),
                    ),
                ),
            ),
        )
        assertEquals(PlaytimePlacementProvenance.RETROACTIVE_PLAY, plan.provenance)
        val context = context(windowStartAt = previous, windowEndAt = recovery)
        val (_, recipe) = PlaytimeRecoveryCommitMapper.allocationsAndRecipe(
            plan, contribution(plan, context), 1L, context,
        )

        assertNotNull(recipe)
        assertTrue(recipe!!.eligibleCount > 3_000)
        assertTrue(recipe.excludedDatesCsv!!.contains(".."))
        assertTrue("full-year coverage is serialized as one range", recipe.excludedDatesCsv!!.length < 40)
    }

    @Test
    fun allocationDailyCreditHonorsTheFrozenEligibility() {
        val plan = plan(
            authorizedDeltaMinutes = 70,
            previousObservationAt = ts("2024-01-09T12:00:00"),
            recoveryObservedAt = ts("2024-01-10T12:00:00"),
            priorHighWaterMinutes = 500,
        )

        val visible = PlaytimeRecoveryCommitMapper.allocationDailyCredit(plan, hiddenAtCredit = false, goalAtCredit = true)
        assertEquals(mapOf("2024-01-10" to 70), visible.mapValues { it.value.minutesPlayed })
        assertEquals(70, visible.getValue("2024-01-10").goalMinutesPlayed)

        val hidden = PlaytimeRecoveryCommitMapper.allocationDailyCredit(plan, hiddenAtCredit = true, goalAtCredit = true)
        assertTrue("a hidden-at-credit share feeds no daily quest", hidden.isEmpty())
    }

    @Test
    fun longWindowProducesNoInlineDailyCreditAndACompactJournalDate() {
        val plan = plan(
            authorizedDeltaMinutes = 100_000,
            previousObservationAt = ts("2014-01-01T12:00:00"),
            recoveryObservedAt = ts("2024-01-01T12:00:00"),
        )
        assertTrue(
            PlaytimeRecoveryCommitMapper.allocationDailyCredit(plan, hiddenAtCredit = false, goalAtCredit = true).isEmpty(),
        )
        val affected = PlaytimeRecoveryCommitMapper.journalAffectedDates(plan)
        assertEquals(1, affected.size)
        assertTrue(affected.single().startsWith("RANGE:2014-01-02:2024-01-01:"))
    }

    @Test
    fun evidencePlacementsBecomeBoundedSessionActionsAndLinkedShares() {
        val plan = plan(
            authorizedDeltaMinutes = 105,
            previousObservationAt = ts("2024-01-08T12:00:00"),
            recoveryObservedAt = ts("2024-01-12T12:00:00"),
            evidence = listOf(
                PlaytimeTimingEvidence(
                    evidenceId = "e1", appId = 1L, account = "steam:1",
                    readerIdentity = CloudReaderIdentity("steam:1", 0L),
                    source = PlaytimeEvidenceSource.CLOUD,
                    startAt = ts("2024-01-10T09:00:00"), endAt = ts("2024-01-10T09:35:00"),
                ),
            ),
        )

        val actions = PlaytimeRecoveryCommitMapper.timedSessionActions(plan)
        assertEquals(2, actions.size)
        val opened = actions[0] as SessionDiffer.SessionAction.Open
        val closed = actions[1] as SessionDiffer.SessionAction.Close
        assertEquals(35, opened.minutes)
        assertEquals(plan.appId, opened.appId)
        assertEquals(opened.startAt, closed.startAt)
        assertEquals(opened.endAt, closed.endAt)

        val placement: TimedPlacement = plan.timedPlacements.single()
        val rows = PlaytimeRecoveryCommitMapper.evidenceTimedRows(
            plan, contribution(plan), 1L, context(), { _, _ -> 42L },
        )
        val row = rows.single()
        assertEquals(42L, row.sessionId)
        assertEquals(35L, row.minutes)
        assertEquals(RecoveryEvidenceQuality.CLOUD, row.evidenceQuality)
        assertEquals("2024-01-10", row.creditedDate)
        assertEquals(placement.startAt to placement.endAt, PlaytimeRecoveryCommitMapper.consumedSpanFrom(row))
        assertTrue(PlaytimeRecoveryCommitMapper.timedSessionKeys(plan).contains(plan.appId to placement.startAt))
    }

    @Test
    fun ordinarySessionActionsBecomeFundedSharesOfTheirAddedMinutesOnly() {
        val plan = plan(
            authorizedDeltaMinutes = 20,
            previousObservationAt = ts("2024-01-08T10:00:00"),
            recoveryObservedAt = ts("2024-01-08T10:20:00"),
            priorHighWaterMinutes = 500,
            evaluationDate = LocalDate.parse("2024-01-08"),
        )
        assertEquals(20, plan.sessionMinutes)
        val ctx = context(
            windowStartAt = ts("2024-01-08T10:00:00"),
            windowEndAt = ts("2024-01-08T10:20:00"),
        )

        val rows = PlaytimeRecoveryCommitMapper.sessionShareRows(
            plan, contribution(plan, ctx), 1L, ctx, { _, _ -> 7L },
        )
        val row = rows.single()
        assertEquals(20L, row.minutes) // the funded share, never a wider host row
        assertEquals(7L, row.sessionId)
        assertEquals(RecoveryEvidenceQuality.ESTIMATE, row.evidenceQuality)
        assertEquals("2024-01-08", row.creditedDate)
    }

    @Test
    fun currentSingleDayEarnedInputCapturesTheExactFrozenQuestCredit() {
        val plan = plan(
            authorizedDeltaMinutes = 20,
            previousObservationAt = ts("2024-01-08T10:00:00"),
            recoveryObservedAt = ts("2024-01-08T10:20:00"),
            priorHighWaterMinutes = 500,
            evaluationDate = LocalDate.parse("2024-01-08"),
        )
        val context = context(
            windowStartAt = ts("2024-01-08T10:00:00"),
            windowEndAt = ts("2024-01-08T10:20:00"),
            goal = true,
        )
        val applied = PlaytimeRecoveryCommitMapper.timedSessionActions(plan) + plan.sessionActions

        val credits = PlaytimeRecoveryCommitMapper.datedCreditByDate(
            plan = plan,
            hiddenAtCredit = false,
            goalAtCredit = context.goalAtCredit,
            appliedSessionActions = applied,
        )

        assertEquals(PlaytimePlacementProvenance.SYNC, plan.provenance)
        assertEquals(20, credits.getValue("2024-01-08").minutesPlayed)
        assertEquals(20, credits.getValue("2024-01-08").goalMinutesPlayed)
    }

    private fun plan(
        authorizedDeltaMinutes: Int,
        previousObservationAt: Long? = ts("2024-01-08T12:00:00"),
        recoveryObservedAt: Long = (previousObservationAt ?: 0L) + 2L * 24 * 3_600_000L,
        priorHighWaterMinutes: Int? = 500,
        evidence: List<PlaytimeTimingEvidence> = emptyList(),
        evaluationDate: LocalDate = LocalDate.parse("2024-01-12"),
    ) = PlaytimeAttributionPlanner.plan(
        PlaytimeRecoveryRequest(
            account = "steam:1",
            appId = 1L,
            baselineEpoch = 7L,
            authorizedDeltaMinutes = authorizedDeltaMinutes,
            previousObservationAt = previousObservationAt,
            recoveryObservedAt = recoveryObservedAt,
            zone = utc,
            evaluationDate = evaluationDate,
            priorHighWaterMinutes = priorHighWaterMinutes,
            evidence = evidence,
            activeReaderIdentity = evidence.firstOrNull { it.source == PlaytimeEvidenceSource.CLOUD }
                ?.readerIdentity,
        ),
    )

    private fun ts(text: String): Long =
        LocalDateTime.parse(text).atZone(utc).toInstant().toEpochMilli()
}
