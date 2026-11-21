package com.example.backlogium.domain

import com.example.backlogium.data.local.entity.PlaytimeContribution
import com.example.backlogium.data.local.entity.RecoveryAllocationMethods
import com.example.backlogium.data.local.entity.RecoveryEvidenceQuality
import com.example.backlogium.data.local.entity.RecoveryProvenance
import com.example.backlogium.data.local.entity.TimedContributionPlacement
import com.example.backlogium.data.local.entity.UnknownTimeAllocation
import com.example.backlogium.data.local.entity.UnknownTimeAllocationRecipe
import com.example.backlogium.data.local.recovery.PlaytimeRecoveryIdentity
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure planning behind tasks 6.2/6.3: revision-based late-evidence upgrades of retained recovery
 * contributions, exact conservation, no re-authorization, idempotent replay, protected placement
 * preservation and mapped-session share splitting. No Room, Android or network dependency.
 */
class RecoveryPlacementReconcilerTest {

    @Test
    fun lateEvidenceMoves35Of105ToTimedLeaving70ResidualExactly() {
        val contribution = contribution(revision = 1L)
        val estimates = estimates27_26_26_26()
        val evidence = listOf(evidenceWeds35)

        val plan = RecoveryPlacementReconciler.computeContributionReplacement(
            contribution = contribution,
            activeTimed = emptyList(),
            activeAllocations = estimates,
            evidence = evidence,
            consumedSpans = emptyList(),
            activeReaderIdentity = null,
            now = NOW,
            sessionMinutesById = emptyMap(),
        )

        assertEquals(RecoveryPlacementReconciler.RecoveryReconcileOperation.UPGRADE, plan.operation)
        assertEquals(1L, plan.priorRevision)
        assertEquals(2L, plan.currentRevision)
        val evidenceMinutes = plan.newEvidencePlacements.sumOf { it.minutes.toLong() }
        assertEquals(35L, evidenceMinutes)
        assertEquals(70L, plan.newAllocationRows.sumOf { it.minutes })
        assertEquals(105L, plan.conservedNewMinutes)
        // The 35 minutes are placed on the evidence span's own date, never on an invented day.
        assertTrue(plan.newEvidencePlacements.all { it.creditedDate == "2026-09-02" })
        assertTrue(plan.newEvidencePlacements.all { it.evidenceQuality == RecoveryEvidenceQuality.LOCAL })
        assertEquals(4, plan.supersededPlacementIds.size)
        // Old estimates leave their dates; replacements and the evidence session arrive on their
        // new ones. The net across all four dates is exactly zero.
        assertEquals(0L, plan.dateCreditDeltas.sumOf { it.minutesDelta.toLong() })
        assertTrue(plan.sessionShareChanges.isEmpty())
    }

    @Test
    fun replayWithTheSameEvidenceConsumedIsANoChange() {
        // State after the first upgrade: 35 LOCAL timed on a session + 70 estimates redistributed.
        val contribution = contribution(revision = 2L)
        val timed = listOf(
            timedShare(
                contribution = contribution,
                revision = 2L,
                sessionId = 7L,
                minutes = 35L,
                creditedDate = "2026-09-02",
                quality = RecoveryEvidenceQuality.LOCAL,
            ),
        )
        val allocations = listOf(
            allocation(contribution, 2L, "2026-09-01", 18L),
            allocation(contribution, 2L, "2026-09-02", 18L),
            allocation(contribution, 2L, "2026-09-03", 17L),
            allocation(contribution, 2L, "2026-09-04", 17L),
        )
        // The already-funded evidence span is now consumed: the same 35-minute interval on
        // Wednesday cannot authorize a second placement (late evidence is never a quantity source).
        val consumed = listOf(ConsumedPlaytimeSpan(WEDS_10_00, WEDS_10_35))

        val plan = RecoveryPlacementReconciler.computeContributionReplacement(
            contribution = contribution,
            activeTimed = timed,
            activeAllocations = allocations,
            evidence = listOf(evidenceWeds35),
            consumedSpans = consumed,
            activeReaderIdentity = null,
            now = NOW,
            sessionMinutesById = emptyMap(),
        )

        assertEquals(RecoveryPlacementReconciler.RecoveryReconcileOperation.NO_CHANGE, plan.operation)
        assertEquals(2L, plan.currentRevision)
        assertEquals(0, plan.newEvidencePlacements.size)
        assertEquals(0, plan.newAllocationRows.size)
        assertEquals(0, plan.dateCreditDeltas.size)
    }

    @Test
    fun compactRecipeWithUnmaterializedTailIsLeftIntactUntilItsRowsCanBeReconciled() {
        val contribution = contribution(revision = 1L)
        val materialized = allocation(contribution, 1L, "2026-09-01", 27L)
        val recipe = UnknownTimeAllocationRecipe(
            contributionId = contribution.contributionId,
            account = ACCOUNT,
            appId = GAME,
            revision = 1L,
            method = RecoveryAllocationMethods.MULTI_DAY_ESTIMATE,
            quotient = 26L,
            remainderCount = 1L,
            dateStart = "2026-09-01",
            dateEnd = "2026-09-04",
            eligibleCount = 4,
            zone = "UTC",
            materializedMinutes = 27L,
            materializedThroughDate = "2026-09-01",
            updatedAt = NOW,
        )

        val plan = RecoveryPlacementReconciler.computeContributionReplacement(
            contribution = contribution,
            activeTimed = emptyList(),
            activeAllocations = listOf(materialized),
            evidence = listOf(evidenceWeds35),
            consumedSpans = emptyList(),
            activeReaderIdentity = null,
            now = NOW,
            activeRecipe = recipe,
        )

        assertEquals(RecoveryPlacementReconciler.RecoveryReconcileOperation.NO_CHANGE, plan.operation)
        assertEquals(1L, plan.currentRevision)
    }

    @Test
    fun retainedCompactDateExclusionsTrimWhitespaceAndRejectMalformedTokens() {
        val parsed = parseRecoveryExcludedDateRanges(" 2026-09-01 , 2026-09-03..2026-09-05 ")
        assertEquals(listOf("2026-09-01", "2026-09-03..2026-09-05"),
            parsed.map { if (it.start == it.endInclusive) it.start.toString() else "${it.start}..${it.endInclusive}" })
        assertTrue(runCatching { parseRecoveryExcludedDateRanges("2026-09-01..bad") }.isFailure)
        assertTrue(runCatching { parseRecoveryExcludedDateRanges("2026-09-01,,2026-09-02") }.isFailure)
    }

    @Test
    fun lockedEvidenceSharesAreReDeclaredAtTheNewRevisionNotMoved() {
        val contribution = contribution(revision = 1L)
        // Authorized 105 = 35 locked cloud evidence share on session 5 + 70 unlocked estimates.
        val locked = timedShare(
            contribution = contribution,
            revision = 1L,
            sessionId = 5L,
            minutes = 35L,
            creditedDate = "2026-09-02",
            quality = RecoveryEvidenceQuality.CLOUD,
        )
        val unlocked = listOf(
            allocation(contribution, 1L, "2026-09-01", 18L),
            allocation(contribution, 1L, "2026-09-02", 18L),
            allocation(contribution, 1L, "2026-09-03", 17L),
            allocation(contribution, 1L, "2026-09-04", 17L),
        )

        val plan = RecoveryPlacementReconciler.computeContributionReplacement(
            contribution = contribution,
            activeTimed = listOf(locked),
            activeAllocations = unlocked,
            evidence = listOf(evidenceWeds35),
            consumedSpans = emptyList(),
            activeReaderIdentity = null,
            now = NOW,
            sessionMinutesById = emptyMap(),
        )

        // One locked share remains hosted on session 5; its deterministic id is regenerated at the
        // new revision because storage supersedes whole revisions, but its content is untouched.
        assertEquals(1, plan.keptTimedShares.size)
        assertEquals(5L, plan.keptTimedShares.single().sessionId)
        assertEquals(2L, plan.keptTimedShares.single().revision)
        assertEquals(35L, plan.keptTimedShares.single().minutes)
        assertEquals(RecoveryEvidenceQuality.CLOUD, plan.keptTimedShares.single().evidenceQuality)
        assertTrue(plan.keptTimedShares.single().placementId != locked.placementId)
        // 70 unlocked move: 35 to evidence + 35 residual; with the locked 35 the authorized 105
        // is conserved exactly once.
        assertEquals(35L, plan.newEvidencePlacements.sumOf { it.minutes.toLong() })
        assertEquals(35L, plan.newAllocationRows.sumOf { it.minutes })
        assertEquals(105L, plan.conservedNewMinutes)
    }

    @Test
    fun protectedPlacementsNeverMove() {
        val contribution = contribution(revision = 1L)
        // Authorized 105 = 20 protected on Friday + 85 unlocked estimates on the other three dates.
        val protectedAllocation = allocation(contribution, 1L, "2026-09-04", 20L)
            .copy(protected = true)
        val unlockable = listOf(
            allocation(contribution, 1L, "2026-09-01", 29L),
            allocation(contribution, 1L, "2026-09-02", 28L),
            allocation(contribution, 1L, "2026-09-03", 28L),
        )

        val plan = RecoveryPlacementReconciler.computeContributionReplacement(
            contribution = contribution,
            activeTimed = emptyList(),
            activeAllocations = listOf(protectedAllocation) + unlockable,
            evidence = listOf(evidenceWeds35),
            consumedSpans = emptyList(),
            activeReaderIdentity = null,
            now = NOW,
            sessionMinutesById = emptyMap(),
        )

        // The 20 protected minutes stay exactly where they are; nothing supersedes them.
        assertTrue(plan.keptAllocationRows.any { it.minutes == 20L && it.protected })
        assertTrue(plan.supersededPlacementIds.none { it == protectedAllocation.placementId })
        assertEquals(105L, plan.conservedNewMinutes)
        // 85 unlocked move: 35 to evidence, 50 redistributed over the eligible dates — the locked
        // Friday date carries no residual row (one allocation row per date per revision).
        assertEquals(35L, plan.newEvidencePlacements.sumOf { it.minutes.toLong() })
        assertEquals(50L, plan.newAllocationRows.sumOf { it.minutes })
        assertTrue(plan.newAllocationRows.none { it.date == "2026-09-04" })
        assertEquals(listOf("2026-09-01", "2026-09-02", "2026-09-03"), plan.newAllocationRows.map { it.date })
    }

    @Test
    fun mappedSyntheticSessionShareIsSplitOutOfTheSessionPreservingLegacyRemainder() {
        // A diff-synthesized session hosting a 35-minute funded share plus 70 unlinked legacy
        // minutes: the funded share is provably mapped and can move; the legacy minutes never do.
        val contribution = contribution(fromMinutes = 500L, toMinutes = 535L, revision = 1L)
        val synthetic = timedShare(
            contribution = contribution,
            revision = 1L,
            sessionId = 11L,
            minutes = 35L,
            creditedDate = "2026-09-02",
            quality = RecoveryEvidenceQuality.ESTIMATE,
        )

        val plan = RecoveryPlacementReconciler.computeContributionReplacement(
            contribution = contribution,
            activeTimed = listOf(synthetic),
            activeAllocations = emptyList(),
            evidence = listOf(evidenceWeds35),
            consumedSpans = emptyList(),
            activeReaderIdentity = null,
            now = NOW,
            sessionMinutesById = mapOf(11L to 105),
        )

        assertEquals(RecoveryPlacementReconciler.RecoveryReconcileOperation.UPGRADE, plan.operation)
        assertEquals(35L, plan.conservedNewMinutes)
        assertEquals(1, plan.sessionShareChanges.size)
        val change = plan.sessionShareChanges.single()
        assertEquals(11L, change.sessionId)
        assertEquals(105, change.beforeMinutes)
        assertEquals(70, change.afterMinutes) // the unlinked legacy remainder survives
        assertTrue(plan.newEvidencePlacements.single().creditedDate == "2026-09-02")
    }

    @Test
    fun fullyFundedSessionShareRetiresTheEmptyRow() {
        val contribution = contribution(fromMinutes = 500L, toMinutes = 535L, revision = 1L)
        val synthetic = timedShare(
            contribution = contribution,
            revision = 1L,
            sessionId = 12L,
            minutes = 35L,
            creditedDate = "2026-09-02",
            quality = RecoveryEvidenceQuality.ESTIMATE,
        )

        val plan = RecoveryPlacementReconciler.computeContributionReplacement(
            contribution = contribution,
            activeTimed = listOf(synthetic),
            activeAllocations = emptyList(),
            evidence = listOf(evidenceWeds35),
            consumedSpans = emptyList(),
            activeReaderIdentity = null,
            now = NOW,
            sessionMinutesById = mapOf(12L to 35),
        )

        val change = plan.sessionShareChanges.single()
        assertEquals(0, change.afterMinutes) // retire: no independent state references it
    }

    @Test
    fun noEvidenceOrEverythingAlreadyConsumedIsANoChange() {
        val contribution = contribution(revision = 1L)

        val noEvidence = RecoveryPlacementReconciler.computeContributionReplacement(
            contribution = contribution,
            activeTimed = emptyList(),
            activeAllocations = estimates27_26_26_26(),
            evidence = emptyList(),
            consumedSpans = emptyList(),
            activeReaderIdentity = null,
            now = NOW,
        )
        val invertedWindow = RecoveryPlacementReconciler.computeContributionReplacement(
            contribution = contribution.copy(
                windowStartAt = WEDS_10_35,
                windowEndAt = WEDS_10_00,
            ),
            activeTimed = emptyList(),
            activeAllocations = estimates27_26_26_26(),
            evidence = listOf(evidenceWeds35),
            consumedSpans = emptyList(),
            activeReaderIdentity = null,
            now = NOW,
        )

        assertEquals(RecoveryPlacementReconciler.RecoveryReconcileOperation.NO_CHANGE, noEvidence.operation)
        assertEquals(RecoveryPlacementReconciler.RecoveryReconcileOperation.NO_CHANGE, invertedWindow.operation)
    }

    @Test
    fun frozenCreditTimeEligibilitySurvivesTheMove() {
        val contribution = contribution(revision = 1L)
        // Wednesday's estimate was goal-eligible at credit time; that fact must follow the moved
        // minute to the evidence session and the replacement allocation on the same date.
        val estimates = estimates27_26_26_26().map {
            if (it.date == "2026-09-02") it.copy(goalAtCredit = true) else it
        }

        val plan = RecoveryPlacementReconciler.computeContributionReplacement(
            contribution = contribution,
            activeTimed = emptyList(),
            activeAllocations = estimates,
            evidence = listOf(evidenceWeds35),
            consumedSpans = emptyList(),
            activeReaderIdentity = null,
            now = NOW,
        )

        // The frozen goal fact must follow the moved minute: the Wednesday estimate was
        // goal-eligible, so the 35-minute evidence session landing there keeps goal eligibility,
        // and Wednesday's signed goal delta (+27) exactly matches its minutes delta (+27).
        assertTrue(plan.newEvidencePlacements.single().goalAtCredit)
        assertTrue(plan.newAllocationRows.first { it.date == "2026-09-02" }.goalAtCredit)
        assertEquals(
            27L,
            plan.dateCreditDeltas.filter { it.date == "2026-09-02" }.sumOf { it.goalMinutesDelta.toLong() },
        )
        assertEquals(
            plan.dateCreditDeltas.filter { it.date == "2026-09-02" }
                .sumOf { it.minutesDelta.toLong() },
            plan.dateCreditDeltas.filter { it.date == "2026-09-02" }
                .sumOf { it.goalMinutesDelta.toLong() },
        )
    }

    @Test
    fun wrongAppEvidenceCannotMoveAnotherGamesContribution() {
        val contribution = contribution(revision = 1L)
        val otherGame = evidenceWeds35.copy(appId = 999L)

        val plan = RecoveryPlacementReconciler.computeContributionReplacement(
            contribution = contribution,
            activeTimed = emptyList(),
            activeAllocations = estimates27_26_26_26(),
            evidence = listOf(otherGame),
            consumedSpans = emptyList(),
            activeReaderIdentity = null,
            now = NOW,
        )

        assertEquals(RecoveryPlacementReconciler.RecoveryReconcileOperation.NO_CHANGE, plan.operation)
    }

    // ------------------------------------------------------------------ fixtures

    private fun contribution(
        fromMinutes: Long = 500L,
        toMinutes: Long = 605L,
        revision: Long = 1L,
    ) = PlaytimeContribution(
        contributionId = PlaytimeRecoveryIdentity.contributionId(ACCOUNT, GAME, 1L, fromMinutes, toMinutes),
        account = ACCOUNT,
        appId = GAME,
        baselineEpoch = 1L,
        fromMinutes = fromMinutes,
        toMinutes = toMinutes,
        windowStartAt = MON_00_00,
        windowEndAt = FRI_20_00,
        authorizedMinutes = toMinutes - fromMinutes,
        revision = revision,
        provenance = RecoveryProvenance.RETROACTIVE_PLAY,
        zone = "UTC",
        createdAt = 1L,
    )

    private fun estimates27_26_26_26() = listOf(
        allocation(contribution(), 1L, "2026-09-01", 27L),
        allocation(contribution(), 1L, "2026-09-02", 26L),
        allocation(contribution(), 1L, "2026-09-03", 26L),
        allocation(contribution(), 1L, "2026-09-04", 26L),
    )

    private fun allocation(
        contribution: PlaytimeContribution,
        revision: Long,
        date: String,
        minutes: Long,
    ) = UnknownTimeAllocation(
        placementId = PlaytimeRecoveryIdentity.allocationPlacementId(contribution.contributionId, revision, date),
        contributionId = contribution.contributionId,
        account = contribution.account,
        appId = contribution.appId,
        revision = revision,
        date = date,
        minutes = minutes,
        method = RecoveryAllocationMethods.MULTI_DAY_ESTIMATE,
        zone = "UTC",
        windowStartAt = contribution.windowStartAt,
        windowEndAt = contribution.windowEndAt,
        createdAt = 1L,
    )

    private fun timedShare(
        contribution: PlaytimeContribution,
        revision: Long,
        sessionId: Long,
        minutes: Long,
        creditedDate: String,
        quality: String,
    ) = TimedContributionPlacement(
        placementId = PlaytimeRecoveryIdentity.timedPlacementId(
            contribution.contributionId, revision, "ev", creditedDate, sessionId,
        ),
        contributionId = contribution.contributionId,
        account = contribution.account,
        appId = contribution.appId,
        revision = revision,
        sessionId = sessionId,
        minutes = minutes,
        creditedDate = creditedDate,
        zone = "UTC",
        evidenceQuality = quality,
        createdAt = 1L,
    )

    private val evidenceWeds35 = PlaytimeTimingEvidence(
        evidenceId = "ev-1",
        appId = GAME,
        account = ACCOUNT,
        source = PlaytimeEvidenceSource.LOCAL,
        startAt = WEDS_10_00,
        endAt = WEDS_10_35,
    )

    private companion object {
        const val ACCOUNT = "76561198000000000"
        const val GAME = 440L
        val NOW = Instant.parse("2026-09-06T12:00:00Z").toEpochMilli()

        val MON_00_00 = Instant.parse("2026-08-31T00:00:00Z").toEpochMilli()
        val FRI_20_00 = Instant.parse("2026-09-04T20:00:00Z").toEpochMilli()
        val WEDS_10_00 = Instant.parse("2026-09-02T10:00:00Z").toEpochMilli()
        val WEDS_10_35 = Instant.parse("2026-09-02T10:35:00Z").toEpochMilli()
    }
}
