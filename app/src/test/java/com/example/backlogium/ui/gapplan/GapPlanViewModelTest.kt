package com.example.backlogium.ui.gapplan

import com.example.backlogium.data.repo.GameCategory
import com.example.backlogium.data.repo.GameGenre
import com.example.backlogium.domain.GameSource
import com.example.backlogium.domain.gapplan.CapacityProvenance
import com.example.backlogium.domain.gapplan.CurrentPlayerCounts
import com.example.backlogium.domain.gapplan.GapPlanIntent
import com.example.backlogium.domain.gapplan.GapPlanLiveCounts
import com.example.backlogium.domain.gapplan.GapPlanReason
import com.example.backlogium.domain.gapplan.GapPlanRequestError
import com.example.backlogium.domain.gapplan.GapPlanTestEnvironment
import com.example.backlogium.domain.gapplan.PlanIntensity
import com.example.backlogium.domain.gapplan.TODAY
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger

/**
 * The gap-plan session, over the real stack with every network path broken.
 *
 * The behaviours that matter most here are the ones a player would only notice after committing a
 * month to a plan: that the snapshot holds still, that an edit changes only what was asked, and
 * that a failed save keeps the decision the player already made.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class GapPlanViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var env: GapPlanTestEnvironment

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        env = GapPlanTestEnvironment()
    }

    @After fun tearDown() {
        env.close()
        Dispatchers.resetMain()
    }

    @Test fun aReliableProfileNeedsNoManualBudgetAndReportsPersonalPaceProvenance() = runTest {
        seedLibrary()
        env.seedReliablePace(appId = 1)
        val viewModel = viewModel()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.requiresManualBudget)

        fillSetup(viewModel)
        viewModel.generate()
        advanceUntilIdle()

        val result = viewModel.uiState.value.result!!
        assertEquals(CapacityProvenance.PERSONAL_PACE, result.provenance)
        assertTrue(result.fullCapacityMinutes > 0)
        assertEquals(3, result.variants.size)
    }

    /** A learning profile cannot make a feasibility claim, so it asks for the budget instead. */
    @Test fun aLearningProfileRequiresManualHoursBeforeGenerationIsOffered() = runTest {
        seedLibrary()
        val viewModel = viewModel()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.requiresManualBudget)

        fillSetup(viewModel)
        assertFalse("without hours, generation must not be offered", viewModel.uiState.value.canGenerate)

        viewModel.setManualTotalHours(20)
        assertTrue(viewModel.uiState.value.canGenerate)
        // Clamped to the slider range, so nothing can push the budget past its ceiling.
        viewModel.setManualTotalHours(10_000)
        assertEquals(
            GapPlanViewModel.MAX_MANUAL_HOURS,
            viewModel.uiState.value.setup.manualTotalHours,
        )
        viewModel.setManualTotalHours(-5)
        assertEquals(0, viewModel.uiState.value.setup.manualTotalHours)
        assertFalse("zero hours is not an answer", viewModel.uiState.value.canGenerate)
        viewModel.setManualTotalHours(20)
        viewModel.generate()
        advanceUntilIdle()

        val result = viewModel.uiState.value.result!!
        assertEquals(CapacityProvenance.MANUAL, result.provenance)
        assertEquals(1_200, result.fullCapacityMinutes)
    }

    @Test fun aTargetDateBeyondTheHorizonIsReportedRatherThanPlanned() = runTest {
        seedLibrary()
        env.seedReliablePace(appId = 1)
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.setAnticipatedTitle("Sequel")
        viewModel.setTargetDate(TODAY.plusDays(2_000))
        viewModel.generate()
        advanceUntilIdle()

        assertEquals(
            GapPlanRequestError.TARGET_DATE_BEYOND_HORIZON,
            viewModel.uiState.value.validationError,
        )
        assertNull(viewModel.uiState.value.result)
    }

    /** An offline library with no cached metadata still plans, and discloses what was missing. */
    @Test fun anOfflineLibraryWithNoMetadataStillProducesPlansAndDisclosesCoverage() = runTest {
        env.addGame(1)
        env.addHltb(1, mainStory = 600)
        env.addGame(2)
        // 2 has no HLTB row at all.
        env.seedReliablePace(appId = 1)
        val viewModel = viewModel()
        advanceUntilIdle()

        fillSetup(viewModel)
        viewModel.generate()
        advanceUntilIdle()

        val result = viewModel.uiState.value.result!!
        assertEquals(listOf(1L), result.variant(PlanIntensity.FULL)!!.members.map { it.appId })
        assertFalse(result.coverage.isComplete)
        assertEquals(1, result.coverage.missingSelectedEstimate)
        assertEquals(0, result.coverage.withCachedReviews)
        // Nothing claims a rating it does not have.
        assertTrue(
            result.variant(PlanIntensity.FULL)!!.members
                .single().reasons.none { it is GapPlanReason.ReviewQuality },
        )
    }

    @Test fun anEmptyLibraryProducesThreeEmptyVariantsRatherThanAnError() = runTest {
        env.addGame(1)
        env.seedReliablePace(appId = 1)
        // Game 1 has no estimate, so nothing is eligible.
        val viewModel = viewModel()
        advanceUntilIdle()

        fillSetup(viewModel)
        viewModel.generate()
        advanceUntilIdle()

        val result = viewModel.uiState.value.result!!
        assertNull(viewModel.uiState.value.validationError)
        assertTrue(result.variants.all { it.isEmpty })
    }

    /**
     * The reserve is measured against the variant's own budget, while the request's whole forecast
     * stays visible. Showing only the per-variant figure would present a Relaxed plan's reduced
     * budget as all the time the player has.
     */
    @Test fun eachVariantReportsItsOwnBudgetWhileTheFullForecastStaysVisible() = runTest {
        seedLibrary()
        env.seedReliablePace(appId = 1)
        val viewModel = viewModel()
        advanceUntilIdle()
        fillSetup(viewModel)
        viewModel.generate()
        advanceUntilIdle()

        val result = viewModel.uiState.value.result!!
        val relaxed = result.variant(PlanIntensity.RELAXED)!!
        assertEquals(result.fullCapacityMinutes * 70 / 100, relaxed.budgetMinutes)
        assertEquals(relaxed.budgetMinutes - relaxed.plannedMinutes, relaxed.reserveMinutes)
        assertTrue(
            "the withheld 30% must be recoverable from the state",
            relaxed.budgetMinutes < result.fullCapacityMinutes,
        )
    }

    @Test fun removingAMemberUpdatesOnlyThatVariant() = runTest {
        seedLibrary()
        env.seedReliablePace(appId = 1)
        val viewModel = viewModel()
        advanceUntilIdle()
        fillSetup(viewModel)
        viewModel.generate()
        advanceUntilIdle()

        val before = viewModel.uiState.value.result!!
        val target = before.variant(PlanIntensity.FULL)!!
        val removed = target.members.first().appId
        val otherVariantBefore = before.variant(PlanIntensity.RELAXED)!!.members.map { it.appId }

        viewModel.removeMember(PlanIntensity.FULL, removed)

        val after = viewModel.uiState.value.result!!
        val updated = after.variant(PlanIntensity.FULL)!!
        assertFalse(updated.members.any { it.appId == removed })
        assertEquals(target.members.size - 1, updated.members.size)
        assertEquals(updated.budgetMinutes - updated.plannedMinutes, updated.reserveMinutes)
        assertEquals(otherVariantBefore, after.variant(PlanIntensity.RELAXED)!!.members.map { it.appId })
    }

    @Test fun onlyBudgetValidReplacementsAreOffered() = runTest {
        seedLibrary()
        env.seedReliablePace(appId = 1)
        val viewModel = viewModel()
        advanceUntilIdle()
        fillSetup(viewModel)
        viewModel.generate()
        advanceUntilIdle()

        val variant = viewModel.uiState.value.result!!.variant(PlanIntensity.FULL)!!
        val member = variant.members.first()
        viewModel.offerReplacements(PlanIntensity.FULL, member.appId)

        val offered = viewModel.uiState.value.replacement!!
        assertEquals(member.appId, offered.forAppId)
        val headroom = variant.budgetMinutes - variant.plannedMinutes + member.remainingMinutes
        assertTrue(offered.candidates.all { it.remainingMinutes <= headroom })
        assertTrue(offered.candidates.none { c -> variant.members.any { it.appId == c.appId } })
    }

    /** An unaccepted snapshot holds still until the player explicitly regenerates it. */
    @Test fun libraryChangesDoNotDisturbAnOpenResultUntilRegeneration() = runTest {
        seedLibrary()
        env.seedReliablePace(appId = 1)
        val viewModel = viewModel()
        advanceUntilIdle()
        fillSetup(viewModel)
        viewModel.generate()
        advanceUntilIdle()

        val before = viewModel.uiState.value.result!!.variants.map { v -> v.members.map { it.appId } }

        // A new, very attractive game arrives while the player is reading the result.
        env.addGame(99, name = "Newcomer")
        env.addHltb(99, mainStory = 120)
        env.addReview(99, "Overwhelmingly Positive", positive = 100_000, negative = 100)
        advanceUntilIdle()

        assertEquals(
            before,
            viewModel.uiState.value.result!!.variants.map { v -> v.members.map { it.appId } },
        )

        viewModel.generate()
        advanceUntilIdle()

        assertTrue(
            "an explicit rebuild may take the newcomer into account",
            viewModel.uiState.value.result!!.variants.any { v -> v.members.any { it.appId == 99L } },
        )
    }

    @Test fun savingCreatesTheCollectionAndExposesItForNavigation() = runTest {
        seedLibrary()
        env.seedReliablePace(appId = 1)
        val viewModel = viewModel()
        advanceUntilIdle()
        fillSetup(viewModel)
        viewModel.generate()
        advanceUntilIdle()

        viewModel.reviewSave(PlanIntensity.FULL)
        val confirmation = viewModel.uiState.value.confirmation!!
        assertEquals("Before Anticipated Game", confirmation.collectionName)
        assertEquals(TODAY.plusDays(60), confirmation.targetDate)
        assertEquals(GapPlanIntent.STORY, confirmation.intent)
        assertEquals(
            viewModel.uiState.value.result!!.variant(PlanIntensity.FULL)!!.members.size,
            confirmation.memberCount,
        )

        viewModel.confirmSave()
        advanceUntilIdle()

        // Room resumes on its own executor, so the scheduler going idle does not mean the save
        // finished. `runTest` waits in real time while the test body is suspended on the state.
        val created = viewModel.uiState.first { it.createdCollectionId != null }.createdCollectionId!!
        assertNull(viewModel.uiState.value.confirmation)
        assertFalse(viewModel.uiState.value.saving)
        assertEquals("Before Anticipated Game", env.db.collectionDao().getById(created)!!.name)

        viewModel.consumeCreatedCollection()
        assertNull(viewModel.uiState.value.createdCollectionId)
    }

    /** A failed save releases busy state and keeps the preview, so the decision is not lost. */
    @Test fun aFailedSaveIsRecoverableAndRetainsThePreview() = runTest {
        val failing = GapPlanTestEnvironment(transaction = RefusingTransaction())
        try {
            failing.addGame(1)
            failing.addHltb(1, mainStory = 600)
            failing.seedReliablePace(appId = 1)
            val viewModel = GapPlanViewModel(
                feed = failing.feed,
                liveCounts = GapPlanLiveCounts(CurrentPlayerCounts { null }),
                collectionCreator = failing.collectionCreator,
            )
            advanceUntilIdle()
            fillSetup(viewModel)
            viewModel.generate()
            advanceUntilIdle()
            val preview = viewModel.uiState.value.result!!

            viewModel.reviewSave(PlanIntensity.FULL)
            viewModel.confirmSave()
            advanceUntilIdle()
            viewModel.uiState.first { it.saveError }

            assertTrue(viewModel.uiState.value.saveError)
            assertFalse(viewModel.uiState.value.saving)
            assertNull(viewModel.uiState.value.createdCollectionId)
            // The preview is exactly as it was, ready to submit again.
            assertEquals(preview, viewModel.uiState.value.result)

            viewModel.clearSaveError()
            assertFalse(viewModel.uiState.value.saveError)
        } finally {
            failing.close()
        }
    }

    /** Single-player members cost no lookup at all, rather than one that is issued and discarded. */
    @Test fun noLiveLookupIsIssuedForASinglePlayerOnlyPlan() = runTest {
        seedLibrary()
        env.seedReliablePace(appId = 1)
        val requested = AtomicInteger()
        val viewModel = viewModel(
            counts = CurrentPlayerCounts { requested.incrementAndGet(); 100 },
        )
        advanceUntilIdle()
        fillSetup(viewModel)
        viewModel.generate()
        advanceUntilIdle()

        assertEquals(0, requested.get())
        assertTrue(
            viewModel.uiState.value.result!!.variants.all { variant ->
                variant.members.none { m -> m.reasons.any { it is GapPlanReason.PlayingNow } }
            },
        )
    }

    /** And a multiplayer member gains a factual chip once its count arrives. */
    @Test fun aMultiplayerMemberGainsALiveChipWithoutChangingMembership() = runTest {
        env.addGame(1)
        env.addHltb(1, mainStory = 600)
        env.addStoreMetadata(
            1,
            genres = listOf(GameGenre("1", "Action")),
            categories = listOf(GameCategory(1, "Multi-player")),
        )
        env.seedReliablePace(appId = 1)
        val viewModel = viewModel(counts = CurrentPlayerCounts { 4_321 })
        advanceUntilIdle()
        fillSetup(viewModel)
        viewModel.generate()
        advanceUntilIdle()

        val member = viewModel.uiState.value.result!!.variant(PlanIntensity.FULL)!!.members.single()
        assertEquals(1L, member.appId)
        assertTrue(member.isMultiplayer)
        assertTrue(member.reasons.contains(GapPlanReason.PlayingNow(4_321)))
    }

    @Test fun familySharedMembersAreLabelled() = runTest {
        env.addGame(1, source = GameSource.FAMILY_SHARED, manualSharedMinutes = 60)
        env.addHltb(1, mainStory = 600)
        // Paced on a separate owned game: a family-shared game counts tracked sessions as
        // playtime, so seeding forty days of them onto game 1 would complete it instead.
        env.addGame(PACE_GAME)
        env.seedReliablePace(appId = PACE_GAME)
        val viewModel = viewModel()
        advanceUntilIdle()
        fillSetup(viewModel)
        viewModel.generate()
        advanceUntilIdle()

        val member = viewModel.uiState.value.result!!.variant(PlanIntensity.FULL)!!.members.single()
        assertTrue(member.isFamilyShared)
        assertEquals(540, member.remainingMinutes)
    }

    private suspend fun seedLibrary() {
        (1L..6L).forEach { appId ->
            env.addGame(appId)
            env.addHltb(appId, mainStory = 200 * appId.toInt())
            env.addStoreMetadata(appId, genres = listOf(GameGenre("$appId", "Genre $appId")))
        }
    }

    private fun fillSetup(viewModel: GapPlanViewModel) {
        viewModel.setAnticipatedTitle("Anticipated Game")
        viewModel.setTargetDate(TODAY.plusDays(60))
        viewModel.setIntent(GapPlanIntent.STORY)
    }

    private fun viewModel(
        counts: CurrentPlayerCounts = CurrentPlayerCounts { null },
    ) = GapPlanViewModel(
        feed = env.feed,
        liveCounts = GapPlanLiveCounts(counts),
        collectionCreator = env.collectionCreator,
    )

    private companion object {
        /** An owned game used only to establish a pace, never to be planned. */
        const val PACE_GAME = 500L
    }

    /** Fails the commit boundary, the only way to reach the interrupted-write case. */
    private class RefusingTransaction : com.example.backlogium.data.backup.DatabaseTransactionScope {
        override suspend fun <R> run(block: suspend () -> R): R =
            throw IllegalStateException("commit refused")
    }
}
