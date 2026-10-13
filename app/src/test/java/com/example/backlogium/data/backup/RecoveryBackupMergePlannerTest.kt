package com.example.backlogium.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure, segment-normalising contribution merge (recover-missed-playtime tasks.md 8.3/8.4). No
 * database is involved: the planner is the single decision point for per-funded-segment dedup,
 * protected/validated placement precedence, replacement aliases and account-safe baselines.
 */
class RecoveryBackupMergePlannerTest {

    @Test
    fun repeatedImport_changesNothing() {
        val graph = contribution("c1", 0, 100)
        val allocations = listOf(allocation("a1", "c1", "2026-07-14", 100))
        val local = recovery(listOf(graph), allocations)
        val imported = recovery(listOf(graph), allocations)

        val plan = planner.plan(local, imported, ACCOUNT)

        assertTrue(plan.isNoOp)
        assertTrue(plan.contributions.isEmpty())
        assertTrue(plan.conflicts.isEmpty())
    }

    @Test
    fun mergeConservationGuardRejectsOverflowingCompactRecipeArithmetic() {
        val contribution = contribution("overflow", 0L, 100L)
        val graph = RecoveryContributionGraph(
            contribution = contribution,
            timedPlacements = emptyList(),
            unknownAllocations = emptyList(),
            recipe = recipe(
                contributionId = contribution.contributionId,
                quotient = Long.MAX_VALUE,
                remainder = 0L,
                materialized = 0L,
                through = null,
            ),
        )

        assertFalse(recoveryGraphConserves(graph))
    }

    @Test
    fun disjointSequentialRanges_areBothRetained() {
        val plan = planner.plan(
            local = recovery(listOf(contribution("c1", 0, 100))),
            imported = recovery(listOf(contribution("c2", 100, 200))),
            activeAccount = ACCOUNT,
        )

        assertTrue(plan.conflicts.isEmpty())
        assertEquals(listOf(ACCOUNT), plan.affectedAccounts)
        assertEquals(setOf("c1", "c2"), plan.contributions.map { it.contribution.contributionId }.toSet())
        assertEquals(200L, plan.contributions.sumOf { it.contribution.authorizedMinutes })
    }

    @Test
    fun disjointRangesWithAnUnfundedGap_keepBothFundedSegments() {
        val plan = planner.plan(
            local = recovery(listOf(contribution("c-left", 0, 100))),
            imported = recovery(listOf(contribution("c-right", 200, 300))),
            activeAccount = ACCOUNT,
        )

        assertTrue(plan.conflicts.isEmpty())
        assertEquals(200L, plan.contributions.sumOf { it.contribution.authorizedMinutes })
        assertEquals(
            setOf(0L to 100L, 200L to 300L),
            plan.contributions.map { it.contribution.fromMinutes to it.contribution.toMinutes }.toSet(),
        )
    }

    @Test
    fun partiallyOverlappingRanges_preserveUncoveredTailsAndConserveUnion() {
        // local funds (500..570], imported funds (535..605]: the true union is 105 minutes. The
        // import must NOT choose one 70-minute side and drop the other 35 silently.
        val plan = planner.plan(
            local = recovery(listOf(contribution("cL", 500, 570))),
            imported = recovery(listOf(contribution("cI", 535, 605))),
            activeAccount = ACCOUNT,
        )

        assertTrue(plan.conflicts.isEmpty())
        assertEquals(105L, plan.contributions.sumOf { it.contribution.authorizedMinutes })
        val coverage = plan.contributions.flatMap {
            listOf(it.contribution.fromMinutes, it.contribution.toMinutes)
        }.distinct().sorted()
        assertEquals(listOf(500L, 570L, 605L), coverage)
        // The imported-only tail (570..605] becomes a funded fragment; the local run keeps its range.
        assertTrue(plan.contributions.any { it.contribution.contributionId == "cL" })
        assertTrue(plan.contributions.any { it.contribution.fromMinutes == 570L && it.contribution.toMinutes == 605L })
    }

    @Test
    fun newerConsistentImportedRange_thatCoversTheLocalRange_winsWhole() {
        val plan = planner.plan(
            local = recovery(listOf(contribution("cL", 500, 570))),
            imported = recovery(listOf(contribution("cI", 500, 605, revision = 2))),
            activeAccount = ACCOUNT,
        )

        val contributions = plan.contributions
        assertEquals(1, contributions.size)
        assertEquals(105L, contributions.single().contribution.authorizedMinutes)
        assertEquals("cI", contributions.single().contribution.contributionId)
    }

    @Test
    fun protectedLocalPlacement_keepsItsOwnSegmentAndNeverLosesQuantity() {
        val local = recovery(
            listOf(contribution("cL", 500, 570)),
            listOf(allocation("a1", "cL", "2026-07-14", 70, protected = true)),
        )
        val imported = recovery(listOf(contribution("cI", 500, 605, revision = 2)))

        val plan = planner.plan(local, imported, ACCOUNT)

        assertEquals(105L, plan.contributions.sumOf { it.contribution.authorizedMinutes })
        val localGraph = plan.contributions.first { it.contribution.contributionId == "cL" }
        assertTrue(localGraph.unknownAllocations.single().protected)
        // The imported-only tail (570..605] is funded by the import.
        assertTrue(plan.contributions.any { it.contribution.fromMinutes == 570L })
    }

    @Test
    fun validatedLocalTiming_isNotDowngradedByNewerImportedEstimate() {
        val local = recovery(
            listOf(contribution("cL", 0, 100)),
            timed = listOf(timed("t1", "cL", "2026-07-14", 100, quality = "LOCAL")),
        )
        val imported = recovery(listOf(contribution("cI", 0, 100, revision = 9)))

        val plan = planner.plan(local, imported, ACCOUNT)

        assertTrue("validated local timing must keep the whole segment", plan.isNoOp)
        assertTrue(plan.conflicts.isEmpty())
    }

    @Test
    fun equalRevisionDifferentEstimatePlacements_reportConflictAndKeepLocal() {
        val local = recovery(
            listOf(contribution("c-local", 0, 100)),
            listOf(allocation("a-local", "c-local", "2026-07-14", 100)),
        )
        val imported = recovery(
            listOf(contribution("c-import", 0, 100)),
            listOf(allocation("a-import", "c-import", "2026-07-15", 100)),
        )

        val plan = planner.plan(local, imported, ACCOUNT)

        assertTrue(plan.conflicts.any { it.detail.contains("ambiguous equal-precedence") })
        assertTrue("local winner is unchanged, so the graph rewrite is a no-op", plan.isNoOp)
        assertTrue(plan.contributions.isEmpty())
        assertEquals("2026-07-14", local.unknownAllocations!!.single().date)
    }

    @Test
    fun differentlyPartitionedEqualCoverage_countsQuantityOnce() {
        val local = recovery(listOf(contribution("c1", 0, 100), contribution("c2", 100, 200)))
        val imported = recovery(
            listOf(contribution("c3", 0, 50, revision = 3), contribution("c4", 50, 200, revision = 3)),
        )

        val plan = planner.plan(local, imported, ACCOUNT)

        assertTrue(plan.conflicts.isEmpty())
        // 200 total, never 400.
        assertEquals(200L, plan.contributions.sumOf { it.contribution.authorizedMinutes })
        assertEquals(setOf("c3", "c4"), plan.contributions.map { it.contribution.contributionId }.toSet())
    }

    @Test
    fun tombstonedPlacement_isReAliasedNotResurrectedAndQuantitySurvives() {
        val local = recovery(
            listOf(contribution("cL", 0, 100)),
            listOf(allocation("a1", "cL", "2026-07-14", 100)),
            replacements = listOf(
                BackupReplacement(
                    operationId = "op", contributionId = "cL", account = ACCOUNT, appId = APP,
                    currentRevision = 1, affectedDatesCsv = "2026-07-14",
                    replacedPlacementIdsCsv = "stale-p1", derivedStatus = "FINALIZED",
                ),
            ),
        )
        val imported = recovery(
            listOf(contribution("cI", 0, 100, revision = 9)),
            listOf(allocation("stale-p1", "cI", "2026-07-14", 100)),
        )

        val plan = planner.plan(local, imported, ACCOUNT)

        val kept = plan.contributions.first { it.contribution.contributionId == "cI" }
        val ids = kept.unknownAllocations.map { it.placementId }
        assertFalse("the tombstoned id must not return as an active placement", "stale-p1" in ids)
        // The funded quantity is re-aliased, never deleted (design #7).
        assertEquals(100L, kept.unknownAllocations.sumOf { it.minutes })
    }

    @Test
    fun tombstonedRecipeSlice_isReAliasedWithoutDoubleCountingItsRecipe() {
        val local = recovery(
            contributions = listOf(contribution("c-recipe", 0, 70)),
            unknownAllocations = listOf(allocation("old-slice", "c-recipe", "2026-07-14", 18)),
            recipes = listOf(recipe("c-recipe", 17, 2, materialized = 18, through = "2026-07-14")),
            replacements = listOf(
                BackupReplacement(
                    operationId = "op-recipe", contributionId = "c-recipe", account = ACCOUNT,
                    appId = APP, currentRevision = 1, affectedDatesCsv = "2026-07-14",
                    replacedPlacementIdsCsv = "old-slice", derivedStatus = "FINALIZED",
                ),
            ),
        )

        val plan = planner.plan(local, imported = null, activeAccount = ACCOUNT)
        val kept = plan.contributions.single()
        assertEquals(70L, kept.recipe!!.quotient!! * kept.recipe.eligibleCount + kept.recipe.remainderCount!!)
        assertEquals(18L, kept.unknownAllocations.single().minutes)
        assertFalse(kept.unknownAllocations.single().placementId == "old-slice")
    }

    @Test
    fun tombstonedTimedShareMovesIntoRecipeAndInvalidatesOldMaterializedPrefix() {
        val local = recovery(
            contributions = listOf(contribution("c-mixed", 0, 105)),
            timed = listOf(timed("old-timed", "c-mixed", "2026-07-14", 35, "ESTIMATE")),
            recipes = listOf(recipe("c-mixed", 17, 2, materialized = 18, through = "2026-07-14")),
            replacements = listOf(
                BackupReplacement(
                    operationId = "op-timed", contributionId = "c-mixed", account = ACCOUNT,
                    appId = APP, currentRevision = 1, affectedDatesCsv = "2026-07-14",
                    replacedPlacementIdsCsv = "old-timed", derivedStatus = "FINALIZED",
                ),
            ),
        )

        val kept = planner.plan(local, imported = null, activeAccount = ACCOUNT).contributions.single()
        assertTrue(kept.timedPlacements.isEmpty())
        assertTrue(kept.unknownAllocations.isEmpty())
        assertEquals(105L, kept.recipe!!.quotient!! * kept.recipe.eligibleCount + kept.recipe.remainderCount!!)
        assertEquals(0L, kept.recipe.materializedMinutes)
        assertEquals(null, kept.recipe.materializedThroughDate)
    }

    @Test
    fun legacyImport_overLocalRecovery_isRetainedAndReportedNotSilent() {
        val local = recovery(
            listOf(contribution("c1", 0, 100)),
            listOf(allocation("a1", "c1", "2026-07-14", 100)),
        )

        val plan = planner.plan(local, imported = null, activeAccount = ACCOUNT)

        assertTrue(plan.isNoOp)
        assertTrue(plan.warnings.any { it.detail.contains("legacy") })
    }

    @Test
    fun partialOverlapWithNewerImported_remainsIdempotentOnRetry() {
        val local = recovery(listOf(contribution("cL", 500, 570)))
        val imported = recovery(listOf(contribution("cI", 535, 605, revision = 2)))
        val first = planner.plan(local, imported, ACCOUNT)

        // Simulate the receiving install now holding the merged result and import again.
        val afterFirst = recoveryOf(first)
        val retry = planner.plan(afterFirst, imported, ACCOUNT)

        assertTrue("re-importing the same file must change nothing", retry.isNoOp)
        assertEquals(first.contributions.sumOf { it.contribution.authorizedMinutes }, 105L)
    }

    @Test
    fun foreignObservation_isNeverWritten() {
        val imported = recovery(
            emptyList(),
            observations = listOf(observation(account = "someone-else", highWater = 500, at = 1_000)),
        )

        val plan = planner.plan(null, imported, ACCOUNT)

        assertTrue(plan.observations.isEmpty())
        assertEquals(1, plan.warnings.size)
    }

    @Test
    fun newerSameAccountBaseline_isAdopted() {
        val plan = planner.plan(
            local = recovery(emptyList(), observations = listOf(observation(highWater = 100, at = 1_000))),
            imported = recovery(emptyList(), observations = listOf(observation(highWater = 150, at = 2_000))),
            activeAccount = ACCOUNT,
        )

        assertEquals(150L, plan.observations.single().highWaterMinutes)
    }

    @Test
    fun olderSameAccountBaseline_isPreserved() {
        val plan = planner.plan(
            local = recovery(emptyList(), observations = listOf(observation(highWater = 150, at = 2_000))),
            imported = recovery(emptyList(), observations = listOf(observation(highWater = 100, at = 1_000))),
            activeAccount = ACCOUNT,
        )

        assertTrue(plan.observations.isEmpty())
    }

    @Test
    fun higherImportedHighWater_isNeverIgnoredEvenWhenItsTimestampIsOlder() {
        // Newer local timestamp, but the import carries a higher high-water: the quantity anchor
        // must move so a future Steam read cannot re-credit the imported range.
        val plan = planner.plan(
            local = recovery(emptyList(), observations = listOf(observation(highWater = 100, at = 2_000))),
            imported = recovery(emptyList(), observations = listOf(observation(highWater = 150, at = 1_000))),
            activeAccount = ACCOUNT,
        )

        assertEquals(150L, plan.observations.single().highWaterMinutes)
        assertEquals("2026-07-17", plan.observations.single().lastObservationDate)
    }

    @Test
    fun importedOnlyContribution_isAdded() {
        val imported = recovery(
            listOf(contribution("c1", 0, 100)),
            listOf(allocation("a1", "c1", "2026-07-14", 100)),
        )

        val plan = planner.plan(null, imported, ACCOUNT)

        assertEquals(listOf(ACCOUNT), plan.affectedAccounts)
        assertEquals(100L, plan.contributions.sumOf { it.contribution.authorizedMinutes })
    }

    // ------------------------------------------------------------------ fixtures

    private val planner = RecoveryBackupMergePlanner

    private fun recoveryOf(plan: RecoveryMergePlan): BackupRecovery = BackupRecovery(
        observations = emptyList(),
        contributions = plan.contributions.map { it.contribution },
        timedPlacements = plan.contributions.flatMap { it.timedPlacements },
        unknownAllocations = plan.contributions.flatMap { it.unknownAllocations },
        recipes = plan.contributions.mapNotNull { it.recipe },
        replacements = plan.replacements,
    )

    private fun contribution(
        id: String,
        from: Long,
        to: Long,
        revision: Long = 1,
    ) = BackupContribution(
        contributionId = id,
        account = ACCOUNT,
        appId = APP,
        baselineEpoch = 1,
        fromMinutes = from,
        toMinutes = to,
        windowStartAt = "2026-07-13T00:00:00Z",
        windowEndAt = "2026-07-17T00:00:00Z",
        authorizedMinutes = to - from,
        revision = revision,
        provenance = "RETROACTIVE_PLAY",
        zone = "UTC",
        observedZone = "UTC",
    )

    private fun recipe(
        contributionId: String,
        quotient: Long,
        remainder: Long,
        materialized: Long,
        through: String?,
    ) = BackupAllocationRecipe(
        contributionId = contributionId,
        account = ACCOUNT,
        appId = APP,
        revision = 1,
        method = "MULTI_DAY_ESTIMATE",
        quotient = quotient,
        remainderCount = remainder,
        dateStart = "2026-07-14",
        dateEnd = "2026-07-17",
        eligibleCount = 4,
        zone = "UTC",
        materializedMinutes = materialized,
        materializedThroughDate = through,
    )

    private fun allocation(
        id: String,
        contributionId: String,
        date: String,
        minutes: Long,
        protected: Boolean = false,
    ) = BackupUnknownAllocation(
        placementId = id,
        contributionId = contributionId,
        account = ACCOUNT,
        appId = APP,
        revision = 1,
        date = date,
        minutes = minutes,
        method = "MULTI_DAY_ESTIMATE",
        zone = "UTC",
        pendingDate = false,
        protected = protected,
    )

    private fun timed(
        id: String,
        contributionId: String,
        date: String,
        minutes: Long,
        quality: String,
        protected: Boolean = false,
    ) = BackupTimedPlacement(
        placementId = id,
        contributionId = contributionId,
        account = ACCOUNT,
        appId = APP,
        revision = 1,
        minutes = minutes,
        creditedDate = date,
        zone = "UTC",
        evidenceQuality = quality,
        protected = protected,
    )

    private fun observation(
        account: String = ACCOUNT,
        highWater: Long,
        at: Long,
    ) = BackupRecoveryObservation(
        account = account,
        appId = APP,
        baselineEpoch = 1,
        highWaterMinutes = highWater,
        lastObservationAt = at.let { "2026-07-${if (it < 2_000) "16" else "17"}T12:00:00Z" },
        lastObservationDate = if (at < 2_000) "2026-07-16" else "2026-07-17",
        lastObservationZone = "UTC",
        clockState = "VALID",
    )

    private fun recovery(
        contributions: List<BackupContribution>,
        unknownAllocations: List<BackupUnknownAllocation> = emptyList(),
        timed: List<BackupTimedPlacement> = emptyList(),
        recipes: List<BackupAllocationRecipe> = emptyList(),
        observations: List<BackupRecoveryObservation> = emptyList(),
        replacements: List<BackupReplacement> = emptyList(),
    ) = BackupRecovery(
        observations = observations,
        contributions = contributions,
        timedPlacements = timed,
        unknownAllocations = unknownAllocations,
        recipes = recipes,
        replacements = replacements,
    )

    private companion object {
        const val ACCOUNT = "76561198000000000"
        const val APP = 440L
    }
}
