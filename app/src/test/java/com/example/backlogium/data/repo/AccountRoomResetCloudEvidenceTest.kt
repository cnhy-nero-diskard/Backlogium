package com.example.backlogium.data.repo

import androidx.room.Room
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.entity.PendingCloudBoundary
import com.example.backlogium.data.local.entity.PendingCloudInterval
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AccountRoomResetCloudEvidenceTest {
    private lateinit var database: BacklogiumDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), BacklogiumDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun accountResetDeletesPendingIntervalsAndBoundaryInSameRoomTransaction() = runTest {
        val dao = database.pendingCloudEvidenceDao()
        dao.upsert(PendingCloudInterval(
            account = "old", generation = 1L, appId = 440L, startAt = 100L,
            endAt = 200L, ongoing = false, coverage = "CONTINUOUS",
            observedUntil = null, coverageLapseFrom = null, coverageLapseRecoveredAt = null,
            mayHaveStartedBefore = false, gameName = "Game", windowStart = 0L,
        ))
        dao.upsertBoundary(PendingCloudBoundary(
            account = "old", generation = 1L, at = 100L, appId = 440L,
            gameName = "Game", personastate = 1, previousLastObservedAt = null,
            previousCoverageLapseFrom = null, previousCoverageLapseRecoveredAt = null,
            schemaVersion = 3,
        ))

        AccountRoomReset(database).resetForAccountChange("new")

        assertTrue(dao.intervals("old", 1L).isEmpty())
        assertNull(dao.boundary("old", 1L))
    }
}
