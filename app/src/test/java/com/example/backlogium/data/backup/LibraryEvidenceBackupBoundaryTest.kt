package com.example.backlogium.data.backup

import androidx.room.Room
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.entity.LibraryPollEvidenceRecord
import com.example.backlogium.data.local.entity.LibraryPollOutcomeKind
import com.example.backlogium.data.local.entity.PlayerProfile
import com.example.backlogium.data.repo.LibraryPollRepository
import com.example.backlogium.data.repo.toBaselineEvidence
import com.example.backlogium.domain.GamificationUpdater
import com.example.backlogium.domain.LibraryBaselineReadiness
import com.example.backlogium.domain.TimeProvider
import com.example.backlogium.gamification.RuleConfig
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Task 2.5 backup boundary: an unverified backup restore must never grant baseline readiness, must
 * not copy local confirmation or transient work identities into — or out of — the local store, and
 * must preserve already-trusted local confirmation. The merge engine's raw transaction writes
 * games/sessions/import-flags and recomputes; baseline evidence is deliberately not part of the
 * backup format, so restoring uncertain rows or timestamps cannot manufacture readiness.
 */
@RunWith(RobolectricTestRunner::class)
class LibraryEvidenceBackupBoundaryTest {

    private lateinit var database: BacklogiumDatabase
    private lateinit var engine: BackupMergeEngine

    private val accountA = "76561198000000000"
    private val work = "00000000-0000-0000-0000-000000000001"
    private val confirmedAt = 1_700_000_000_000L
    private val pollMapper = LibraryPollRepository()

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, BacklogiumDatabase::class.java)
            .allowMainThreadQueries().build()
        val time = FixedTime()
        engine = BackupMergeEngine(
            gameDao = database.gameDao(),
            sessionDao = database.sessionDao(),
            dailyProgressDao = database.dailyProgressDao(),
            hltbDataDao = database.hltbDataDao(),
            achievementDao = database.achievementDao(),
            playerProfileDao = database.playerProfileDao(),
            collectionDao = database.collectionDao(),
            excludedSharedGameDao = database.excludedSharedGameDao(),
            hiddenGameDao = database.hiddenGameDao(),
            gamificationUpdater = GamificationUpdater(
                database.sessionDao(),
                database.dailyProgressDao(),
                database.playerProfileDao(),
                database.hltbDataDao(),
                database.achievementDao(),
                database.gameDao(),
                database.hiddenGameDao(),
            ),
            time = time,
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun backupFile(
        games: List<BackupGame> = emptyList(),
        playtimeBackfilled: Boolean = false,
        longestStreak: Int = 0,
    ) = BackupFile(
        exportedAt = "2026-09-30T00:00:00Z",
        identity = BackupIdentity(steamId64 = accountA),
        ruleConfig = RuleConfig().toBackupRuleConfig(),
        games = games,
        achievements = emptyList(),
        sessions = emptyList(),
        dailyProgress = emptyList(),
        hltbData = emptyList(),
        librarySortPrefs = BackupLibrarySortPrefs(focus = "NAME", library = "PLAYTIME"),
        playerProfile = BackupPlayerProfile(
            totalXp = 999_999L, // deliberately implausible: the merge engine never trusts it
            level = 99,
            currentStreak = 99,
            longestStreak = longestStreak,
            playtimeBackfilled = playtimeBackfilled,
        ),
        computed = BackupComputed(emptyList(), emptyList()),
    )

    private suspend fun confirmLocally() {
        database.playerProfileDao().insertIfMissing()
        database.playerProfileDao().updateLibraryConfirmation(steamId = accountA, confirmedAt = confirmedAt)
        database.libraryPollEvidenceDao().upsert(
            LibraryPollEvidenceRecord(
                workIdentity = work,
                accountSteamId = accountA,
                outcome = LibraryPollOutcomeKind.COMMITTED.name,
                gameCount = 1,
                lastSyncAt = confirmedAt,
                refusal = null,
                reason = null,
                recordedAt = confirmedAt,
            ),
        )
    }

    private fun readiness(profile: PlayerProfile?): LibraryBaselineReadiness =
        pollMapper.readinessFor(
            activeSteamId = accountA,
            pendingResetSteamId = null,
            evidence = profile?.toBaselineEvidence(),
        )

    @Test
    fun `restoring an unverified backup never grants readiness, even with imported rows`() = runBlocking {
        // Nothing touched the profile or evidence store; the restored file carries games and says
        // the account was imported.
        val file = backupFile(
            games = (440L..442L).map { appId ->
                BackupGame(appId = appId, name = "Game $appId", isGoal = false, backfillMinutes = 0)
            },
            playtimeBackfilled = true,
            longestStreak = 12,
        )

        engine.merge(file, RuleConfig())

        val profile = checkNotNull(database.playerProfileDao().get())
        assertTrue("the backup's own rows were genuinely merged", database.gameDao().count() == 3)
        assertTrue("the one-time import flag is honored from the file", profile.playtimeBackfilled)
        assertNull("restored rows must not create confirmation", profile.confirmedLibrarySteamId)
        assertNull(profile.confirmedLibraryAt)
        assertEquals(
            "uncertain restored rows and timestamps must stay unconfirmed",
            LibraryBaselineReadiness.Unknown,
            readiness(profile),
        )
        assertNull(
            "the restore wrote no attributable evidence rows into the local store",
            database.libraryPollEvidenceDao().getFor(work, accountA),
        )
    }

    @Test
    fun `restoring preserves already-trusted local confirmation and evidence`() = runBlocking {
        confirmLocally()
        val confirmedAtBefore = checkNotNull(database.playerProfileDao().get()).confirmedLibraryAt

        engine.merge(
            backupFile(games = listOf(BackupGame(appId = 440L, name = "Game", isGoal = false, backfillMinutes = 0))),
            RuleConfig(),
        )

        val profile = checkNotNull(database.playerProfileDao().get())
        assertEquals("local confirmation survives the merge untouched", accountA, profile.confirmedLibrarySteamId)
        assertEquals(confirmedAtBefore, profile.confirmedLibraryAt)
        assertEquals(
            "trusted local readiness is preserved across a restore",
            LibraryBaselineReadiness.Confirmed,
            readiness(profile),
        )
        assertEquals(
            "the restore neither copies nor clears local attributable evidence",
            LibraryPollOutcomeKind.COMMITTED.name,
            checkNotNull(database.libraryPollEvidenceDao().getFor(work, accountA)).outcome,
        )
    }

    @Test
    fun `restoring cannot refresh the confirmation timestamp from file aggregates`() = runBlocking {
        database.playerProfileDao().insertIfMissing()
        database.playerProfileDao().updateLibraryConfirmation(steamId = accountA, confirmedAt = confirmedAt)

        // The file carries an imported profile (longest streak 12, backfilled) with no baseline
        // evidence of its own: nothing in the backup format can carry a confirmation.
        engine.merge(backupFile(playtimeBackfilled = true, longestStreak = 12), RuleConfig())

        val profile = checkNotNull(database.playerProfileDao().get())
        assertTrue(profile.playtimeBackfilled)
        assertEquals("the profile aggregate fields may change, but not readiness evidence", confirmedAt, profile.confirmedLibraryAt)
        assertEquals(LibraryBaselineReadiness.Confirmed, readiness(profile))
        assertFalse("a ready account stays ready; nothing about the restore revokes it", profile.confirmedLibrarySteamId.isNullOrBlank())
    }

    private inner class FixedTime : TimeProvider {
        override fun nowMillis(): Long = confirmedAt
        override fun zone(): ZoneId = ZoneId.of("UTC")
        override fun today(): LocalDate = LocalDate.parse("2026-10-02")
    }
}

/** Mirrors the fields [BackupFile.ruleConfig] must name; only used to build a decodable file. */
private fun RuleConfig.toBackupRuleConfig() = BackupRuleConfig(
    xpPerMinute = xpPerMinute,
    levelBase = levelBase,
    questThresholdMin = questThresholdMin,
    questMode = questMode.name,
    streakGraceDays = streakGraceDays,
    commonAchievementXp = commonAchievementXp,
    uncommonAchievementXp = uncommonAchievementXp,
    rareAchievementXp = rareAchievementXp,
    epicAchievementXp = epicAchievementXp,
    legendaryAchievementXp = legendaryAchievementXp,
)
