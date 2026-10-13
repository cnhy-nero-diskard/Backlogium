package com.example.backlogium.data.repo

import androidx.room.Room
import com.example.backlogium.data.backup.RoomDatabaseTransactionScope
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.PlayerProfile
import com.example.backlogium.domain.GameSource
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class LocalPlaytimeEvidenceRecorderTest {
    private lateinit var db: BacklogiumDatabase
    private val account = "local-timing-fixture"

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), BacklogiumDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After
    fun close() = db.close()

    private fun recorder() = LocalPlaytimeEvidenceRecorder(
        db.playtimeRecoveryDao(), db.gameDao(), RoomDatabaseTransactionScope(db),
    )

    private suspend fun seed() {
        db.playerProfileDao().upsert(PlayerProfile(steamId = account))
        db.gameDao().upsert(Game(440, "Fixture", "", 500, 0, 500))
    }

    @Test
    fun `adjacent samples persist evidence but no quantity session or baseline`() = runTest {
        seed()
        val recorder = recorder()
        recorder.observation(account, 440, 1_000)
        val first = db.playtimeRecoveryDao().evidenceForAccount(account).single()
        assertEquals(first.firstSampleAt, first.observedUntil)
        recorder.observation(account, 440, 31_000)
        val evidence = db.playtimeRecoveryDao().evidenceForAccount(account).single()
        assertEquals(1_000L, evidence.firstSampleAt)
        assertEquals(31_000L, evidence.observedUntil)
        assertTrue(db.sessionDao().getAll().isEmpty())
        assertTrue(db.playtimeRecoveryDao().contributionsForGame(account, 440).isEmpty())
        assertTrue(db.playtimeRecoveryDao().statesForAccount(account).isEmpty())
        assertEquals(500, db.gameDao().getById(440)!!.lastPlaytime)
    }

    @Test
    fun `network and process gaps retain prefixes but cannot join later samples`() = runTest {
        seed()
        val firstProcess = recorder()
        firstProcess.observation(account, 440, 1_000)
        firstProcess.observation(account, 440, 31_000)
        firstProcess.breakContinuity(40_000)
        firstProcess.observation(account, 440, 61_000)
        recorder().observation(account, 440, 91_000)
        val evidence = db.playtimeRecoveryDao().evidenceForAccount(account)
        assertEquals(3, evidence.size)
        assertTrue(evidence.any { it.firstSampleAt == 1_000L && it.observedUntil == 31_000L })
        assertTrue(evidence.filter { it.firstSampleAt != 1_000L }.all { it.firstSampleAt == it.observedUntil })
    }

    @Test
    fun `synchronous service stop fence prevents continuity before async persistence`() = runTest {
        seed()
        val recorder = recorder()
        recorder.observation(account, 440, 1_000)
        recorder.observation(account, 440, 31_000)
        recorder.invalidateContinuity()
        recorder.observation(account, 440, 61_000)
        val evidence = db.playtimeRecoveryDao().evidenceForAccount(account)
        assertEquals(2, evidence.size)
        assertTrue(evidence.any { it.firstSampleAt == 61_000L && it.observedUntil == 61_000L })
    }

    @Test
    fun `account reset and shared games cannot repopulate owned timing facts`() = runTest {
        seed()
        val recorder = recorder()
        recorder.observation("inactive-account", 440, 1_000)
        assertTrue(db.playtimeRecoveryDao().evidenceForAccount("inactive-account").isEmpty())
        db.gameDao().upsert(Game(570, "Shared fixture", "", 0, 0, 0, source = GameSource.FAMILY_SHARED))
        recorder.observation(account, 570, 31_000)
        assertTrue(db.playtimeRecoveryDao().evidenceForAccount(account).isEmpty())
    }
}
