package com.example.backlogium.domain

import com.example.backlogium.data.local.entity.Achievement
import com.example.backlogium.data.local.entity.DailyProgress
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.PlayerProfile
import com.example.backlogium.data.repo.HiddenGamesRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * The disclose-then-apply contract behind hiding (add-hidden-games).
 *
 * The load-bearing property is that the preview and the apply are the same computation: a
 * confirmation that states a level the hide does not actually produce would be worse than no
 * confirmation at all. The other half is that declining changes nothing — not the hidden set, not
 * XP, not a goal designation.
 */
class GameVisibilityUseCaseTest {

    @Test
    fun previewHide_statesTheRealXpAndLevelEffect() = runTest {
        val fixture = fixture()

        val effect = fixture.useCase.previewHide(listOf(TOOL))

        assertTrue(effect.hiding)
        assertEquals(listOf("Wallpaper Engine"), effect.names)
        assertEquals(740, effect.totalXpBefore)
        assertEquals(300, effect.totalXpAfter)
        assertEquals(4, effect.levelBefore)
        assertEquals(3, effect.levelAfter)
        assertTrue(effect.levelDrops)
        assertFalse(effect.noDerivedChange)
    }

    @Test
    fun previewHide_isWriteFree() = runTest {
        val fixture = fixture()

        fixture.useCase.previewHide(listOf(TOOL))

        assertTrue(fixture.hidden.hiddenAppIdSet().isEmpty())
        assertEquals(0, fixture.profileDao.upsertCount)
        assertTrue(fixture.gameDao.getById(TOOL)!!.isGoal)
    }

    @Test
    fun previewHide_reportsNoDerivedChangeForANeverPlayedGame() = runTest {
        val fixture = fixture()

        val effect = fixture.useCase.previewHide(listOf(UNPLAYED))

        assertTrue(effect.noDerivedChange)
        assertFalse(effect.levelDrops)
        assertEquals(effect.totalXpBefore, effect.totalXpAfter)
    }

    @Test
    fun previewHide_namesTheGoalDesignationItWillClear() = runTest {
        val fixture = fixture()

        assertEquals(listOf("Wallpaper Engine"), fixture.useCase.previewHide(listOf(TOOL)).clearedGoalNames)
        assertTrue(fixture.useCase.previewHide(listOf(UNPLAYED)).clearedGoalNames.isEmpty())
    }

    @Test
    fun hide_appliesExactlyWhatThePreviewStated_andClearsTheGoal() = runTest {
        val fixture = fixture()
        val effect = fixture.useCase.previewHide(listOf(TOOL))

        fixture.useCase.hide(effect.appIds)

        val profile = fixture.profileDao.get()!!
        assertEquals(effect.totalXpAfter, profile.totalXp)
        assertEquals(effect.levelAfter, profile.level)
        assertEquals(setOf(TOOL), fixture.hidden.hiddenAppIdSet())
        assertFalse("hiding clears the goal designation", fixture.gameDao.getById(TOOL)!!.isGoal)
    }

    @Test
    fun unhide_restoresXpAndLevel_butNotTheGoal() = runTest {
        val fixture = fixture()
        fixture.useCase.hide(listOf(TOOL))
        val whileHidden = fixture.profileDao.get()!!

        val effect = fixture.useCase.previewUnhide(listOf(TOOL))
        assertFalse(effect.hiding)
        assertEquals(whileHidden.totalXp, effect.totalXpBefore)
        assertTrue(effect.clearedGoalNames.isEmpty())

        fixture.useCase.unhide(listOf(TOOL))

        val restored = fixture.profileDao.get()!!
        assertEquals(effect.totalXpAfter, restored.totalXp)
        assertEquals(740, restored.totalXp)
        assertFalse("a cleared goal is never reinstated", fixture.gameDao.getById(TOOL)!!.isGoal)
    }

    @Test
    fun unhideAll_clearsTheWholeHiddenSet() = runTest {
        val fixture = fixture()
        fixture.useCase.hide(listOf(TOOL, UNPLAYED))
        assertEquals(2, fixture.hidden.hiddenAppIdSet().size)

        fixture.useCase.unhideAll()

        assertTrue(fixture.hidden.hiddenAppIdSet().isEmpty())
        assertEquals(740, fixture.profileDao.get()!!.totalXp)
    }

    // --- Fixture -------------------------------------------------------------

    private class Fixture(
        val useCase: GameVisibilityUseCase,
        val hidden: HiddenGamesRepository,
        val gameDao: FakeGameDao,
        val profileDao: FakePlayerProfileDao,
    )

    /**
     * 300 minutes on a kept game and 400 on a played "tool", both tagged as goals so a hide has a
     * goal designation to clear; plus a never-played game, for the no-effect case.
     */
    private fun fixture(): Fixture {
        val hiddenDao = FakeHiddenGameDao()
        val gameDao = FakeGameDao(
            listOf(
                goalGame(KEPT, "Kept Game"),
                goalGame(TOOL, "Wallpaper Engine"),
                Game(
                    appId = UNPLAYED,
                    name = "Never Played",
                    iconUrl = "",
                    playtimeForever = 0,
                    playtime2Weeks = 0,
                    lastPlaytime = 0,
                ),
            ),
        )
        val profileDao = FakePlayerProfileDao(PlayerProfile())
        val updater = GamificationUpdater(
            FakeSessionDao(
                listOf(
                    testSession(minutes = 300, appId = KEPT),
                    testSession(minutes = 400, appId = TOOL),
                ),
            ),
            FakeDailyProgressDao(listOf(DailyProgress("2026-07-17", minutesPlayed = 40, questMet = true))),
            profileDao,
            FakeHltbDataDao(),
            FakeAchievementDao(
                listOf(
                    Achievement(
                        appId = TOOL,
                        apiName = "TOOL_ACH",
                        unlocked = true,
                        snapshotPercent = 10.0,
                        fetchedAt = 0L,
                    ),
                ),
            ),
            gameDao,
            hiddenDao,
        )
        val hidden = HiddenGamesRepository(
            hiddenGameDao = hiddenDao,
            gameDao = gameDao,
            time = FixedTime,
        )
        return Fixture(
            useCase = GameVisibilityUseCase(
                hiddenGames = hidden,
                gamificationUpdater = updater,
                gameDao = gameDao,
                settings = FakeSettingsRepository(),
                time = FixedTime,
                derivedStateWrites = DerivedStateWriteCoordinator(),
            ),
            hidden = hidden,
            gameDao = gameDao,
            profileDao = profileDao,
        )
    }

    private fun goalGame(appId: Long, name: String) = Game(
        appId = appId,
        name = name,
        iconUrl = "",
        playtimeForever = 500,
        playtime2Weeks = 0,
        lastPlaytime = 500,
        isGoal = true,
    )

    private object FixedTime : TimeProvider {
        override fun nowMillis(): Long = 1_700_000_000_000L
        override fun zone(): ZoneId = ZoneId.of("UTC")
        override fun today(): LocalDate = LocalDate.parse("2026-07-17")
    }

    private companion object {
        const val KEPT = 1L
        const val TOOL = 2L
        const val UNPLAYED = 3L
    }
}
