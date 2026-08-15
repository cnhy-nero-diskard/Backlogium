package com.example.backlogium.data.backup

import androidx.room.Room
import androidx.room.withTransaction
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.entity.Collection
import com.example.backlogium.data.local.entity.CollectionMember
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.PlayerProfile
import com.example.backlogium.data.local.entity.Session
import com.example.backlogium.domain.DerivedStateWriteCoordinator
import com.example.backlogium.domain.GamificationUpdater
import com.example.backlogium.domain.RecomputeSource
import com.example.backlogium.domain.TimeProvider
import com.example.backlogium.gamification.RuleConfig
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Real-database coverage for the merge's atomicity, the merge/recompute interruption gap, and the
 * export's snapshot consistency (tasks.md 3.8-3.10, 5.3) — deliberately database tests rather than
 * fake-DAO tests, mirroring [com.example.backlogium.data.local.WriteIntegrityDaoTest], since the
 * guarantee under test is Room's own commit/rollback and transaction-isolation behavior.
 */
@RunWith(RobolectricTestRunner::class)
class BackupTransactionalIntegrityTest {

    private lateinit var database: BacklogiumDatabase
    private lateinit var transaction: DatabaseTransactionScope
    private lateinit var engine: BackupMergeEngine

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            BacklogiumDatabase::class.java,
        ).allowMainThreadQueries().build()
        transaction = RoomDatabaseTransactionScope(database)
        val gamificationUpdater = GamificationUpdater(
            database.sessionDao(), database.dailyProgressDao(), database.playerProfileDao(),
            database.hltbDataDao(), database.achievementDao(), database.gameDao(),
        )
        engine = BackupMergeEngine(
            gameDao = database.gameDao(),
            sessionDao = database.sessionDao(),
            dailyProgressDao = database.dailyProgressDao(),
            hltbDataDao = database.hltbDataDao(),
            achievementDao = database.achievementDao(),
            playerProfileDao = database.playerProfileDao(),
            collectionDao = database.collectionDao(),
            gamificationUpdater = gamificationUpdater,
            time = FixedTimeProvider(),
            derivedStateWrites = DerivedStateWriteCoordinator(),
            transaction = transaction,
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun failureMidwayThroughMerge_leavesDatabaseExactlyAsBefore() = runBlocking {
        // Pre-existing state the merge must leave untouched.
        database.gameDao().upsert(existingGame(appId = 50L, name = "Existing"))
        database.sessionDao().insert(Session(appId = 50L, startAt = 1_000L, endAt = 2_000L, minutes = 10, open = false))

        // A collectionMember referencing a collection that exists nowhere violates the FK — the
        // merge processes games/sessions/collections before collectionMembers, so this throws
        // after real writes have already happened inside the same transaction.
        val file = backupFile(
            games = listOf(BackupGame(appId = 7L, name = "New", isGoal = false, backfillMinutes = 0)),
            sessions = listOf(BackupSession(appId = 7L, startAt = 9_000L.toIso8601(), endAt = 9_500L.toIso8601(), minutes = 5)),
            collectionMembers = listOf(BackupCollectionMember(collectionId = 999L, appId = 7L, orderIndex = 0)),
        )

        val failure = runCatching { engine.merge(file, RuleConfig()) }
        assertTrue("expected the FK violation to propagate", failure.isFailure)

        // Nothing from the failed merge landed, and the pre-existing row is untouched.
        assertEquals(null, database.gameDao().getById(7L))
        assertEquals(0, database.sessionDao().getAll().count { it.appId == 7L })
        val survivor = database.gameDao().getById(50L)!!
        assertEquals("Existing", survivor.name)
        assertEquals(1, database.sessionDao().getAll().count { it.appId == 50L })
    }

    @Test
    fun cancellingMidMerge_leavesNoPartialResult() = runBlocking {
        database.gameDao().upsert(existingGame(appId = 1L, name = "Game"))
        // Enough rows that a cancellation fired shortly after launch has a real chance of landing
        // inside the transaction rather than before or after it entirely.
        val sessions = (0 until 500).map { i ->
            BackupSession(appId = 1L, startAt = (i * 10_000L).toIso8601(), endAt = (i * 10_000L + 500L).toIso8601(), minutes = 1)
        }
        val file = backupFile(sessions = sessions)

        val job = launch(Dispatchers.Default) {
            engine.merge(file, RuleConfig())
        }
        delay(1)
        job.cancel()
        job.join()

        // Either the whole merge committed before cancellation landed, or none of it did — never
        // a partial slice of the 500 sessions.
        val count = database.sessionDao().getAll().size
        assertTrue("expected 0 or 500, got $count", count == 0 || count == 500)
    }

    @Test
    fun interruptionBetweenMergeCommitAndRecompute_isDetectedAndResolvedOnNextAttempt() = runBlocking {
        // Simulates a process death after the merge's transaction commits but before the
        // follow-up recompute runs: write raw data and mark the flag, exactly as the merge's
        // transaction does, but never call the recompute that would normally follow immediately.
        transaction.run {
            database.gameDao().upsert(existingGame(appId = 1L, name = "Game"))
            database.sessionDao().insert(Session(appId = 1L, startAt = 0L, endAt = 60_000L, minutes = 60, open = false))
            database.playerProfileDao().insertIfMissing()
            database.playerProfileDao().markPendingImportRecompute()
        }

        val profileBeforeRecovery = database.playerProfileDao().get()!!
        assertTrue(profileBeforeRecovery.pendingImportRecompute)
        assertEquals(0, profileBeforeRecovery.totalXp)

        // "The next attempt": PendingImportRecomputeUseCase's own logic, inlined here since it
        // also needs SettingsDataStore/scope wiring this test does not otherwise require.
        val gamificationUpdater = GamificationUpdater(
            database.sessionDao(), database.dailyProgressDao(), database.playerProfileDao(),
            database.hltbDataDao(), database.achievementDao(), database.gameDao(),
        )
        gamificationUpdater.recompute(LocalDate.parse("2026-07-17"), RecomputeSource.RESTORE, RuleConfig())

        val resolved = database.playerProfileDao().get()!!
        assertFalse(resolved.pendingImportRecompute)
        assertTrue("expected aggregates recomputed from the merged session", resolved.totalXp > 0)
    }

    @Test
    fun exportSnapshot_concurrentSyncCommit_readsRemainMutuallyConsistent() = runBlocking {
        database.gameDao().upsert(existingGame(appId = 1L, name = "Before"))
        database.sessionDao().insert(Session(appId = 1L, startAt = 1_000L, endAt = 2_000L, minutes = 10, open = false))

        lateinit var gamesSeen: List<Game>
        lateinit var sessionsSeen: List<Session>

        // Two independent transactions launched under one scope with no explicit dispatcher and
        // no cross-coroutine signaling between them — mirroring
        // WriteIntegrityDaoTest.twoConcurrentCommitsReReadBaselineAndCreditOneSession. Letting
        // Room's own connection serialization decide the interleaving (rather than forcing one
        // via an await held inside a transaction) is what keeps this from deadlocking: a reader
        // that suspends *inside its own transaction* waiting on the writer's completion signal
        // would hold the in-memory database's one connection while the writer blocks forever
        // trying to acquire that same connection to even begin.
        coroutineScope {
            launch {
                database.withTransaction {
                    database.gameDao().upsert(existingGame(appId = 2L, name = "After"))
                    database.sessionDao().insert(Session(appId = 2L, startAt = 5_000L, endAt = 6_000L, minutes = 20, open = false))
                }
            }
            launch {
                transaction.run {
                    gamesSeen = database.gameDao().getAll()
                    sessionsSeen = database.sessionDao().getAll()
                }
            }
        }

        val sawNewGame = gamesSeen.any { it.appId == 2L }
        val sawNewSession = sessionsSeen.any { it.appId == 2L }
        // The property that matters (tasks.md 5.3), regardless of which transaction the scheduler
        // happened to run first: games and sessions must agree with each other, never a hybrid of
        // one table's before-state with another's after-state.
        assertEquals(sawNewGame, sawNewSession)
    }

    private fun existingGame(appId: Long, name: String) = Game(
        appId = appId, name = name, iconUrl = "", playtimeForever = 0, playtime2Weeks = 0, lastPlaytime = 0,
    )

    private fun backupFile(
        games: List<BackupGame> = emptyList(),
        sessions: List<BackupSession> = emptyList(),
        collections: List<BackupCollection> = emptyList(),
        collectionMembers: List<BackupCollectionMember> = emptyList(),
    ) = BackupFile(
        exportedAt = "2026-07-01T00:00:00Z",
        identity = BackupIdentity(steamId64 = "1"),
        ruleConfig = BackupRuleConfig(
            xpPerMinute = 1, levelBase = 100, questThresholdMin = 30, questMode = "ANY_GAME",
            streakGraceDays = 0, commonAchievementXp = 5, uncommonAchievementXp = 10,
            rareAchievementXp = 20, epicAchievementXp = 40, legendaryAchievementXp = 80,
        ),
        games = games,
        achievements = emptyList(),
        sessions = sessions,
        dailyProgress = emptyList(),
        hltbData = emptyList(),
        librarySortPrefs = BackupLibrarySortPrefs(focus = "NAME", library = "PLAYTIME"),
        playerProfile = BackupPlayerProfile(totalXp = 0, level = 1, currentStreak = 0, longestStreak = 0, playtimeBackfilled = false),
        computed = BackupComputed(emptyList(), emptyList()),
        collections = collections,
        collectionMembers = collectionMembers,
    )

    private class FixedTimeProvider : TimeProvider {
        override fun nowMillis(): Long = 0L
        override fun zone(): ZoneId = ZoneId.of("UTC")
        override fun today(): LocalDate = LocalDate.parse("2026-07-17")
    }
}
