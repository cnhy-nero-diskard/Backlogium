package com.example.backlogium.domain

import com.example.backlogium.data.local.entity.PlayerProfile
import com.example.backlogium.gamification.RuleConfig
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Marker-clearing derived writers (stabilize-first-run-setup, task 5.6): whichever recompute runs
 * while a raw import/merge's pending recompute marker is set is the one that clears it, so it must
 * present the transitions administratively (BACKFILL for an explicit import, RESTORE for a legacy
 * backup marker) regardless of its declared SYNC/RULE_CHANGE source — and once the marker is gone,
 * a SYNC recompute is earned again.
 */
class GamificationUpdaterPendingRecomputeTest {

    private val today: LocalDate = LocalDate.of(2026, 10, 2)

    private fun importedGame(minutes: Int) = com.example.backlogium.data.local.entity.Game(
        appId = 1L,
        name = "Game",
        iconUrl = "",
        playtimeForever = 0,
        playtime2Weeks = 0,
        lastPlaytime = 0,
        backfillMinutes = minutes,
    )

    private fun updaterWith(
        sessions: List<com.example.backlogium.data.local.entity.Session>,
        profile: PlayerProfile,
    ): Triple<GamificationUpdater, FakePlayerProfileDao, InMemoryProgressMarksStore> {
        val profileDao = FakePlayerProfileDao(profile)
        val marks = InMemoryProgressMarksStore(
            ProgressMarks(
                lastCelebratedLevel = 1,
                lastCelebratedStreakMilestone = 0,
                initialized = true,
            ),
        )
        val updater = GamificationUpdater(
            sessionDao = FakeSessionDao(sessions),
            dailyProgressDao = FakeDailyProgressDao(emptyList()),
            playerProfileDao = profileDao,
            hltbDataDao = FakeHltbDataDao(),
            achievementDao = FakeAchievementDao(emptyList()),
            gameDao = FakeGameDao(listOf(importedGame(300))),
            hiddenGameDao = FakeHiddenGameDao(),
            progressMarksStore = marks,
        )
        return Triple(updater, profileDao, marks)
    }

    @Test
    fun syncRecomputeWhileBackfillPendingIsAdministrativeAndSilent() = runTest {
        val (updater, profileDao, marks) = updaterWith(
            sessions = listOf(testSession(minutes = 0)),
            profile = PlayerProfile(
                steamId = "s",
                pendingImportRecompute = true,
                pendingImportRecomputeSource = RecomputeSource.BACKFILL.name,
                pendingImportRecomputeSteamId = "s",
                pendingImportRecomputeRequestId = "req-1",
            ),
        )

        // A periodic sync arrives while the import's administrative recomputation is pending.
        updater.recompute(today = today, source = RecomputeSource.SYNC)

        val profile = profileDao.get()!!
        // The marker is cleared only because the sync resolved the committed raw state with
        // administrative semantics — imported minutes are recounted, never announced as earned.
        assertFalse(profile.pendingImportRecompute)
        assertTrue(profile.pendingImportRecomputeSource == null)
        assertEquals(300L, profile.totalXp)
        // Reseeded baseline follows the written level (3), no earned quest/level delivery pending.
        assertEquals(3, profile.level)
        assertEquals(3, marks.read().lastCelebratedLevel)
        assertTrue(marks.read().pendingQuestDates.isEmpty())
    }

    @Test
    fun legacyNullProvenancePendingResolvesAsRestoreSilently() = runTest {
        val (updater, profileDao, marks) = updaterWith(
            sessions = listOf(testSession(minutes = 0)),
            profile = PlayerProfile(
                steamId = "s",
                pendingImportRecompute = true,
                pendingImportRecomputeSource = null,
                pendingImportRecomputeSteamId = null,
            ),
        )

        updater.recompute(today = today, source = RecomputeSource.SYNC)

        val profile = profileDao.get()!!
        assertFalse(profile.pendingImportRecompute)
        assertEquals(300L, profile.totalXp)
        assertEquals(3, profile.level)
        // RESTORE semantics: baseline reseeded, not delivered as earned.
        assertEquals(3, marks.read().lastCelebratedLevel)
        assertTrue(marks.read().pendingQuestDates.isEmpty())
    }

    @Test
    fun xpIntegrityCorrectionStillTakesPrecedenceOverPendingImport() = runTest {
        val (updater, profileDao, marks) = updaterWith(
            sessions = listOf(testSession(minutes = 0)),
            profile = PlayerProfile(
                steamId = "s",
                pendingImportRecompute = true,
                pendingImportRecomputeSource = RecomputeSource.BACKFILL.name,
                pendingImportRecomputeSteamId = "s",
                pendingXpIntegrityCorrection = true,
            ),
        )

        updater.recompute(today = today, source = RecomputeSource.SYNC)

        val profile = profileDao.get()!!
        assertFalse(profile.pendingImportRecompute)
        assertFalse(profile.pendingXpIntegrityCorrection)
        // Both non-earned; the correction marker wins and both clear atomically.
        assertEquals(3, marks.read().lastCelebratedLevel)
    }

    @Test
    fun staleResultComputedBeforeRawCommitIsRefusedWhileMarkerPending() = runTest {
        // The parent-review scenario: a caller computes a candidate (pre-import raw state), the
        // raw import commits with its recomputation pending, and the caller then persists its
        // stale result. Without a refresh config the write must be refused outright — stale XP is
        // never written and the marker (and its provenance) is never cleared by that write.
        val profileDao = FakePlayerProfileDao(
            PlayerProfile(
                steamId = "s",
                pendingImportRecompute = true,
                pendingImportRecomputeSource = RecomputeSource.BACKFILL.name,
                pendingImportRecomputeSteamId = "s",
                pendingImportRecomputeRequestId = "req-1",
            ),
        )
        val marks = InMemoryProgressMarksStore(
            ProgressMarks(lastCelebratedLevel = 1, initialized = true),
        )
        val gameDao = FakeGameDao(emptyList())
        val updater = GamificationUpdater(
            sessionDao = FakeSessionDao(emptyList()),
            dailyProgressDao = FakeDailyProgressDao(emptyList()),
            playerProfileDao = profileDao,
            hltbDataDao = FakeHltbDataDao(),
            achievementDao = FakeAchievementDao(emptyList()),
            gameDao = gameDao,
            hiddenGameDao = FakeHiddenGameDao(),
            progressMarksStore = marks,
        )

        // Candidate computed BEFORE the raw import exists (0 minutes of raw playtime).
        val stale = updater.compute(today, RuleConfig())
        assertEquals(0L, stale.xpState.totalXp)

        // The raw import commits: 300 imported minutes now exist as committed raw state.
        gameDao.upsert(importedGame(300))

        // Persisting the stale candidate with no refresh config is refused, not written.
        updater.persist(stale, RecomputeSource.SYNC, configVersion = 0L, refreshConfig = null)

        val profile = profileDao.get()!!
        assertTrue("stale totalXp must not be written", profile.totalXp < 300L)
        assertTrue("marker must survive the refused stale write", profile.pendingImportRecompute)
        assertEquals(RecomputeSource.BACKFILL.name, profile.pendingImportRecomputeSource)

        // With the caller's own rules as the refresh config, the write recomputes fresh from the
        // current committed raw (300 minutes) under administrative semantics and clears the marker.
        updater.persist(stale, RecomputeSource.SYNC, configVersion = 0L, refreshConfig = RuleConfig())

        val after = profileDao.get()!!
        assertEquals(300L, after.totalXp)
        assertFalse(after.pendingImportRecompute)
    }

    @Test
    fun syncAfterTheMarkerIsClearedIsEarnedAgain() = runTest {
        val profileDao = FakePlayerProfileDao(
            PlayerProfile(
                steamId = "s",
                pendingImportRecompute = true,
                pendingImportRecomputeSource = RecomputeSource.BACKFILL.name,
                pendingImportRecomputeSteamId = "s",
            ),
        )
        val marks = InMemoryProgressMarksStore(
            ProgressMarks(
                lastCelebratedLevel = 1,
                lastCelebratedStreakMilestone = 0,
                initialized = true,
            ),
        )
        val sessions = mutableListOf<com.example.backlogium.data.local.entity.Session>()
        val updater = GamificationUpdater(
            sessionDao = FakeSessionDao(sessions),
            dailyProgressDao = FakeDailyProgressDao(emptyList()),
            playerProfileDao = profileDao,
            hltbDataDao = FakeHltbDataDao(),
            achievementDao = FakeAchievementDao(emptyList()),
            gameDao = FakeGameDao(
                listOf(
                    com.example.backlogium.data.local.entity.Game(
                        appId = 1L,
                        name = "Game",
                        iconUrl = "",
                        playtimeForever = 0,
                        playtime2Weeks = 0,
                        lastPlaytime = 0,
                        backfillMinutes = 300,
                    ),
                ),
            ),
            hiddenGameDao = FakeHiddenGameDao(),
            progressMarksStore = marks,
        )

        // First: a rule change resolves the pending admin work (silent reseed to level 3).
        updater.recompute(today = today, source = RecomputeSource.RULE_CHANGE)
        assertEquals(3, profileDao.get()!!.level)
        assertEquals(3, marks.read().lastCelebratedLevel)

        // Now the marker is gone and the player accrues tracked play: a later sync is earned.
        sessions += testSession(minutes = 600)
        updater.recompute(today = today, source = RecomputeSource.SYNC)

        val profile = profileDao.get()!!
        // XP = frozen imported 300 + newly tracked 600 = 900 (the import contract: new play
        // accrues on top of the one-time frozen offset, never re-imported, never lower).
        assertEquals(900L, profile.totalXp)
        assertEquals(4, profile.level)
        // A genuinely earned level-up is NOT reseeded: the baseline stays at the previously
        // delivered level and awaits acknowledgement.
        assertEquals(3, marks.read().lastCelebratedLevel)
    }
}