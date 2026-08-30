package com.example.backlogium.data.local

import androidx.room.Room
import androidx.room.withTransaction
import com.example.backlogium.data.local.entity.Achievement
import com.example.backlogium.data.local.entity.CollectionMember
import com.example.backlogium.data.local.entity.DailyProgress
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.GameAchievementSync
import com.example.backlogium.data.local.entity.GameGenreCache
import com.example.backlogium.data.local.entity.HltbData
import com.example.backlogium.data.local.entity.HltbMatchStatus
import com.example.backlogium.data.local.entity.PlayerProfile
import com.example.backlogium.data.local.entity.PresenceDecision
import com.example.backlogium.data.local.entity.RequestBreakdown
import com.example.backlogium.data.local.entity.Session
import com.example.backlogium.data.local.entity.SyncRun
import com.example.backlogium.data.local.entity.WishlistItem
import com.example.backlogium.data.local.entity.WishlistPriceObservation
import com.example.backlogium.data.local.entity.Collection as CollectionEntity
import com.example.backlogium.data.repo.AccountRoomReset
import com.example.backlogium.domain.SessionDiffer
import com.example.backlogium.domain.CollectionMode
import com.example.backlogium.domain.CollectionSort
import com.example.backlogium.domain.CollectionTimeBasis
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
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
 * Exercises the real Room write surfaces introduced by auditfix-sync-write-integrity. These are
 * deliberately database tests rather than fake-DAO tests: the guarantees here are SQL column
 * scope, additive updates, and rollback when a raw commit fails halfway through.
 */
@RunWith(RobolectricTestRunner::class)
class WriteIntegrityDaoTest {

    private lateinit var database: BacklogiumDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            BacklogiumDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun steamInsertStampsArrivalAndALaterPollNeverOverwritesIt() = runBlocking {
        database.gameDao().insertSteamGameIfMissing(
            appId = 440L,
            name = "Game",
            iconUrl = "icon",
            playtimeForever = 0,
            playtime2Weeks = 0,
            lastPlaytime = 0,
            lastSyncedAt = 1L,
            firstSeenAt = 1_000L,
            lastPlayedAt = null,
        )

        // A second poll runs the same insert-then-update pair. `INSERT OR IGNORE` cannot reach the
        // existing row, and the update deliberately has no `firstSeenAt` column in its SET list —
        // between them, "written once" is a property of the SQL rather than of the caller.
        database.gameDao().insertSteamGameIfMissing(
            appId = 440L,
            name = "Game",
            iconUrl = "icon",
            playtimeForever = 60,
            playtime2Weeks = 60,
            lastPlaytime = 0,
            lastSyncedAt = 2L,
            firstSeenAt = 9_000L,
            lastPlayedAt = 8_000L,
        )
        database.gameDao().updateSteamFields(
            appId = 440L,
            name = "Game",
            iconUrl = "icon",
            playtimeForever = 60,
            playtime2Weeks = 60,
            lastPlaytime = 60,
            lastSyncedAt = 2L,
            lastPlayedAt = 8_000L,
            returnedToPlayAt = null,
        )

        val stored = database.gameDao().getById(440L)!!
        assertEquals(1_000L, stored.firstSeenAt)
        assertEquals(8_000L, stored.lastPlayedAt)
        assertNull(stored.returnedToPlayAt)
    }

    @Test
    fun steamUpdateRecordsAReturnAndNeverErasesOne() = runBlocking {
        database.gameDao().upsert(
            Game(
                appId = 440L,
                name = "Game",
                iconUrl = "",
                playtimeForever = 100,
                playtime2Weeks = 0,
                lastPlaytime = 100,
            ),
        )

        database.gameDao().updateSteamFields(
            appId = 440L,
            name = "Game",
            iconUrl = "",
            playtimeForever = 160,
            playtime2Weeks = 60,
            lastPlaytime = 160,
            lastSyncedAt = 2L,
            lastPlayedAt = 5_000L,
            returnedToPlayAt = 5_000L,
        )
        assertEquals(5_000L, database.gameDao().getById(440L)!!.returnedToPlayAt)

        // A later poll with no return to record must leave the stored one alone: this is the
        // COALESCE, and without it every subsequent poll would silently retire the badge.
        database.gameDao().updateSteamFields(
            appId = 440L,
            name = "Game",
            iconUrl = "",
            playtimeForever = 200,
            playtime2Weeks = 100,
            lastPlaytime = 200,
            lastSyncedAt = 3L,
            lastPlayedAt = 6_000L,
            returnedToPlayAt = null,
        )
        val stored = database.gameDao().getById(440L)!!
        assertEquals(5_000L, stored.returnedToPlayAt)
        assertEquals(6_000L, stored.lastPlayedAt)
    }

    @Test
    fun recencyOnlyUpdateLeavesSteamAndUserOwnedFieldsUntouched() = runBlocking {
        database.gameDao().upsert(
            Game(
                appId = 440L,
                name = "Game",
                iconUrl = "icon",
                playtimeForever = 100,
                playtime2Weeks = 20,
                lastPlaytime = 100,
                isGoal = true,
                targetMinutes = 60,
                lastSyncedAt = 1L,
                firstSeenAt = 1_000L,
                lastPlayedAt = 2_000L,
                returnedToPlayAt = 3_000L,
            ),
        )

        database.gameDao().updateRecencyFields(
            appId = 440L,
            firstSeenAt = 9_000L,
            lastPlayedAt = 8_000L,
            returnedToPlayAt = null,
        )

        val stored = database.gameDao().getById(440L)!!
        assertEquals(100, stored.playtimeForever)
        assertEquals(20, stored.playtime2Weeks)
        assertEquals(100, stored.lastPlaytime)
        assertEquals("Game", stored.name)
        assertTrue(stored.isGoal)
        assertEquals(60, stored.targetMinutes)
        assertEquals(1_000L, stored.firstSeenAt)
        assertEquals(8_000L, stored.lastPlayedAt)
        assertEquals(3_000L, stored.returnedToPlayAt)
    }

    @Test
    fun steamUpdateClearsLastPlayedWhenSteamStopsReportingIt() = runBlocking {
        database.gameDao().upsert(
            Game(
                appId = 440L,
                name = "Game",
                iconUrl = "",
                playtimeForever = 100,
                playtime2Weeks = 0,
                lastPlaytime = 100,
                lastPlayedAt = 5_000L,
            ),
        )

        // `lastPlayedAt` is Steam-owned, so it mirrors the source including its absence — the
        // detail row then reads "unknown", which is the honest answer.
        database.gameDao().updateSteamFields(
            appId = 440L,
            name = "Game",
            iconUrl = "",
            playtimeForever = 100,
            playtime2Weeks = 0,
            lastPlaytime = 100,
            lastSyncedAt = 3L,
            lastPlayedAt = null,
            returnedToPlayAt = null,
        )
        assertNull(database.gameDao().getById(440L)!!.lastPlayedAt)
    }

    @Test
    fun steamUpdatePreservesGoalTargetAndBackfillColumns() = runBlocking {
        database.gameDao().upsert(
            Game(
                appId = 440L,
                name = "Old",
                iconUrl = "old-icon",
                playtimeForever = 100,
                playtime2Weeks = 10,
                lastPlaytime = 100,
                isGoal = true,
                targetMinutes = 240,
                backfillMinutes = 55,
            ),
        )

        database.gameDao().insertSteamGameIfMissing(
            appId = 440L,
            name = "Ignored",
            iconUrl = "ignored",
            playtimeForever = 1,
            playtime2Weeks = 1,
            lastPlaytime = 1,
            lastSyncedAt = 1L,
            firstSeenAt = null,
            lastPlayedAt = null,
        )
        database.gameDao().updateSteamFields(
            appId = 440L,
            name = "New",
            iconUrl = "new-icon",
            playtimeForever = 130,
            playtime2Weeks = 20,
            lastPlaytime = 130,
            lastSyncedAt = 2L,
            lastPlayedAt = null,
            returnedToPlayAt = null,
        )

        val stored = database.gameDao().getById(440L)!!
        assertEquals("New", stored.name)
        assertEquals(130, stored.lastPlaytime)
        assertTrue(stored.isGoal)
        assertEquals(240, stored.targetMinutes)
        assertEquals(55, stored.backfillMinutes)
    }

    @Test
    fun additiveDailyUpdatesKeepBothCredits() = runBlocking {
        database.dailyProgressDao().ensureDate(DATE)
        database.dailyProgressDao().addMinutes(DATE, minutesPlayed = 12, goalMinutesPlayed = 5)
        database.dailyProgressDao().addMinutes(DATE, minutesPlayed = 8, goalMinutesPlayed = 3)

        val stored = database.dailyProgressDao().getByDate(DATE)!!
        assertEquals(20, stored.minutesPlayed)
        assertEquals(8, stored.goalMinutesPlayed)
        assertFalse(stored.questMet)
    }

    @Test
    fun retiredAchievementsStayStoredButLeaveDerivedReads() = runBlocking {
        database.gameDao().upsert(
            Game(
                appId = 440L,
                name = "Game",
                iconUrl = "",
                playtimeForever = 0,
                playtime2Weeks = 0,
                lastPlaytime = 0,
            ),
        )
        database.achievementDao().upsertAll(
            listOf(
                com.example.backlogium.data.local.entity.Achievement(
                    appId = 440L,
                    apiName = "RETIRED",
                    unlocked = true,
                    snapshotPercent = 5.0,
                    retired = true,
                    fetchedAt = 1L,
                ),
                com.example.backlogium.data.local.entity.Achievement(
                    appId = 440L,
                    apiName = "ACTIVE",
                    unlocked = true,
                    snapshotPercent = 20.0,
                    fetchedAt = 2L,
                ),
                com.example.backlogium.data.local.entity.Achievement(
                    appId = 440L,
                    apiName = "LOCKED",
                    unlocked = false,
                    fetchedAt = 2L,
                ),
            ),
        )

        assertEquals(1, database.achievementDao().getAllUnlocked().size)
        val counts = database.achievementDao().observeCounts().first().single()
        assertEquals(2, counts.total)
        assertEquals(1, counts.unlocked)
        assertEquals(1, database.achievementDao().observeUnlockedRarity().first().size)
        assertEquals(3, database.achievementDao().getForGame(440L).size)
    }

    @Test
    fun profileDomainWritesDoNotLoseEachOther() = runBlocking {
        database.playerProfileDao().upsert(
            PlayerProfile(
                steamId = "steam",
                steamLevel = 4,
                totalXp = 10,
                level = 1,
                currentStreak = 1,
                longestStreak = 2,
                lastSyncAt = 100L,
                lastSyncError = "old",
                playtimeBackfilled = true,
                personaName = "Player",
                avatarUrl = "avatar",
            ),
        )

        coroutineScope {
            val gamification = launch {
                database.playerProfileDao().updateGamification(
                    totalXp = 400,
                    level = 4,
                    currentStreak = 3,
                    longestStreak = 5,
                    gamificationConfigVersion = 9L,
                )
            }
            val sync = launch {
                database.playerProfileDao().updateSyncStatus(200L, null)
            }
            joinAll(gamification, sync)
        }

        val stored = database.playerProfileDao().get()!!
        assertEquals(400, stored.totalXp)
        assertEquals(4, stored.level)
        assertEquals(9L, stored.gamificationConfigVersion)
        assertEquals(200L, stored.lastSyncAt)
        assertEquals(null, stored.lastSyncError)
        assertEquals("steam", stored.steamId)
        assertTrue(stored.playtimeBackfilled)
    }

    @Test
    fun accountResetClearsOwnedStateRetainsHltbAndRebaselinesProfile() = runBlocking {
        val appId = 440L
        database.gameDao().upsert(
            Game(
                appId = appId,
                name = "Old account game",
                iconUrl = "icon",
                playtimeForever = 100,
                playtime2Weeks = 20,
                lastPlaytime = 90,
                isGoal = true,
                targetMinutes = 240,
                backfillMinutes = 30,
            ),
        )
        database.hltbDataDao().upsert(
            HltbData(
                appId = appId,
                hltbId = 123L,
                mainStoryMinutes = 600,
                fetchedAt = 10L,
                matchStatus = HltbMatchStatus.RESOLVED,
            ),
        )
        database.sessionDao().insert(Session(appId = appId, startAt = 1L, minutes = 30, open = false))
        database.dailyProgressDao().upsert(DailyProgress(DATE, minutesPlayed = 30, questMet = true))
        database.achievementDao().upsertAll(
            listOf(Achievement(appId = appId, apiName = "WIN", unlocked = true, fetchedAt = 10L)),
        )
        database.gameGenreCacheDao().upsert(GameGenreCache(appId, "[\"Action\"]", 10L))
        database.gameAchievementSyncDao().upsert(
            GameAchievementSync(appId, 10L, 10L, true, 10L),
        )
        val collectionId = database.collectionDao().insert(
            CollectionEntity(
                name = "Old queue",
                mode = CollectionMode.ORDERED_QUEUE,
                sort = CollectionSort.MANUAL_SEQUENCE,
                timeBasis = CollectionTimeBasis.COMPLETIONIST,
                createdAt = 10L,
            ),
        )
        database.collectionDao().upsertMember(CollectionMember(collectionId, appId, 0))
        val runId = database.diagnosticsDao().insertRun(
            SyncRun(
                startedAt = 10L,
                durationMs = 1L,
                trigger = "test",
                requestCount = 1,
                requestMillis = 1L,
                gamesExamined = 1,
                gamesUpdated = 1,
                outcome = "success",
                errorMessage = null,
            ),
        )
        database.diagnosticsDao().insertBreakdowns(
            listOf(RequestBreakdown(runId = runId, endpoint = "test", status = 200, requestCount = 1, durationMs = 1L)),
        )
        database.diagnosticsDao().insertPresenceDecision(
            PresenceDecision(
                at = 10L,
                trigger = "test",
                outcome = "detected",
                appId = appId,
                retainedPriorState = false,
            ),
        )
        database.playerProfileDao().upsert(
            PlayerProfile(
                steamId = "old-account",
                steamLevel = 20,
                totalXp = 900,
                level = 8,
                currentStreak = 4,
                longestStreak = 12,
                gamificationConfigVersion = 42L,
                lastSyncAt = 100L,
                lastSyncError = "old error",
                playtimeBackfilled = true,
                personaName = "Old player",
                avatarUrl = "old-avatar",
                storeRegion = "PH",
                pendingImportRecompute = true,
                lastSuccessfulWishlistReadAt = 200L,
            ),
        )
        database.wishlistDao().upsertItems(
            listOf(
                WishlistItem(
                    appId = 999L,
                    name = "Old account wishlist",
                    artworkUrl = "art",
                    priority = 1,
                    addedAt = 10L,
                    lastSeenAt = 20L,
                ),
            ),
        )
        database.wishlistDao().insertObservations(
            listOf(WishlistPriceObservation(appId = 999L, observedAt = 20L)),
        )

        val reset = AccountRoomReset(database)
        reset.resetForAccountChange("new-account")
        reset.resetForAccountChange("new-account")

        assertTrue(database.gameDao().getAll().isEmpty())
        assertTrue(database.sessionDao().getAll().isEmpty())
        assertTrue(database.dailyProgressDao().getAllOrdered().isEmpty())
        assertTrue(database.achievementDao().getForGame(appId).isEmpty())
        assertTrue(database.collectionDao().getAll().isEmpty())
        assertTrue(database.collectionDao().getAllMembers().isEmpty())
        assertTrue(database.gameGenreCacheDao().observeAll().first().isEmpty())
        assertTrue(database.gameAchievementSyncDao().observeAll().first().isEmpty())
        assertTrue(database.diagnosticsDao().observeRuns().first().isEmpty())
        assertTrue(database.diagnosticsDao().observePresenceDecisions().first().isEmpty())
        assertEquals(1, database.hltbDataDao().getAll().size)
        assertEquals(123L, database.hltbDataDao().getByAppId(appId)?.hltbId)

        val profile = database.playerProfileDao().get()!!
        assertEquals("new-account", profile.steamId)
        assertEquals(0, profile.steamLevel)
        assertEquals(0, profile.totalXp)
        assertEquals(1, profile.level)
        assertEquals(0, profile.currentStreak)
        assertEquals(0, profile.longestStreak)
        assertEquals(42L, profile.gamificationConfigVersion)
        assertEquals(0L, profile.lastSyncAt)
        assertNull(profile.lastSyncError)
        assertFalse(profile.playtimeBackfilled)
        assertNull(profile.personaName)
        assertNull(profile.avatarUrl)
        assertNull(profile.storeRegion)
        assertNull(profile.lastSuccessfulWishlistReadAt)
        assertFalse(profile.pendingImportRecompute)
        assertTrue(database.wishlistDao().observeItems().first().isEmpty())
        assertTrue(database.wishlistDao().observeLatestPrices().first().isEmpty())
    }

    @Test
    fun failedRawCommitRollsBackBaselineBeforeDailyCredit() = runBlocking {
        database.gameDao().upsert(
            Game(
                appId = 440L,
                name = "Game",
                iconUrl = "",
                playtimeForever = 100,
                playtime2Weeks = 0,
                lastPlaytime = 100,
            ),
        )
        database.dailyProgressDao().upsert(
            DailyProgress(DATE, minutesPlayed = 4, goalMinutesPlayed = 2, questMet = true),
        )

        try {
            database.withTransaction {
                database.gameDao().updateSteamFields(
                    appId = 440L,
                    name = "Game",
                    iconUrl = "",
                    playtimeForever = 130,
                    playtime2Weeks = 0,
                    lastPlaytime = 130,
                    lastSyncedAt = 2L,
                    lastPlayedAt = null,
                    returnedToPlayAt = null,
                )
                error("injected failure between baseline and daily progress")
                // The real commit credits daily progress after the baseline write. The injected
                // exception must roll both writes back, so the next poll can observe the delta.
                database.dailyProgressDao().addMinutes(DATE, 30, 30)
            }
        } catch (_: IllegalStateException) {
            // Expected: the transaction boundary is the behavior under test.
        }

        assertEquals(100, database.gameDao().getById(440L)!!.lastPlaytime)
        assertEquals(4, database.dailyProgressDao().getByDate(DATE)!!.minutesPlayed)
    }

    @Test
    fun twoConcurrentCommitsReReadBaselineAndCreditOneSession() = runBlocking {
        database.gameDao().upsert(
            Game(
                appId = 440L,
                name = "Game",
                iconUrl = "",
                playtimeForever = 100,
                playtime2Weeks = 0,
                lastPlaytime = 100,
            ),
        )
        database.playerProfileDao().upsert(PlayerProfile(lastSyncAt = 1_000L))
        database.dailyProgressDao().ensureDate(DATE)

        // This intentionally does not use SteamSyncCoordinator. Both callers reach Room, which
        // proves the correctness layer is the transaction's fresh read rather than the mutex.
        coroutineScope {
            launch { commitPollSnapshot(playtime = 130, pollAt = 2_000L) }
            launch { commitPollSnapshot(playtime = 130, pollAt = 3_000L) }
        }

        val sessions = database.sessionDao().getAll()
        assertEquals(1, sessions.size)
        assertEquals(30, sessions.single().minutes)
        assertFalse(sessions.single().open)
        assertEquals(30, database.dailyProgressDao().getByDate(DATE)!!.minutesPlayed)
        assertEquals(130, database.gameDao().getById(440L)!!.lastPlaytime)
    }

    private suspend fun commitPollSnapshot(playtime: Int, pollAt: Long) {
        database.withTransaction {
            val profile = database.playerProfileDao().get()!!
            val game = database.gameDao().getById(440L)!!
            val open = database.sessionDao().getOpenSession(440L)
            val prior = SessionDiffer.GameDiffState(
                lastPlaytime = game.lastPlaytime,
                openSession = open?.let {
                    SessionDiffer.OpenSession(
                        startAt = it.startAt,
                        minutes = it.minutes,
                        lastIncreaseAt = it.endAt ?: it.startAt,
                    )
                },
            )
            val diff = SessionDiffer().diff(
                polls = listOf(SessionDiffer.PollGame(440L, playtime)),
                priorStates = mapOf(440L to prior),
                now = pollAt,
                previousPollAt = profile.lastSyncAt,
            )
            diff.actions.forEach { action ->
                when (action) {
                    is SessionDiffer.SessionAction.Open -> database.sessionDao().insert(
                        Session(
                            appId = action.appId,
                            startAt = action.startAt,
                            endAt = action.endAt,
                            minutes = action.minutes,
                            open = true,
                        ),
                    )

                    is SessionDiffer.SessionAction.Extend ->
                        database.sessionDao().getOpenSession(action.appId)?.let {
                            database.sessionDao().update(it.copy(minutes = action.minutes, endAt = action.endAt))
                        }

                    is SessionDiffer.SessionAction.Close ->
                        database.sessionDao().getOpenSession(action.appId)?.let {
                            database.sessionDao().update(it.copy(open = false, endAt = action.endAt))
                        }
                }
            }

            database.gameDao().updateSteamFields(
                appId = 440L,
                name = game.name,
                iconUrl = game.iconUrl,
                playtimeForever = playtime,
                playtime2Weeks = 0,
                lastPlaytime = diff.newLastPlaytime[440L]!!,
                lastSyncedAt = pollAt,
                lastPlayedAt = null,
                returnedToPlayAt = null,
            )
            val delta = diff.playedDeltaByAppId[440L] ?: 0
            database.dailyProgressDao().ensureDate(DATE)
            database.dailyProgressDao().addMinutes(DATE, delta, delta)
            database.playerProfileDao().updateSyncStatus(pollAt, null)
        }
    }

    private companion object {
        const val DATE = "2026-08-15"
    }
}
