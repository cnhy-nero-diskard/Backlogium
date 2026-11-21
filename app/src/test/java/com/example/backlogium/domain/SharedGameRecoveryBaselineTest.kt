package com.example.backlogium.domain

import androidx.room.Room
import androidx.room.withTransaction
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.entity.GameRecoveryState
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.PlaytimeContribution
import com.example.backlogium.data.local.entity.PlayerProfile
import com.example.backlogium.data.local.entity.RecoveryProvenance
import com.example.backlogium.data.local.entity.Session
import com.example.backlogium.data.local.entity.UnknownTimeAllocation
import com.example.backlogium.data.remote.dto.OwnedGameDto
import com.example.backlogium.data.repo.AccountRoomReset
import com.example.backlogium.data.repo.PlaytimeRecoveryCommitAdapter
import com.example.backlogium.data.repo.SessionActionWriter
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
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

@RunWith(RobolectricTestRunner::class)
class SharedGameRecoveryBaselineTest {
    private lateinit var db: BacklogiumDatabase
    private val time = object : TimeProvider {
        override fun nowMillis() = 1_700_000_000_000L
        override fun zone() = ZoneOffset.UTC
        override fun today() = LocalDate.of(2023, 11, 14)
    }

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), BacklogiumDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After
    fun close() = db.close()

    @Test
    fun `conversion seeds a fresh epoch and keeps shared sessions without lifetime credit`() = runTest {
        val account = "conversion-fixture-account"
        db.playerProfileDao().upsert(PlayerProfile(steamId = account))
        db.gameDao().upsert(Game(440, "Shared fixture", "", 0, 0, 0, source = GameSource.FAMILY_SHARED))
        db.sessionDao().insert(Session(appId = 440, startAt = time.nowMillis() - 60_000, minutes = 1, open = false))
        val converter = SharedGameConverter(db.gameDao(), db.playtimeRecoveryDao(), time)
        db.withTransaction {
            assertEquals(listOf(440L), converter.convertNewlyOwned(listOf(OwnedGameDto(440, playtimeForever = 500)), time.nowMillis()))
        }
        val state = db.playtimeRecoveryDao().state(account, 440)!!
        assertEquals(500L, state.highWaterMinutes)
        assertEquals(1L, state.baselineEpoch)
        assertEquals("Z", state.lastObservationZone)
        assertEquals(500, db.gameDao().getById(440)!!.lastPlaytime)
        assertEquals(1, db.sessionDao().getAll().single().minutes)
        assertTrue(db.playtimeRecoveryDao().contributionsForGame(account, 440).isEmpty())
        db.withTransaction {
            assertTrue(converter.convertNewlyOwned(listOf(OwnedGameDto(440, playtimeForever = 500)), time.nowMillis()).isEmpty())
        }
        assertEquals(state, db.playtimeRecoveryDao().state(account, 440))
    }

    @Test
    fun `conversion and the same poll establish a baseline without borrowing lifetime or fabricating a session`() = runTest {
        val account = "conversion-manual-fixture"
        db.playerProfileDao().upsert(
            PlayerProfile(steamId = account, lastSyncAt = time.nowMillis() - 1_000L, playtimeBackfilled = true),
        )
        db.gameDao().upsert(
            Game(
                appId = 441,
                name = "Shared fixture",
                iconUrl = "",
                playtimeForever = 0,
                playtime2Weeks = 0,
                lastPlaytime = 0,
                isGoal = true,
                targetMinutes = 24_000,
                source = GameSource.FAMILY_SHARED,
                manualSharedMinutes = 55,
            ),
        )
        val existingSharedSessionId = db.sessionDao().insert(
            Session(appId = 441, startAt = time.nowMillis() - 60_000L, minutes = 1, open = false),
        )
        val converter = SharedGameConverter(db.gameDao(), db.playtimeRecoveryDao(), time)
        val committer = PlaytimeObservationCommitter(
            gameDao = db.gameDao(),
            sessionDao = db.sessionDao(),
            dailyProgressDao = db.dailyProgressDao(),
            profileDao = db.playerProfileDao(),
            hiddenGameDao = db.hiddenGameDao(),
            differ = SessionDiffer(),
            time = time,
            sessionActionWriter = SessionActionWriter(
                db.sessionDao(), db.dailyProgressDao(), db.hiddenGameDao(), time,
            ),
            recoveryAdapter = PlaytimeRecoveryCommitAdapter(db.playtimeRecoveryDao(), db.sessionDao()),
        )
        val baselineAt = time.nowMillis()

        val (converted, firstPoll) = db.withTransaction {
            val convertedRows = converter.convertNewlyOwned(
                listOf(OwnedGameDto(441, playtimeForever = 500)),
                baselineAt,
            )
            val poll = committer.commit(
                observed = listOf(PlaytimeObservationCommitter.ObservedGame(441, "Shared fixture", "", 500, 0)),
                observedPlayAt = baselineAt,
                syncedAt = baselineAt,
                expectedAccount = account,
            )
            convertedRows to poll
        }

        assertEquals(listOf(441L), converted)
        assertFalse(firstPoll.recordedPlay)
        assertEquals(0, firstPoll.playedDeltaByAppId[441L] ?: 0)
        assertEquals(0, firstPoll.recoveredContributions)
        val convertedGame = db.gameDao().getById(441)!!
        assertEquals(GameSource.STEAM_OWNED, convertedGame.source)
        assertEquals(0, convertedGame.manualSharedMinutes)
        assertEquals(55, convertedGame.backfillMinutes)
        assertEquals(500, convertedGame.playtimeForever)
        assertEquals(500, convertedGame.lastPlaytime)
        assertNull(convertedGame.firstSeenAt)
        assertNull(convertedGame.lastPlayedAt)
        assertNull(convertedGame.returnedToPlayAt)
        assertTrue(db.playerProfileDao().get()!!.playtimeBackfilled)
        val baseline = db.playtimeRecoveryDao().state(account, 441)!!
        assertEquals(1L, baseline.baselineEpoch)
        assertEquals(500L, baseline.highWaterMinutes)
        assertTrue(db.playtimeRecoveryDao().contributionsForGame(account, 441).isEmpty())
        assertEquals(1, db.sessionDao().getAll().size)
        assertEquals(existingSharedSessionId, db.sessionDao().getAll().single().id)

        db.playerProfileDao().updateSyncStatus(baselineAt, null)
        val increaseAt = baselineAt + 30 * 60_000L
        val increase = db.withTransaction {
            committer.commit(
                observed = listOf(PlaytimeObservationCommitter.ObservedGame(441, "Shared fixture", "", 520, 0)),
                observedPlayAt = increaseAt,
                syncedAt = increaseAt,
                expectedAccount = account,
            )
        }
        assertTrue(increase.recordedPlay)
        assertEquals(20, increase.playedDeltaByAppId[441L])
        assertEquals(1, increase.recoveredContributions)
        assertEquals(20L, db.playtimeRecoveryDao().contributionsForGame(account, 441).sumOf { it.authorizedMinutes })
        assertEquals(520L, db.playtimeRecoveryDao().state(account, 441)!!.highWaterMinutes)
        assertEquals(55, db.gameDao().getById(441)!!.backfillMinutes)
        assertEquals(0, db.gameDao().getById(441)!!.manualSharedMinutes)
        assertEquals(21, db.sessionDao().creditedMinutesByGame().single().minutes)
        assertEquals(2, db.sessionDao().getAll().size)
    }

    @Test
    fun `old-account commit after reset is fenced and new-account first poll establishes a clean baseline`() = runTest {
        val oldAccount = "76561198000000001"
        val newAccount = "76561198000000002"
        val oldGame = Game(
            appId = 442,
            name = "Old account game",
            iconUrl = "",
            playtimeForever = 500,
            playtime2Weeks = 0,
            lastPlaytime = 500,
            isGoal = true,
            backfillMinutes = 25,
        )
        db.playerProfileDao().upsert(
            PlayerProfile(steamId = oldAccount, lastSyncAt = time.nowMillis()),
        )
        db.gameDao().upsert(oldGame)
        db.playtimeRecoveryDao().upsertState(
            GameRecoveryState(
                account = oldAccount,
                appId = oldGame.appId,
                baselineEpoch = 1L,
                highWaterMinutes = 500L,
                lastObservationAt = time.nowMillis(),
                lastObservationDate = time.today().toString(),
                lastObservationZone = "UTC",
                clockState = "VALID",
                updatedAt = time.nowMillis(),
            ),
        )
        val oldContribution = PlaytimeContribution(
            contributionId = "old-account-funded",
            account = oldAccount,
            appId = oldGame.appId,
            baselineEpoch = 1L,
            fromMinutes = 400L,
            toMinutes = 500L,
            windowStartAt = time.nowMillis() - 3_600_000L,
            windowEndAt = time.nowMillis(),
            authorizedMinutes = 100L,
            revision = 1L,
            provenance = RecoveryProvenance.RETROACTIVE_PLAY,
            zone = "UTC",
            createdAt = time.nowMillis(),
        )
        db.playtimeRecoveryDao().commitAllocatedGraph(
            contribution = oldContribution,
            timed = emptyList(),
            allocations = listOf(
                UnknownTimeAllocation(
                    placementId = "old-account-funded:1:u:2023-11-14",
                    contributionId = oldContribution.contributionId,
                    account = oldAccount,
                    appId = oldGame.appId,
                    revision = 1L,
                    date = "2023-11-14",
                    minutes = 100L,
                    method = "SINGLE_DAY",
                    zone = "UTC",
                    createdAt = time.nowMillis(),
                ),
            ),
        )
        val oldSessionId = db.sessionDao().insert(
            Session(
                appId = oldGame.appId,
                startAt = time.nowMillis() - 60_000L,
                endAt = time.nowMillis(),
                minutes = 60,
                open = false,
            ),
        )
        db.dailyProgressDao().ensureDate("2023-11-14")
        db.dailyProgressDao().addMinutes("2023-11-14", 60, 60)

        AccountRoomReset(db).resetForAccountChange(newAccount)

        assertEquals(newAccount, db.playerProfileDao().get()!!.steamId)
        assertTrue(db.gameDao().getAll().isEmpty())
        assertTrue(db.sessionDao().getAll().isEmpty())
        assertTrue(db.dailyProgressDao().getAllOrdered().isEmpty())
        assertTrue(db.playtimeRecoveryDao().statesForAccount(oldAccount).isEmpty())
        assertTrue(db.playtimeRecoveryDao().contributionsForAccount(oldAccount).isEmpty())

        val committer = PlaytimeObservationCommitter(
            gameDao = db.gameDao(),
            sessionDao = db.sessionDao(),
            dailyProgressDao = db.dailyProgressDao(),
            profileDao = db.playerProfileDao(),
            hiddenGameDao = db.hiddenGameDao(),
            differ = SessionDiffer(),
            time = time,
            sessionActionWriter = SessionActionWriter(
                db.sessionDao(), db.dailyProgressDao(), db.hiddenGameDao(), time,
            ),
            recoveryAdapter = PlaytimeRecoveryCommitAdapter(db.playtimeRecoveryDao(), db.sessionDao()),
        )
        val stale = db.withTransaction {
            committer.commit(
                observed = listOf(PlaytimeObservationCommitter.ObservedGame(442, "Old account game", "", 700, 0)),
                observedPlayAt = time.nowMillis() + 1_000L,
                syncedAt = time.nowMillis() + 1_000L,
                expectedAccount = oldAccount,
            )
        }
        assertFalse(stale.recordedPlay)
        assertEquals(0, stale.playedDeltaByAppId[442L] ?: 0)
        assertTrue(db.gameDao().getAll().isEmpty())
        assertTrue(db.sessionDao().getAll().isEmpty())
        assertTrue(db.playtimeRecoveryDao().statesForAccount(oldAccount).isEmpty())
        assertTrue(db.playtimeRecoveryDao().statesForAccount(newAccount).isEmpty())

        val baselineAt = time.nowMillis() + 2_000L
        val firstNewAccountRead = db.withTransaction {
            committer.commit(
                observed = listOf(PlaytimeObservationCommitter.ObservedGame(442, "New account game", "", 605, 0)),
                observedPlayAt = baselineAt,
                syncedAt = baselineAt,
                expectedAccount = newAccount,
            )
        }
        assertFalse(firstNewAccountRead.recordedPlay)
        assertEquals(0, firstNewAccountRead.playedDeltaByAppId[442L] ?: 0)
        assertEquals(0, firstNewAccountRead.recoveredContributions)
        assertTrue(db.sessionDao().getAll().isEmpty())
        val freshBaseline = db.playtimeRecoveryDao().state(newAccount, 442)!!
        assertEquals(605L, freshBaseline.highWaterMinutes)
        assertTrue(db.playtimeRecoveryDao().contributionsForGame(newAccount, 442).isEmpty())
        assertEquals(605, db.gameDao().getById(442)!!.lastPlaytime)

        db.playerProfileDao().updateSyncStatus(baselineAt, null)
        val increase = db.withTransaction {
            committer.commit(
                observed = listOf(PlaytimeObservationCommitter.ObservedGame(442, "New account game", "", 620, 0)),
                observedPlayAt = baselineAt + 30 * 60_000L,
                syncedAt = baselineAt + 30 * 60_000L,
                expectedAccount = newAccount,
            )
        }
        assertTrue(increase.recordedPlay)
        assertEquals(15, increase.playedDeltaByAppId[442L])
        assertEquals(15L, db.playtimeRecoveryDao().contributionsForGame(newAccount, 442).single().authorizedMinutes)
        assertTrue(db.playtimeRecoveryDao().contributionsForAccount(oldAccount).isEmpty())
        assertTrue(db.playtimeRecoveryDao().statesForAccount(oldAccount).isEmpty())
        assertEquals(620L, db.playtimeRecoveryDao().state(newAccount, 442)!!.highWaterMinutes)
        assertEquals(620, db.gameDao().getById(442)!!.lastPlaytime)
        assertTrue(oldSessionId > 0L)

        // The app currently has no separate logout API. Exercise the same durable account-cleared
        // identity fence the raw committer must honor if a logout/reset leaves the profile blank.
        AccountRoomReset(db).resetForAccountChange("")
        assertEquals("", db.playerProfileDao().get()!!.steamId)
        val staleAfterLogout = db.withTransaction {
            committer.commit(
                observed = listOf(PlaytimeObservationCommitter.ObservedGame(442, "New account game", "", 640, 0)),
                observedPlayAt = baselineAt + 60 * 60_000L,
                syncedAt = baselineAt + 60 * 60_000L,
                expectedAccount = newAccount,
            )
        }
        assertFalse(staleAfterLogout.recordedPlay)
        assertEquals(0, staleAfterLogout.playedDeltaByAppId[442L] ?: 0)
        assertTrue(db.gameDao().getAll().isEmpty())
        assertTrue(db.sessionDao().getAll().isEmpty())
        assertTrue(db.playtimeRecoveryDao().statesForAccount(newAccount).isEmpty())
        assertTrue(db.playtimeRecoveryDao().contributionsForAccount(newAccount).isEmpty())
    }
}
