package com.example.backlogium.ui.settings.hidden

import androidx.room.Room
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.GameGenreCache
import com.example.backlogium.data.local.entity.Session
import com.example.backlogium.data.repo.HiddenGamesRepository
import com.example.backlogium.domain.DerivedStateWriteCoordinator
import com.example.backlogium.domain.FakeSettingsRepository
import com.example.backlogium.domain.GameVisibilityUseCase
import com.example.backlogium.domain.GamificationUpdater
import com.example.backlogium.domain.TimeProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.LocalDate
import java.time.ZoneId

/**
 * The non-game bulk action proposes; it never acts (add-hidden-games design decision 7).
 *
 * Store types are occasionally wrong, so the three properties asserted here are the ones that keep
 * a misclassification from silently taking a game and its XP: an unknown type is never offered,
 * nothing is hidden until the player confirms, and a confirmed group is hidden together and stays
 * individually recoverable.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NonGameBulkHideTest {

    private lateinit var db: BacklogiumDatabase

    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), BacklogiumDatabase::class.java,
        ).allowMainThreadQueries().build()

        db.gameDao().upsertAll(
            listOf(
                game(GAME, "A Real Game"),
                game(TOOL, "Wallpaper Engine"),
                game(APPLICATION, "SteamVR"),
                game(UNKNOWN_TYPE, "Never Enriched"),
            ),
        )
        // The store answered for three of them; the fourth has never been enriched.
        db.gameGenreCacheDao().upsert(GameGenreCache(GAME, "[]", checkedAt = 1L, appType = "game"))
        db.gameGenreCacheDao().upsert(GameGenreCache(TOOL, "[]", checkedAt = 1L, appType = "tool"))
        db.gameGenreCacheDao().upsert(GameGenreCache(APPLICATION, "[]", checkedAt = 1L, appType = "application"))
        db.sessionDao().insert(
            Session(appId = TOOL, startAt = 1_000L, endAt = 2_000L, minutes = 120, open = false),
        )
        Unit
    }

    @After fun tearDown() = db.close()

    @Test
    fun onlyKnownNonGames_areOffered() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val viewModel = viewModel()
            val collector = backgroundScope.launch { viewModel.uiState.collect() }
            advanceUntilIdle()

            assertEquals(
                listOf("SteamVR", "Wallpaper Engine"),
                viewModel.uiState.value.nonGameCandidates.map { it.name },
            )
            collector.cancel()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun requestingTheBulkHide_disclosesTheEffectAndHidesNothingYet() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val viewModel = viewModel()
            val collector = backgroundScope.launch { viewModel.uiState.collect() }
            advanceUntilIdle()

            viewModel.openNonGameReview()
            viewModel.requestBulkHide()
            advanceUntilIdle()

            val effect = viewModel.uiState.value.pendingEffect
            assertNotNull("the group's combined effect must be disclosed", effect)
            assertEquals(setOf(APPLICATION, TOOL), effect!!.appIds.toSet())
            // The tool's 120 tracked minutes leave XP, and nothing is hidden until confirmation.
            assertEquals(120, effect.totalXpBefore)
            assertEquals(0, effect.totalXpAfter)
            assertTrue(db.hiddenGameDao().hiddenAppIds().isEmpty())

            viewModel.dismiss()
            advanceUntilIdle()
            assertTrue("declining hides nothing", db.hiddenGameDao().hiddenAppIds().isEmpty())
            collector.cancel()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun confirmedGroup_isHiddenTogetherAndRecordedAsBulk() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val viewModel = viewModel()
            val collector = backgroundScope.launch { viewModel.uiState.collect() }
            advanceUntilIdle()

            viewModel.openNonGameReview()
            viewModel.requestBulkHide()
            advanceUntilIdle()
            viewModel.confirm()
            advanceUntilIdle()

            assertEquals(setOf(APPLICATION, TOOL), db.hiddenGameDao().hiddenAppIds().toSet())
            assertTrue(db.hiddenGameDao().getAll().all { it.fromBulkAction })
            // Each remains individually recoverable, and the offer is empty now they are hidden.
            assertTrue(viewModel.uiState.value.nonGameCandidates.isEmpty())
            assertEquals(2, viewModel.uiState.value.hidden.size)

            viewModel.requestUnhide(TOOL)
            advanceUntilIdle()
            viewModel.confirm()
            advanceUntilIdle()

            assertEquals(listOf(APPLICATION), db.hiddenGameDao().hiddenAppIds())
            collector.cancel()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun deselectingAMisclassifiedItem_leavesItVisible() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val viewModel = viewModel()
            val collector = backgroundScope.launch { viewModel.uiState.collect() }
            advanceUntilIdle()

            viewModel.openNonGameReview()
            viewModel.toggleCandidate(TOOL)
            viewModel.requestBulkHide()
            advanceUntilIdle()
            viewModel.confirm()
            advanceUntilIdle()

            assertEquals(listOf(APPLICATION), db.hiddenGameDao().hiddenAppIds())
            collector.cancel()
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun viewModel(): HiddenGamesViewModel {
        val hidden = HiddenGamesRepository(
            hiddenGameDao = db.hiddenGameDao(),
            gameDao = db.gameDao(),
            storeCacheDao = db.gameGenreCacheDao(),
            time = FixedTime,
        )
        val updater = GamificationUpdater(
            db.sessionDao(),
            db.dailyProgressDao(),
            db.playerProfileDao(),
            db.hltbDataDao(),
            db.achievementDao(),
            db.gameDao(),
            db.hiddenGameDao(),
        )
        return HiddenGamesViewModel(
            hiddenGames = hidden,
            visibility = GameVisibilityUseCase(
                hiddenGames = hidden,
                gamificationUpdater = updater,
                gameDao = db.gameDao(),
                settings = FakeSettingsRepository(),
                time = FixedTime,
                derivedStateWrites = DerivedStateWriteCoordinator(),
            ),
        )
    }

    private fun game(appId: Long, name: String) = Game(
        appId = appId,
        name = name,
        iconUrl = "",
        playtimeForever = 120,
        playtime2Weeks = 0,
        lastPlaytime = 120,
    )

    private object FixedTime : TimeProvider {
        override fun nowMillis(): Long = 1_700_000_000_000L
        override fun zone(): ZoneId = ZoneId.of("UTC")
        override fun today(): LocalDate = LocalDate.parse("2026-08-22")
    }

    private companion object {
        const val GAME = 1L
        const val TOOL = 2L
        const val APPLICATION = 3L
        const val UNKNOWN_TYPE = 4L
    }
}
