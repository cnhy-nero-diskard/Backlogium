package com.example.backlogium.domain

import androidx.room.Room
import androidx.room.withTransaction
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.PlayerProfile
import com.example.backlogium.data.local.entity.Session
import com.example.backlogium.data.remote.dto.OwnedGameDto
import java.time.LocalDate
import java.time.ZoneOffset
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
}
