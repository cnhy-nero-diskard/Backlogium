package com.example.backlogium.ui.gapplan

import com.example.backlogium.data.repo.GameCategory
import com.example.backlogium.data.repo.GameGenre
import com.example.backlogium.domain.GameSource
import com.example.backlogium.domain.gapplan.CapacityProvenance
import com.example.backlogium.domain.gapplan.CurrentPlayerCounts
import com.example.backlogium.domain.gapplan.GapPlanFact
import com.example.backlogium.domain.gapplan.GapPlanIntent
import com.example.backlogium.domain.gapplan.GapPlanLiveCounts
import com.example.backlogium.domain.gapplan.GapPlanSeeds
import com.example.backlogium.domain.gapplan.GapPlanTestEnvironment
import com.example.backlogium.domain.gapplan.PlanIntensity
import com.example.backlogium.domain.gapplan.TODAY
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The gap-plan session, over the real stack with every network path broken.
 *
 * The behaviours that matter most here are the ones a player would only notice after committing a
 * month to a suggestion: that a shown result holds still, that rebuilding genuinely changes it,
 * that inspecting a pick does not, and that a failed save keeps the decision already made.
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
        env.seedReliablePace(appId = PACE_GAME)
        val viewModel = viewModel()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.requiresManualBudget)

        fillSetup(viewModel)
        viewModel.generate()
        advanceUntilIdle()

        val result = viewModel.uiState.value.result!!
        assertEquals(CapacityProvenance.PERSONAL_PACE, result.provenance)
        assertTrue(result.fullCapacityMinutes > 0)
        assertEquals(3, result.picks.size)
        assertEquals(
            listOf(PlanIntensity.RELAXED, PlanIntensity.BALANCED, PlanIntensity.FULL),
            result.picks.map { it.intensity },
        )
    }

    /** Three tiers, one game each, all distinct. */
    @Test fun eachTierOffersOneDistinctGame() = runTest {
        seedLibrary()
        env.seedReliablePace(appId = PACE_GAME)
        val viewModel = viewModel()
        advanceUntilIdle()
        fillSetup(viewModel)
        viewModel.generate()
        advanceUntilIdle()

        val picked = viewModel.uiState.value.result!!.picks.mapNotNull { it.game?.appId }
        assertEquals(3, picked.size)
        assertEquals(3, picked.toSet().size)
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

    @Test fun aTargetDateBeyondTheHorizonIsUnavailable() = runTest {
        seedLibrary()
        env.seedReliablePace(appId = PACE_GAME)
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.setAnticipatedTitle("Sequel")
        viewModel.setTargetDate(TODAY.plusDays(2_000))
        assertFalse(viewModel.uiState.value.canGenerate)
        viewModel.generate()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.validationError)
        assertNull(viewModel.uiState.value.result)
    }

    /** An offline library with no cached metadata still suggests, and discloses what was missing. */
    @Test fun anOfflineLibraryWithNoMetadataStillOffersPicksAndDisclosesCoverage() = runTest {
        env.addGame(1)
        env.addHltb(1, mainStory = 600)
        env.addGame(2)
        // 2 has no HLTB row at all.
        env.addGame(PACE_GAME)
        env.seedReliablePace(appId = PACE_GAME)
        val viewModel = viewModel()
        advanceUntilIdle()

        fillSetup(viewModel)
        viewModel.generate()
        advanceUntilIdle()

        val result = viewModel.uiState.value.result!!
        assertEquals(listOf(1L), result.picks.mapNotNull { it.game?.appId })
        assertFalse(result.coverage.isComplete)
        assertEquals(2, result.coverage.missingSelectedEstimate)
        assertEquals(0, result.coverage.withCachedReviews)
        // Nothing claims a rating or a genre it does not have.
        val game = result.pick(PlanIntensity.FULL)!!.game!!
        assertTrue(game.facts.none { it is GapPlanFact.Reviews })
        assertTrue(game.genreLabels.isEmpty())
    }

    @Test fun anEmptyEligiblePoolProducesThreeEmptyTiersRatherThanAnError() = runTest {
        env.addGame(1)
        env.addGame(PACE_GAME)
        env.seedReliablePace(appId = PACE_GAME)
        // No game has an estimate, so nothing is eligible.
        val viewModel = viewModel()
        advanceUntilIdle()

        fillSetup(viewModel)
        viewModel.generate()
        advanceUntilIdle()

        val result = viewModel.uiState.value.result!!
        assertNull(viewModel.uiState.value.validationError)
        assertTrue(result.picks.all { it.isEmpty })
    }

    /**
     * Each tier reports its own share while the request's whole forecast stays visible. Showing
     * only the per-tier figure would present a Relaxed share as all the time the player has.
     */
    @Test fun eachTierReportsItsOwnShareWhileTheFullForecastStaysVisible() = runTest {
        seedLibrary()
        env.seedReliablePace(appId = PACE_GAME)
        val viewModel = viewModel()
        advanceUntilIdle()
        fillSetup(viewModel)
        viewModel.generate()
        advanceUntilIdle()

        val result = viewModel.uiState.value.result!!
        val relaxed = result.pick(PlanIntensity.RELAXED)!!
        assertEquals(result.fullCapacityMinutes * 70 / 100, relaxed.budgetMinutes)
        assertTrue(
            "the withheld 30% must be recoverable from the state",
            relaxed.budgetMinutes < result.fullCapacityMinutes,
        )
    }

    /**
     * Rebuilding is a real reroll.
     *
     * The previous surface could not do this: picks were fully determined by the inputs, so the
     * control could only ever repaint what was already there. Each generation now draws a seed.
     */
    @Test fun rebuildingChangesThePicks() = runTest {
        seedLibrary()
        env.seedReliablePace(appId = PACE_GAME)
        val viewModel = viewModel()
        advanceUntilIdle()
        fillSetup(viewModel)
        viewModel.generate()
        advanceUntilIdle()

        val before = viewModel.uiState.value.result!!.picks.mapNotNull { it.game?.appId }

        viewModel.generate()
        advanceUntilIdle()

        val after = viewModel.uiState.value.result!!.picks.mapNotNull { it.game?.appId }
        assertNotEquals(before, after)
        assertFalse(viewModel.uiState.value.rebuildDidNotVary)
    }

    /** An alternate reachable trio is found even when every supplied reroll seed repeats the first. */
    @Test fun aReachableRerollDoesNotConcedeAfterEightIdenticalSeeds() = runTest {
        seedLibrary()
        env.seedReliablePace(appId = PACE_GAME)
        val viewModel = viewModel(seeds = repeatedSeeds(seed = 1L, count = 10))
        advanceUntilIdle()
        fillSetup(viewModel)
        viewModel.generate()
        advanceUntilIdle()

        val before = viewModel.uiState.value.result!!.picks.mapNotNull { it.game?.appId }

        viewModel.generate()
        advanceUntilIdle()

        val after = viewModel.uiState.value.result!!.picks.mapNotNull { it.game?.appId }
        assertNotEquals(before, after)
        assertFalse(viewModel.uiState.value.rebuildDidNotVary)
    }

    @Test
    fun aRerollCancelsThePreviousLiveCountEnrichment() = runTest {
        seedLibrary()
        env.seedReliablePace(appId = PACE_GAME)
        (1L..6L).forEach { appId ->
            env.addStoreMetadata(
                appId,
                categories = listOf(GameCategory(1, "Multi-player")),
            )
        }
        val calls = AtomicInteger()
        val cancellations = AtomicInteger()
        val viewModel = viewModel(
            counts = CurrentPlayerCounts {
                val call = calls.incrementAndGet()
                try {
                    delay(1_000)
                    null
                } catch (error: CancellationException) {
                    if (call <= 3) cancellations.incrementAndGet()
                    throw error
                }
            },
        )
        advanceUntilIdle()
        fillSetup(viewModel)
        viewModel.generate()
        viewModel.uiState.first { it.result != null && !it.generating }
        runCurrent()
        assertEquals(3, calls.get())

        viewModel.generate()
        advanceUntilIdle()

        assertEquals(
            "all three requests from the superseded pass must be cancelled",
            3,
            cancellations.get(),
        )
    }

    /**
     * And when it cannot, it says so rather than appearing to have been ignored.
     *
     * Three widely separated lengths give each tier exactly one option, so no seed can produce a
     * different set. That is a real library shape, not a contrived one — a small backlog with one
     * long game, one medium and one short reaches it immediately.
     */
    @Test fun aRebuildThatCannotVarySaysSo() = runTest {
        env.addGame(PACE_GAME)
        env.seedReliablePace(appId = PACE_GAME, minutesPerDay = 120)
        // The forecast for 60 days lands near 7,200 minutes, so these three sit one per band.
        env.addGame(1)
        env.addHltb(1, mainStory = 7_000)
        env.addGame(2)
        env.addHltb(2, mainStory = 5_800)
        env.addGame(3)
        env.addHltb(3, mainStory = 4_700)
        val viewModel = viewModel()
        advanceUntilIdle()
        fillSetup(viewModel)
        viewModel.generate()
        advanceUntilIdle()
        val before = viewModel.uiState.value.result!!.picks.mapNotNull { it.game?.appId }

        viewModel.generate()
        advanceUntilIdle()

        val after = viewModel.uiState.value.result!!.picks.mapNotNull { it.game?.appId }
        assertEquals(before, after)
        assertTrue(
            "an unchanged rebuild has to explain itself",
            viewModel.uiState.value.rebuildDidNotVary,
        )
    }

    /** A first build has no previous set to differ from, so it never reports a failed reroll. */
    @Test fun aFirstBuildNeverReportsAnUnchangedPool() = runTest {
        env.addGame(PACE_GAME)
        env.seedReliablePace(appId = PACE_GAME)
        env.addGame(1)
        env.addHltb(1, mainStory = 600)
        val viewModel = viewModel()
        advanceUntilIdle()
        fillSetup(viewModel)
        viewModel.generate()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.rebuildDidNotVary)
        assertEquals(listOf(1L), viewModel.uiState.value.result!!.picks.mapNotNull { it.game?.appId })
    }

    /** An unaccepted result holds still until the player explicitly rebuilds it. */
    @Test fun libraryChangesDoNotDisturbAnOpenResultUntilRebuild() = runTest {
        seedLibrary()
        env.seedReliablePace(appId = PACE_GAME)
        val viewModel = viewModel()
        advanceUntilIdle()
        fillSetup(viewModel)
        viewModel.generate()
        advanceUntilIdle()

        val before = viewModel.uiState.value.result!!.picks.map { it.game?.appId }

        // A new game arrives while the player is reading the result.
        env.addGame(99, name = "Newcomer")
        env.addHltb(99, mainStory = 120)
        env.addReview(99, "Overwhelmingly Positive", positive = 100_000, negative = 100)
        advanceUntilIdle()

        assertEquals(before, viewModel.uiState.value.result!!.picks.map { it.game?.appId })
    }

    /**
     * Inspecting a pick is not a reroll. A player can open all three in turn and still accept the
     * one they started with.
     */
    @Test fun inspectingAPickLeavesThePicksUnchanged() = runTest {
        seedLibrary()
        env.seedReliablePace(appId = PACE_GAME)
        val viewModel = viewModel()
        advanceUntilIdle()
        fillSetup(viewModel)
        viewModel.generate()
        advanceUntilIdle()

        val before = viewModel.uiState.value.result!!
        val picks = before.picks.mapNotNull { it.game?.appId }

        picks.forEach { appId ->
            viewModel.inspect(appId)
            assertEquals(appId, viewModel.uiState.value.inspectingAppId)
            assertEquals(before, viewModel.uiState.value.result)
            viewModel.dismissInspection()
            assertNull(viewModel.uiState.value.inspectingAppId)
            assertEquals(before, viewModel.uiState.value.result)
        }
    }

    @Test
    fun aSaveConfirmationCommitsTheReviewedSnapshotAfterARerollPublishes() = runTest {
        env.addGame(PACE_GAME)
        env.seedReliablePace(appId = PACE_GAME)
        (1L..4L).forEach { appId ->
            env.addGame(appId)
            env.addHltb(appId, mainStory = 600)
        }
        val viewModel = viewModel()
        advanceUntilIdle()
        fillSetup(viewModel)
        viewModel.generate()
        advanceUntilIdle()

        val reviewed = viewModel.uiState.value.result!!.pick(PlanIntensity.FULL)!!.game!!
        viewModel.reviewSave(PlanIntensity.FULL)
        val confirmation = viewModel.uiState.value.confirmation!!

        viewModel.generate()
        advanceUntilIdle()

        assertNotEquals(
            reviewed.appId,
            viewModel.uiState.value.result!!.pick(PlanIntensity.FULL)!!.game!!.appId,
        )
        assertEquals(reviewed.name, confirmation.gameName)

        viewModel.confirmSave()
        val created = viewModel.uiState.first { it.createdCollectionId != null }.createdCollectionId!!

        assertEquals(reviewed.appId, env.db.collectionDao().getMembers(created).single().appId)
    }

    @Test fun savingCreatesTheCollectionAndExposesItForNavigation() = runTest {
        seedLibrary()
        env.seedReliablePace(appId = PACE_GAME)
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
            viewModel.uiState.value.result!!.pick(PlanIntensity.FULL)!!.game!!.name,
            confirmation.gameName,
        )

        viewModel.confirmSave()
        advanceUntilIdle()

        // Room resumes on its own executor, so the scheduler going idle does not mean the save
        // finished. `runTest` waits in real time while the test body is suspended on the state.
        val created = viewModel.uiState.first { it.createdCollectionId != null }.createdCollectionId!!
        assertNull(viewModel.uiState.value.confirmation)
        assertFalse(viewModel.uiState.value.saving)
        assertEquals("Before Anticipated Game", env.db.collectionDao().getById(created)!!.name)
        assertEquals(1, env.db.collectionDao().getMembers(created).size)

        viewModel.consumeCreatedCollection()
        assertNull(viewModel.uiState.value.createdCollectionId)
    }

    /** An empty tier offers nothing to review, so the confirmation never opens for it. */
    @Test fun anEmptyTierCannotBeSaved() = runTest {
        env.addGame(PACE_GAME)
        env.seedReliablePace(appId = PACE_GAME)
        env.addGame(1)
        env.addHltb(1, mainStory = 600)
        val viewModel = viewModel()
        advanceUntilIdle()
        fillSetup(viewModel)
        viewModel.generate()
        advanceUntilIdle()

        // One game, so the widest tier claims it and the other two are empty.
        assertTrue(viewModel.uiState.value.result!!.pick(PlanIntensity.RELAXED)!!.isEmpty)
        viewModel.reviewSave(PlanIntensity.RELAXED)

        assertNull(viewModel.uiState.value.confirmation)
    }

    /** A failed save releases busy state and keeps the result, so the decision is not lost. */
    @Test fun aFailedSaveIsRecoverableAndRetainsTheResult() = runTest {
        val failing = GapPlanTestEnvironment(transaction = RefusingTransaction())
        try {
            failing.addGame(1)
            failing.addHltb(1, mainStory = 600)
            failing.addGame(PACE_GAME)
            failing.seedReliablePace(appId = PACE_GAME)
            val viewModel = GapPlanViewModel(
                feed = failing.feed,
                liveCounts = GapPlanLiveCounts(CurrentPlayerCounts { null }),
                collectionCreator = failing.collectionCreator,
                seeds = sequentialSeeds(),
            )
            advanceUntilIdle()
            fillSetup(viewModel)
            viewModel.generate()
            advanceUntilIdle()
            val result = viewModel.uiState.value.result!!

            viewModel.reviewSave(PlanIntensity.FULL)
            viewModel.confirmSave()
            advanceUntilIdle()
            viewModel.uiState.first { it.saveError }

            assertTrue(viewModel.uiState.value.saveError)
            assertFalse(viewModel.uiState.value.saving)
            assertNull(viewModel.uiState.value.createdCollectionId)
            // The result is exactly as it was, ready to submit again.
            assertEquals(result, viewModel.uiState.value.result)

            viewModel.clearSaveError()
            assertFalse(viewModel.uiState.value.saveError)
        } finally {
            failing.close()
        }
    }

    /** Single-player picks cost no lookup at all, rather than one issued and discarded. */
    @Test fun noLiveLookupIsIssuedForAWhollySinglePlayerResult() = runTest {
        seedLibrary()
        env.seedReliablePace(appId = PACE_GAME)
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
            viewModel.uiState.value.result!!.picks.mapNotNull { it.game }
                .all { game -> game.facts.none { it is GapPlanFact.PlayingNow } },
        )
    }

    /** And a multiplayer pick gains a factual label once its count arrives. */
    @Test fun aMultiplayerPickGainsALiveFactWithoutChangingTheGame() = runTest {
        env.addGame(1)
        env.addHltb(1, mainStory = 600)
        env.addStoreMetadata(
            1,
            genres = listOf(GameGenre("1", "Action")),
            categories = listOf(GameCategory(1, "Multi-player")),
        )
        env.addGame(PACE_GAME)
        env.seedReliablePace(appId = PACE_GAME)
        val viewModel = viewModel(counts = CurrentPlayerCounts { 4_321 })
        advanceUntilIdle()
        fillSetup(viewModel)
        viewModel.generate()
        advanceUntilIdle()

        val game = viewModel.uiState.value.result!!.pick(PlanIntensity.FULL)!!.game!!
        assertEquals(1L, game.appId)
        assertTrue(game.isMultiplayer)
        assertEquals(listOf("Action"), game.genreLabels)
        assertTrue(game.facts.contains(GapPlanFact.PlayingNow(4_321)))
    }

    @Test fun familySharedPicksAreLabelled() = runTest {
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

        val game = viewModel.uiState.value.result!!.pick(PlanIntensity.FULL)!!.game!!
        assertTrue(game.isFamilyShared)
        assertEquals(540, game.remainingMinutes)
    }

    /**
     * Six games of widely varied lengths, plus a separate owned game carrying the pace.
     *
     * The pace game deliberately has no estimate so it never enters the pool: a game seeded with
     * forty days of sessions would be scored as heavily played, and the tests that assert on which
     * games were picked would be describing the fixture rather than the draw.
     */
    private suspend fun seedLibrary() {
        env.addGame(PACE_GAME)
        (1L..6L).forEach { appId ->
            env.addGame(appId)
            env.addHltb(appId, mainStory = 500 * appId.toInt())
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
        seeds: GapPlanSeeds = sequentialSeeds(),
    ) = GapPlanViewModel(
        feed = env.feed,
        liveCounts = GapPlanLiveCounts(counts),
        collectionCreator = env.collectionCreator,
        seeds = seeds,
    )

    /**
     * Seeds 1, 2, 3, … rather than real randomness.
     *
     * The properties under test are that identical seeds reproduce a result and that a *different*
     * seed changes it. Neither is assertable against a real generator: the test would have to say
     * "probably different", which is not an assertion. Successive integers also make the reroll
     * loop's behaviour legible — attempt one uses 1, the rebuild starts from 2.
     */
    private fun sequentialSeeds(): GapPlanSeeds {
        val next = AtomicLong(0L)
        return GapPlanSeeds { next.incrementAndGet() }
    }

    private fun repeatedSeeds(seed: Long, count: Int): GapPlanSeeds {
        val supplied = List(count) { seed }
        val next = AtomicInteger()
        return GapPlanSeeds { supplied.getOrElse(next.getAndIncrement()) { seed } }
    }

    private companion object {
        /** An owned game used only to establish a pace, never to be suggested. */
        const val PACE_GAME = 500L
    }

    /** Fails the commit boundary, the only way to reach the interrupted-write case. */
    private class RefusingTransaction : com.example.backlogium.data.backup.DatabaseTransactionScope {
        override suspend fun <R> run(block: suspend () -> R): R =
            throw IllegalStateException("commit refused")
    }
}
