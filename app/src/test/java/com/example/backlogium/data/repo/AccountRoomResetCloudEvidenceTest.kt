package com.example.backlogium.data.repo

import androidx.room.Room
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.entity.CloudHistoricalBoundary
import com.example.backlogium.data.local.entity.CloudHistoricalInterval
import com.example.backlogium.data.local.entity.CloudHistoricalJournal
import com.example.backlogium.data.local.entity.CloudHistoricalOperation
import com.example.backlogium.data.local.entity.CloudHistoricalStates
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
        val historicalDao = database.cloudHistoricalDao()
        val operation = CloudHistoricalOperation(
            operationId = "old-history",
            account = "old",
            readerGeneration = 1L,
            endpointIdentity = "https://reader.example.com/read",
            selectedStartAt = 0L,
            fromAt = 0L,
            throughAt = 300L,
            pagesFetched = 1,
            acquisitionComplete = true,
            state = CloudHistoricalStates.COMPLETE,
            createdAt = 1L,
            updatedAt = 2L,
        )
        historicalDao.insertOperation(operation)
        historicalDao.insertInterval(CloudHistoricalInterval(
            operationId = operation.operationId,
            account = operation.account,
            readerGeneration = operation.readerGeneration,
            endpointIdentity = operation.endpointIdentity,
            appId = 440L,
            startAt = 100L,
            endAt = 200L,
            ongoing = false,
            coverage = "CONTINUOUS",
            observedUntil = 200L,
            coverageLapseFrom = null,
            coverageLapseRecoveredAt = null,
            mayHaveStartedBefore = false,
            gameName = "Game",
            windowStart = operation.fromAt,
        ))
        historicalDao.insertBoundary(CloudHistoricalBoundary(
            operationId = operation.operationId,
            account = operation.account,
            readerGeneration = operation.readerGeneration,
            endpointIdentity = operation.endpointIdentity,
            kind = CloudHistoricalStates.BOUNDARY_LAST_TRANSITION,
            at = 200L,
            appId = 440L,
            gameName = "Game",
            personastate = 1,
            previousLastObservedAt = 200L,
            previousCoverageLapseFrom = null,
            previousCoverageLapseRecoveredAt = null,
            schemaVersion = 3,
        ))
        historicalDao.upsertJournal(CloudHistoricalJournal(
            operationId = operation.operationId,
            account = operation.account,
            readerGeneration = operation.readerGeneration,
            endpointIdentity = operation.endpointIdentity,
            state = CloudHistoricalStates.JOURNAL_PREPARED,
            payloadJson = "{}",
            updatedAt = 2L,
        ))

        AccountRoomReset(database).resetForAccountChange("new")

        assertTrue(dao.intervals("old", 1L).isEmpty())
        assertNull(dao.boundary("old", 1L))
        assertNull(historicalDao.operation(operation.operationId))
        assertTrue(historicalDao.intervals(operation.operationId).isEmpty())
        assertNull(historicalDao.boundary(operation.operationId, CloudHistoricalStates.BOUNDARY_LAST_TRANSITION))
        assertNull(historicalDao.journal(operation.operationId))
    }
}
