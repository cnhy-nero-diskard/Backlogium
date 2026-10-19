package com.example.backlogium.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Version 3 recovery preflight (recover-missed-playtime tasks.md 8.2): a strict full-file check of
 * the mandatory recovery sections, before any write. Legacy version markers must not smuggle modern
 * fields, and an inconsistent or dangling funded graph must be rejected rather than merged.
 */
class BackupRecoveryValidatorTest {

    @Test
    fun validRecoverySection_passes() {
        assertTrue(BackupValidator.validate(v3(recovery = validRecovery())) is BackupValidationResult.Valid)
    }

    @Test
    fun observationOnlyZeroEpoch_rejectedByFullFilePreflight() {
        val problems = v3(recovery = observationOnlyRecovery(epoch = 0L)).assertRejected()

        assertTrue(problems.any {
            it.recordType == "recoveryObservation" && it.detail.contains("baselineEpoch")
        })
    }

    @Test
    fun negativeObservationEpoch_rejectedByFullFilePreflight() {
        val problems = v3(recovery = observationOnlyRecovery(epoch = -1L)).assertRejected()

        assertTrue(problems.any {
            it.recordType == "recoveryObservation" && it.detail.contains("baselineEpoch")
        })
    }

    @Test
    fun zeroContributionEpoch_rejectedByFullFilePreflight() {
        val recovery = validRecovery().copy(
            contributions = listOf(validContribution().copy(baselineEpoch = 0L)),
        )
        val problems = v3(recovery).assertRejected()

        assertTrue(problems.any {
            it.recordType == "recoveryContribution" && it.detail.contains("baselineEpoch")
        })
    }

    @Test
    fun negativeContributionEpoch_rejectedByFullFilePreflight() {
        val recovery = validRecovery().copy(
            contributions = listOf(validContribution().copy(baselineEpoch = -1L)),
        )
        val problems = v3(recovery).assertRejected()

        assertTrue(problems.any {
            it.recordType == "recoveryContribution" && it.detail.contains("baselineEpoch")
        })
    }

    @Test
    fun positiveRecoveryAndLegacyWithoutRecovery_remainValid() {
        assertTrue(BackupValidator.validate(v3(recovery = validRecovery())) is BackupValidationResult.Valid)
        assertTrue(BackupValidator.validate(v3(recovery = null).copy(formatVersion = 1)) is BackupValidationResult.Valid)
        assertTrue(BackupValidator.validate(v3(recovery = null).copy(formatVersion = 2)) is BackupValidationResult.Valid)
    }

    @Test
    fun observationHighWaterAboveLiveWriterRange_rejected() {
        val recovery = observationOnlyRecovery(epoch = 1L).copy(
            observations = listOf(validObservation().copy(highWaterMinutes = Int.MAX_VALUE.toLong() + 1L)),
        )

        val problems = v3(recovery).assertRejected()

        assertTrue(problems.any {
            it.recordType == "recoveryObservation" && it.detail.contains("highWaterMinutes")
        })
    }

    @Test
    fun contributionEndpointAboveLiveWriterRange_rejected() {
        val from = Int.MAX_VALUE.toLong() - 1L
        val to = Int.MAX_VALUE.toLong() + 1L
        val recovery = validRecovery().copy(
            observations = listOf(validObservation().copy(highWaterMinutes = to)),
            contributions = listOf(validContribution().copy(
                fromMinutes = from,
                toMinutes = to,
                authorizedMinutes = to - from,
            )),
            unknownAllocations = listOf(allocation("2026-07-17", to - from)),
        )

        val problems = v3(recovery).assertRejected()

        assertTrue(problems.any {
            it.recordType == "recoveryContribution" && it.detail.contains("live Steam")
        })
    }

    @Test
    fun maximumLiveWriterValue_remainsValid() {
        val max = Int.MAX_VALUE.toLong()
        val recovery = validRecovery().copy(
            observations = listOf(validObservation().copy(highWaterMinutes = max)),
            contributions = listOf(validContribution().copy(
                fromMinutes = max - 1L,
                toMinutes = max,
                authorizedMinutes = 1L,
            )),
            unknownAllocations = listOf(allocation("2026-07-17", 1L)),
        )

        assertTrue(BackupValidator.validate(v3(recovery)) is BackupValidationResult.Valid)
    }

    @Test
    fun alternateEpochContributionCoveredByObservedHighWater_remainsValid() {
        val recovery = validRecovery().copy(
            observations = listOf(validObservation().copy(highWaterMinutes = 700L)),
            contributions = listOf(validContribution().copy(baselineEpoch = 2L)),
        )

        assertTrue(BackupValidator.validate(v3(recovery)) is BackupValidationResult.Valid)
    }

    @Test
    fun alternateEpochContributionPastObservedHighWater_rejected() {
        val recovery = validRecovery().copy(
            contributions = listOf(validContribution().copy(
                baselineEpoch = 2L,
                toMinutes = 606L,
                authorizedMinutes = 106L,
            )),
            unknownAllocations = validAllocations().mapIndexed { index, row ->
                if (index == 0) row.copy(minutes = 28L) else row
            },
        )

        val problems = v3(recovery).assertRejected()

        assertTrue(problems.any {
            it.recordType == "recoveryContribution" && it.detail.contains("exceeds observation high-water")
        })
    }

    @Test
    fun sameEpochContributionBeyondObservationHighWater_rejected() {
        val recovery = validRecovery().copy(
            contributions = listOf(validContribution().copy(toMinutes = 606L, authorizedMinutes = 106L)),
            unknownAllocations = validAllocations().mapIndexed { index, row ->
                if (index == 0) row.copy(minutes = 28L) else row
            },
        )

        val problems = v3(recovery).assertRejected()

        assertTrue(problems.any {
            it.recordType == "recoveryContribution" && it.detail.contains("exceeds observation high-water")
        })
    }

    @Test
    fun missingObservationForFundedContribution_rejected() {
        val problems = v3(recovery = validRecovery().copy(observations = emptyList())).assertRejected()

        assertTrue(problems.any {
            it.recordType == "recoveryContribution" && it.detail.contains("no recovery observation")
        })
    }

    @Test
    fun olderContributionEpochWithNewerResetBaseline_remainsValid() {
        val recovery = validRecovery().copy(
            observations = listOf(validObservation().copy(baselineEpoch = 2L, highWaterMinutes = 50L)),
            contributions = listOf(validContribution().copy(baselineEpoch = 1L)),
        )

        assertTrue("older epoch shares remain historical across baseline re-establishment",
            BackupValidator.validate(v3(recovery)) is BackupValidationResult.Valid)
    }

    @Test
    fun recoveryAccountMustMatchFileIdentity_butForeignFileIdentityRemainsValid() {
        val foreignRecovery = recoveryForAccount(FOREIGN_ACCOUNT)

        val mismatched = v3(recovery = foreignRecovery).assertRejected()
        assertTrue(mismatched.any { it.detail.contains("does not match backup identity") })

        val foreignFile = v3(recovery = foreignRecovery).copy(
            identity = BackupIdentity(steamId64 = FOREIGN_ACCOUNT),
        )
        assertTrue("a foreign backup is valid when its own recovery records match its identity",
            BackupValidator.validate(foreignFile) is BackupValidationResult.Valid)
    }

    @Test
    fun timedPlacementAccountMustMatchItsContribution() {
        val timed = BackupTimedPlacement(
            placementId = "timed-foreign",
            contributionId = "c1",
            account = FOREIGN_ACCOUNT,
            appId = APP_ID,
            revision = 1L,
            minutes = 1L,
            creditedDate = "2026-07-17",
            zone = "UTC",
            sessionStartAt = "2026-07-17T10:00:00Z",
            sessionEndAt = "2026-07-17T11:00:00Z",
            evidenceQuality = "LOCAL",
        )
        val recovery = validRecovery().copy(
            timedPlacements = listOf(timed),
            unknownAllocations = validAllocations().mapIndexed { index, row ->
                if (index == 0) row.copy(minutes = 26L) else row
            },
        )

        val problems = v3(recovery, sessions = listOf(validSession())).assertRejected()

        assertTrue(problems.any { it.detail.contains("account/game does not match contribution") })
    }

    @Test
    fun allocationAccountMustMatchItsContribution() {
        val recovery = validRecovery().copy(
            unknownAllocations = validAllocations().mapIndexed { index, row ->
                if (index == 0) row.copy(account = FOREIGN_ACCOUNT) else row
            },
        )

        val problems = v3(recovery).assertRejected()

        assertTrue(problems.any { it.detail.contains("account/game does not match contribution") })
    }

    @Test
    fun recipeAccountMustMatchItsContribution() {
        val recovery = validRecovery().copy(recipes = listOf(validRecipe().copy(account = FOREIGN_ACCOUNT)))

        val problems = v3(recovery).assertRejected()

        assertTrue(problems.any { it.detail.contains("account/game does not match contribution") })
    }

    @Test
    fun replacementAccountMustMatchItsContribution() {
        val recovery = validRecovery().copy(replacements = listOf(validReplacement().copy(account = FOREIGN_ACCOUNT)))

        val problems = v3(recovery).assertRejected()

        assertTrue(problems.any { it.detail.contains("account/game does not match contribution") })
    }

    @Test
    fun ambiguousPortableSessionReference_rejected() {
        val timed = BackupTimedPlacement(
            placementId = "timed-1",
            contributionId = "c1",
            account = ACCOUNT,
            appId = APP_ID,
            revision = 1L,
            minutes = 1L,
            creditedDate = "2026-07-17",
            zone = "UTC",
            sessionStartAt = "2026-07-17T10:00:00Z",
            sessionEndAt = "2026-07-17T11:00:00Z",
            evidenceQuality = "LOCAL",
        )
        val recovery = validRecovery().copy(
            timedPlacements = listOf(timed, timed.copy(placementId = "timed-2")),
            unknownAllocations = validAllocations().mapIndexed { index, row ->
                if (index == 0) row.copy(minutes = 25L) else row
            },
        )

        val problems = v3(recovery, sessions = listOf(validSession(), validSession())).assertRejected()

        assertTrue(problems.any { it.detail.contains("ambiguous across 2 natural-key sessions") })
    }

    @Test
    fun v3WithoutRecoverySection_rejected() {
        val problems = v3(recovery = null).assertRejected()
        assertEquals("recovery", problems.single().recordType)
    }

    @Test
    fun v3WithAbsentSection_rejected() {
        val problems = v3(recovery = validRecovery().copy(observations = null)).assertRejected()
        assertTrue(problems.any { it.detail.contains("observations") })
    }

    @Test
    fun legacyVersionCarryingRecoverySection_rejected() {
        val v2 = v3(recovery = validRecovery()).copy(formatVersion = 2)
        val problems = v2.assertRejected()
        assertEquals("file", problems.single().recordType)
        assertTrue(problems.single().detail.contains("cannot contain recovery"))
    }

    @Test
    fun invalidZone_rejected() {
        val recovery = validRecovery().copy(
            observations = listOf(validObservation().copy(lastObservationZone = "Mars/Olympus")),
        )
        val problems = v3(recovery).assertRejected()
        assertTrue(problems.any { it.detail.contains("invalid zone") })
    }

    @Test
    fun unparseableDate_rejected() {
        val recovery = validRecovery().copy(
            unknownAllocations = validAllocations().mapIndexed { index, allocation ->
                if (index == 0) allocation.copy(date = "not-a-date") else allocation
            },
        )
        val problems = v3(recovery).assertRejected()
        assertTrue(problems.any { it.detail.contains("unusable allocation date") })
    }

    @Test
    fun authorizedWidthMismatch_rejected() {
        val recovery = validRecovery().copy(
            contributions = listOf(validContribution().copy(authorizedMinutes = 104)),
        )
        val problems = v3(recovery).assertRejected()
        assertTrue(problems.any { it.detail.contains("range width") })
    }

    @Test
    fun danglingAllocation_rejected() {
        val recovery = validRecovery().copy(
            unknownAllocations = validAllocations().map { it.copy(contributionId = "missing") },
        )
        val problems = v3(recovery).assertRejected()
        assertTrue(problems.any { it.detail.contains("absent contribution") })
    }

    @Test
    fun nonConservingQuantityGraph_rejected() {
        // Allocations sum to 104 but the contribution authorizes 105.
        val recovery = validRecovery().copy(
            unknownAllocations = validAllocations().mapIndexed { index, allocation ->
                if (index == 0) allocation.copy(minutes = 26) else allocation
            },
        )
        val problems = v3(recovery).assertRejected()
        assertTrue(problems.any { it.detail.contains("does not conserve") })
    }

    @Test
    fun conflictingDuplicateContribution_rejected() {
        val first = validContribution()
        val recovery = validRecovery().copy(contributions = listOf(first, first.copy(authorizedMinutes = 0)))
        val problems = v3(recovery).assertRejected()
        assertTrue(problems.any { it.detail.contains("conflicting duplicate contributionId") })
    }

    @Test
    fun overlappingFundedRanges_rejected() {
        val first = validContribution()
        val second = validContribution().copy(
            contributionId = "second",
            fromMinutes = 550,
            toMinutes = 700,
            authorizedMinutes = 150,
            revision = 1,
        )
        val recovery = validRecovery().copy(contributions = listOf(first, second))
        val problems = v3(recovery).assertRejected()
        assertTrue(problems.any { it.detail.contains("overlapping funded ranges") })
    }

    @Test
    fun danglingPortableTimedSessionLink_rejectedBeforeMerge() {
        val timed = BackupTimedPlacement(
            placementId = "timed-1", contributionId = "c1", account = ACCOUNT, appId = APP_ID,
            revision = 1, minutes = 1, creditedDate = "2026-07-14", zone = "UTC",
            sessionStartAt = "2026-07-14T10:00:00Z", sessionEndAt = "2026-07-14T10:01:00Z",
            evidenceQuality = "LOCAL",
        )
        val recovery = validRecovery().copy(
            timedPlacements = listOf(timed),
            unknownAllocations = validAllocations().mapIndexed { index, row ->
                if (index == 0) row.copy(minutes = row.minutes - 1L) else row
            },
        )
        val problems = v3(recovery).assertRejected()
        assertTrue(problems.any { it.detail.contains("no matching exported session") })
    }

    @Test
    fun replacementRevisionAheadOfContribution_rejected() {
        val recovery = validRecovery().copy(
            replacements = listOf(validReplacement().copy(currentRevision = 9)),
        )
        val problems = v3(recovery).assertRejected()
        assertTrue(problems.any { it.detail.contains("inconsistent with contribution revision") })
    }

    @Test
    fun danglingReplacementLink_rejected() {
        val recovery = validRecovery().copy(
            replacements = listOf(validReplacement().copy(contributionId = "missing")),
        )
        val problems = v3(recovery).assertRejected()
        assertTrue(problems.any { it.detail.contains("dangling replacement link") })
    }

    @Test
    fun recipeRevisionMismatch_rejected() {
        val recovery = validRecovery().copy(
            recipes = listOf(validRecipe().copy(revision = 2)),
        )
        val problems = v3(recovery).assertRejected()
        assertTrue(problems.any { it.detail.contains("recipe revision") })
    }

    @Test
    fun compactExcludedDateRange_isValidWithoutExpandingItsWindow() {
        val shares = listOf(
            allocation("2026-07-14", 35L),
            allocation("2026-07-16", 35L),
            allocation("2026-07-17", 35L),
        )
        val recipe = validRecipe().copy(
            quotient = 35L,
            remainderCount = 0L,
            eligibleCount = 3,
            excludedDatesCsv = "2026-07-15",
            materializedMinutes = 105L,
            materializedThroughDate = "2026-07-17",
        )
        val file = v3(
            recovery = validRecovery().copy(unknownAllocations = shares, recipes = listOf(recipe)),
        )
        assertTrue("compact exact exclusions are valid", BackupValidator.validate(file) is BackupValidationResult.Valid)
    }

    @Test
    fun compactExclusionOutsideRecipeWindow_isRejected() {
        val file = v3(
            recovery = validRecovery().copy(
                recipes = listOf(validRecipe().copy(excludedDatesCsv = "2026-07-12..2026-07-13")),
            ),
        )
        val problems = file.assertRejected()
        assertTrue(problems.any { it.detail.contains("outside its recipe window") })
    }

    @Test
    fun v1CarryingCloudContribution_rejected() {
        val file = v3(recovery = validRecovery(), sessions = listOf(validSession())).copy(formatVersion = 1)
        val problems = file.assertRejected()
        assertTrue(problems.any { it.detail.contains("v1 cannot contain cloudContribution") })
    }

    // ------------------------------------------------------------------ fixtures

    private fun validRecovery() = BackupRecovery(
        observations = listOf(validObservation()),
        contributions = listOf(validContribution()),
        timedPlacements = emptyList(),
        unknownAllocations = validAllocations(),
        recipes = emptyList(),
        replacements = listOf(validReplacement()),
    )

    private fun observationOnlyRecovery(epoch: Long) = BackupRecovery(
        observations = listOf(validObservation().copy(baselineEpoch = epoch)),
        contributions = emptyList(),
        timedPlacements = emptyList(),
        unknownAllocations = emptyList(),
        recipes = emptyList(),
        replacements = emptyList(),
    )

    private fun recoveryForAccount(account: String) = validRecovery().copy(
        observations = listOf(validObservation().copy(account = account)),
        contributions = listOf(validContribution().copy(account = account)),
        unknownAllocations = validAllocations().map { it.copy(account = account) },
        replacements = listOf(validReplacement().copy(account = account)),
    )

    private fun validObservation() = BackupRecoveryObservation(
        account = ACCOUNT,
        appId = APP_ID,
        baselineEpoch = 1,
        highWaterMinutes = 605,
        lastObservationAt = "2026-07-17T12:00:00Z",
        lastObservationDate = "2026-07-17",
        lastObservationZone = "UTC",
        clockState = "VALID",
    )

    private fun validContribution() = BackupContribution(
        contributionId = "c1",
        account = ACCOUNT,
        appId = APP_ID,
        baselineEpoch = 1,
        fromMinutes = 500,
        toMinutes = 605,
        windowStartAt = "2026-07-13T12:00:00Z",
        windowEndAt = "2026-07-17T12:00:00Z",
        authorizedMinutes = 105,
        revision = 1,
        provenance = "RETROACTIVE_PLAY",
        zone = "UTC",
        observedZone = "UTC",
    )

    private fun validAllocations() = listOf(
        allocation("2026-07-14", 27),
        allocation("2026-07-15", 26),
        allocation("2026-07-16", 26),
        allocation("2026-07-17", 26),
    )

    private fun allocation(date: String, minutes: Long) = BackupUnknownAllocation(
        placementId = "p-$date",
        contributionId = "c1",
        account = ACCOUNT,
        appId = APP_ID,
        revision = 1,
        date = date,
        minutes = minutes,
        method = "MULTI_DAY_ESTIMATE",
        zone = "UTC",
        windowStartAt = "2026-07-13T12:00:00Z",
        windowEndAt = "2026-07-17T12:00:00Z",
        pendingDate = false,
        createdAt = 0,
    )

    private fun validRecipe() = BackupAllocationRecipe(
        contributionId = "c1",
        account = ACCOUNT,
        appId = APP_ID,
        revision = 1,
        method = "MULTI_DAY_ESTIMATE",
        quotient = 26,
        remainderCount = 1,
        dateStart = "2026-07-14",
        dateEnd = "2026-07-17",
        eligibleCount = 4,
        zone = "UTC",
        windowStartAt = "2026-07-13T12:00:00Z",
        windowEndAt = "2026-07-17T12:00:00Z",
        pending = false,
        materializedMinutes = 105,
        materializedThroughDate = "2026-07-17",
    )

    private fun validReplacement() = BackupReplacement(
        operationId = "op1",
        contributionId = "c1",
        account = ACCOUNT,
        appId = APP_ID,
        priorRevision = null,
        currentRevision = 1,
        affectedDatesCsv = "2026-07-14,2026-07-15",
        replacedPlacementIdsCsv = "c1:0:t:-:2026-07-14:-",
        replacedRecipeJson = null,
        derivedStatus = "FINALIZED",
    )

    private fun validSession() = BackupSession(
        appId = APP_ID,
        startAt = "2026-07-17T10:00:00Z",
        endAt = "2026-07-17T11:00:00Z",
        minutes = 60,
        cloudContribution = BackupCloudContribution(
            BackupContributionState.UNKNOWN,
            BackupContributionState.UNKNOWN,
        ),
    )

    private fun v3(
        recovery: BackupRecovery?,
        sessions: List<BackupSession> = emptyList(),
    ) = BackupFile(
        formatVersion = 3,
        exportedAt = "2026-07-17T12:00:00Z",
        identity = BackupIdentity(steamId64 = ACCOUNT),
        ruleConfig = BackupRuleConfig(1, 100, 30, "ANY_GAME", 0, 5, 10, 20, 40, 80),
        games = listOf(BackupGame(appId = APP_ID, name = "Game", isGoal = false, backfillMinutes = 0)),
        achievements = emptyList(),
        sessions = sessions,
        dailyProgress = emptyList(),
        hltbData = emptyList(),
        librarySortPrefs = BackupLibrarySortPrefs(focus = "NAME", library = "PLAYTIME"),
        playerProfile = BackupPlayerProfile(0, 1, 0, 0, false),
        computed = BackupComputed(emptyList(), emptyList()),
        recovery = recovery,
    )

    private fun BackupFile.assertRejected(): List<BackupValidationProblem> {
        val result = BackupValidator.validate(this)
        assertTrue(result is BackupValidationResult.Invalid)
        return (result as BackupValidationResult.Invalid).problems
    }

    private companion object {
        const val ACCOUNT = "76561198000000000"
        const val FOREIGN_ACCOUNT = "76561198000000001"
        const val APP_ID = 440L
    }
}
