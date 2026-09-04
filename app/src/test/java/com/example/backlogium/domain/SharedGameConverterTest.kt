package com.example.backlogium.domain

import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.remote.dto.OwnedGameDto
import com.example.backlogium.domain.SessionDiffer.GameDiffState
import com.example.backlogium.domain.SessionDiffer.PollGame
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Buying a borrowed game. The baseline is the whole risk here: `playtime_forever` for a newly
 * purchased game includes the hours played while borrowing, which the app has already recorded as
 * presence-derived sessions, so treating that total as a diff would synthesize one enormous session
 * over time already counted.
 */
class SharedGameConverterTest {

    private val differ = SessionDiffer()
    private val now = 5_000L

    private val shared = Game(
        appId = 620L,
        name = "Shared",
        iconUrl = "",
        playtimeForever = 0,
        playtime2Weeks = 0,
        lastPlaytime = 0,
        source = GameSource.FAMILY_SHARED,
    )

    private fun dto(appId: Long, forever: Int, weeks: Int = 0) =
        OwnedGameDto(appid = appId, name = "Shared", playtimeForever = forever, playtime2Weeks = weeks)

    @Test
    fun aPurchasedGame_becomesOwned() = runTest {
        val dao = FakeGameDao(listOf(shared))
        val converter = SharedGameConverter(dao)

        val converted = converter.convertNewlyOwned(listOf(dto(shared.appId, forever = 900)), now)

        assertEquals(listOf(shared.appId), converted)
        assertEquals(GameSource.STEAM_OWNED, dao.getById(shared.appId)?.source)
    }

    @Test
    fun conversion_storesTheReportedTotalAsTheBaselineAndCreatesNoSession() = runTest {
        val dao = FakeGameDao(listOf(shared))
        SharedGameConverter(dao).convertNewlyOwned(listOf(dto(shared.appId, forever = 900)), now)

        val row = requireNonNull(dao.getById(shared.appId))
        assertEquals(900, row.playtimeForever)
        assertEquals(900, row.lastPlaytime)

        // The poll that converts then diffs against that baseline and sees no delta, so no session
        // is synthesized from 900 minutes the app already has sessions for.
        val diffable = dao.ownedGamesForDiffing()
        val diff = differ.diff(
            polls = diffable.map { PollGame(it.appId, it.playtimeForever) },
            priorStates = diffable.associate { it.appId to GameDiffState(it.lastPlaytime) },
            now = now + 1_000L,
            previousPollAt = now,
        )

        assertTrue(diff.actions.none { it.addedMinutes > 0 })
    }

    @Test
    fun diffingResumesNormally_onTheNextIncrease() = runTest {
        val dao = FakeGameDao(listOf(shared))
        SharedGameConverter(dao).convertNewlyOwned(listOf(dto(shared.appId, forever = 900)), now)

        val diffable = dao.ownedGamesForDiffing()
        val diff = differ.diff(
            polls = listOf(PollGame(shared.appId, playtimeForever = 930)),
            priorStates = diffable.associate { it.appId to GameDiffState(it.lastPlaytime) },
            now = now + 1_000L,
            previousPollAt = now,
        )

        assertEquals(30, diff.playedDeltaByAppId[shared.appId])
    }

    @Test
    fun conversion_foldsAManualSharedEstimateIntoBackfill() = runTest {
        val estimated = shared.copy(manualSharedMinutes = 600)
        val dao = FakeGameDao(listOf(estimated))
        SharedGameConverter(dao).convertNewlyOwned(listOf(dto(shared.appId, forever = 900)), now)

        val row = requireNonNull(dao.getById(shared.appId))
        assertEquals(GameSource.STEAM_OWNED, row.source)
        // The estimate stays 0 for an owned game and its editor is no longer offered, but the
        // credited minutes must survive as backfill: XP is computed from backfill + manual +
        // tracked and never from playtimeForever, so clearing without folding would erase credit
        // no later import could restore on an already-imported profile. Owned display still reads
        // Steam's total, which already includes the borrowed hours, so there is no double count.
        assertEquals(0, row.manualSharedMinutes)
        assertEquals(600, row.backfillMinutes)
    }

    @Test
    fun conversion_preservesXpCreditAcrossOwnershipChange() = runTest {
        // 60 tracked + 600 manual credits 660 minutes before purchase; the next recompute after
        // conversion must see the same 660 (now as backfill + tracked), not just the 60 tracked.
        val estimated = shared.copy(manualSharedMinutes = 600)
        val sessionDao = FakeSessionDao(listOf(testSession(minutes = 60, appId = shared.appId)))
        val dao = FakeGameDao(listOf(estimated))
        val profileDao = FakePlayerProfileDao()
        val updater = GamificationUpdater(
            sessionDao = sessionDao,
            dailyProgressDao = FakeDailyProgressDao(emptyList()),
            playerProfileDao = profileDao,
            hltbDataDao = FakeHltbDataDao(),
            achievementDao = FakeAchievementDao(emptyList()),
            gameDao = dao,
        )
        val today = java.time.LocalDate.of(2026, 8, 24)
        updater.recompute(today = today, source = RecomputeSource.SYNC)
        val xpBefore = requireNonNull(profileDao.get()).totalXp

        SharedGameConverter(dao).convertNewlyOwned(listOf(dto(shared.appId, forever = 900)), now)
        updater.recompute(today = today, source = RecomputeSource.SYNC)
        val xpAfter = requireNonNull(profileDao.get()).totalXp

        assertEquals(xpBefore, xpAfter)
        val row = requireNonNull(dao.getById(shared.appId))
        assertEquals(0, row.manualSharedMinutes)
        assertEquals(600, row.backfillMinutes)
    }

    @Test
    fun conversion_preservesXpCreditWhenTheProfileAlreadyImportedHistory() = runTest {
        // The sticky case: this row had backfillMinutes = 0 while shared and the profile flag is
        // already set, so PlaytimeBackfillUseCase will no-op — the conversion itself must carry
        // the credit; nothing later will.
        val estimated = shared.copy(manualSharedMinutes = 600, backfillMinutes = 0)
        val sessionDao = FakeSessionDao(listOf(testSession(minutes = 60, appId = shared.appId)))
        val dao = FakeGameDao(listOf(estimated))
        val profileDao = FakePlayerProfileDao(
            com.example.backlogium.data.local.entity.PlayerProfile(playtimeBackfilled = true),
        )
        val updater = GamificationUpdater(
            sessionDao = sessionDao,
            dailyProgressDao = FakeDailyProgressDao(emptyList()),
            playerProfileDao = profileDao,
            hltbDataDao = FakeHltbDataDao(),
            achievementDao = FakeAchievementDao(emptyList()),
            gameDao = dao,
        )
        val today = java.time.LocalDate.of(2026, 8, 24)
        updater.recompute(today = today, source = RecomputeSource.SYNC)
        val xpBefore = requireNonNull(profileDao.get()).totalXp

        SharedGameConverter(dao).convertNewlyOwned(listOf(dto(shared.appId, forever = 900)), now)
        updater.recompute(today = today, source = RecomputeSource.SYNC)

        assertEquals(xpBefore, requireNonNull(profileDao.get()).totalXp)
        assertEquals(600, requireNonNull(dao.getById(shared.appId)).backfillMinutes)
    }

    @Test
    fun anUnrelatedOwnedGame_isNotTouched() = runTest {
        val dao = FakeGameDao(listOf(shared))

        val converted = SharedGameConverter(dao).convertNewlyOwned(listOf(dto(999L, forever = 10)), now)

        assertTrue(converted.isEmpty())
        assertEquals(GameSource.FAMILY_SHARED, dao.getById(shared.appId)?.source)
    }

    @Test
    fun convertingTwice_isANoOpTheSecondTime() = runTest {
        val dao = FakeGameDao(listOf(shared))
        val converter = SharedGameConverter(dao)
        converter.convertNewlyOwned(listOf(dto(shared.appId, forever = 900)), now)

        // A later poll reporting a higher total must not re-baseline an already-owned game, which
        // would silently discard the increase the diff is there to turn into a session.
        val again = converter.convertNewlyOwned(listOf(dto(shared.appId, forever = 930)), now + 1)

        assertTrue(again.isEmpty())
        assertEquals(900, dao.getById(shared.appId)?.lastPlaytime)
    }

    @Test
    fun conversion_retainsHistoryAndOnlyClosesAnOpenSession() = runTest {
        val dao = FakeGameDao(listOf(shared))
        SharedGameConverter(dao).convertNewlyOwned(listOf(dto(shared.appId, forever = 900)), now)

        // Conversion is an UPDATE on `games` alone -- it never deletes the row, so nothing cascades
        // to `sessions`. What the next diff does to a session left open by the presence deriver is
        // close it at its last observation, which is the correct end for a session the deriver will
        // no longer extend. It is closed, never discarded.
        val diffable = dao.ownedGamesForDiffing()
        val diff = differ.diff(
            polls = diffable.map { PollGame(it.appId, it.playtimeForever) },
            priorStates = mapOf(
                shared.appId to GameDiffState(
                    lastPlaytime = 900,
                    openSession = SessionDiffer.OpenSession(
                        startAt = 1_000L,
                        minutes = 12,
                        lastIncreaseAt = 2_000L,
                    ),
                ),
            ),
            now = now + 1_000L,
            previousPollAt = now,
        )

        val closed = diff.actions.single() as SessionDiffer.SessionAction.Close
        assertEquals(1_000L, closed.startAt)
        assertEquals(2_000L, closed.endAt)
    }

    @Test
    fun noSharedGames_shortCircuits() = runTest {
        val dao = FakeGameDao(emptyList())

        assertTrue(SharedGameConverter(dao).convertNewlyOwned(listOf(dto(1L, forever = 5)), now).isEmpty())
    }

    private fun <T> requireNonNull(value: T?): T = checkNotNull(value)
}
