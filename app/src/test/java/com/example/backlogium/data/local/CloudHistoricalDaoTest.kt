package com.example.backlogium.data.local

import androidx.room.Room
import com.example.backlogium.data.local.entity.CloudHistoricalBoundary
import com.example.backlogium.data.local.entity.CloudHistoricalInterval
import com.example.backlogium.data.local.entity.CloudHistoricalJournal
import com.example.backlogium.data.local.entity.CloudHistoricalOperation
import com.example.backlogium.data.local.entity.CloudHistoricalStates
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class CloudHistoricalDaoTest {
    private lateinit var database: BacklogiumDatabase
    private lateinit var dao: com.example.backlogium.data.local.dao.CloudHistoricalDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), BacklogiumDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.cloudHistoricalDao()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun historicalPageReplayUpsertsIntervalsAndNeverRegressesItsBoundary() = runTest {
        dao.insertOperation(operation())
        val ongoing = interval(endAt = 30, ongoing = true)
        dao.upsertInterval(ongoing)
        dao.upsertInterval(ongoing)

        val closed = ongoing.copy(endAt = 40, ongoing = false)
        dao.upsertInterval(closed)
        // If a failed checkpoint causes the previous page to replay, the provisional form cannot
        // overwrite the later page's terminal interval.
        dao.upsertInterval(ongoing)

        assertEquals(listOf(closed), dao.intervals(OPERATION_ID))

        val laterBoundary = boundary(at = 40)
        dao.upsertBoundary(laterBoundary)
        dao.upsertBoundary(boundary(at = 20))
        assertEquals(laterBoundary, dao.boundary(OPERATION_ID, CloudHistoricalStates.BOUNDARY_LAST_TRANSITION))
    }

    @Test
    fun progressAndApplyUndoJournalSurviveReadsAndAreIdentityScoped() = runTest {
        val operation = operation()
        dao.insertOperation(operation)
        assertEquals(
            1,
            dao.markPageIngested(
                operationId = OPERATION_ID,
                account = ACCOUNT,
                readerGeneration = GENERATION,
                endpointIdentity = ENDPOINT,
                expectedPagesFetched = 0,
                expectedLastIngestedPageNumber = 0,
                pageNumber = 1,
            ),
        )
        assertEquals(1, dao.operation(OPERATION_ID)?.lastIngestedPageNumber)

        assertEquals(
            1,
            dao.updateProgress(
                operationId = OPERATION_ID,
                account = ACCOUNT,
                readerGeneration = GENERATION,
                endpointIdentity = ENDPOINT,
                lastPositionAt = 25,
                pagesFetched = 2,
                lastIngestedPageNumber = 2,
                transitionsFetched = 500,
                coveredStartAt = 10,
                coveredEndAt = 25,
                acquisitionComplete = false,
                state = CloudHistoricalStates.ACQUIRING,
                updatedAt = 30,
            ),
        )
        val saved = dao.operation(OPERATION_ID)!!
        assertEquals(2, saved.pagesFetched)
        assertEquals(2, saved.lastIngestedPageNumber)
        assertEquals(500, saved.transitionsFetched)
        assertEquals(25L, saved.lastPositionAt)
        assertEquals(25L, saved.coveredEndAt)
        assertNull(
            dao.operationForIdentity(ACCOUNT, GENERATION + 1, ENDPOINT, CloudHistoricalStates.ACQUIRING),
        )

        val journal = CloudHistoricalJournal(
            operationId = OPERATION_ID,
            account = ACCOUNT,
            readerGeneration = GENERATION,
            endpointIdentity = ENDPOINT,
            state = CloudHistoricalStates.JOURNAL_APPLY_COMMITTED,
            payloadJson = "{\"gameDeltas\":{\"440\":12}}",
            updatedAt = 31,
        )
        dao.upsertJournal(journal)
        assertEquals(journal, dao.journal(OPERATION_ID))
        dao.upsertJournal(journal.copy(state = CloudHistoricalStates.JOURNAL_REVERSE_COMMITTED))
        assertEquals(CloudHistoricalStates.JOURNAL_REVERSE_COMMITTED, dao.journal(OPERATION_ID)?.state)

        dao.deleteAllOperations()
        assertTrue(dao.intervals(OPERATION_ID).isEmpty())
        assertNull(dao.journal(OPERATION_ID))
    }

    @Test
    fun childRowsCannotBeStagedUnderAnotherAccountOrEndpoint() = runTest {
        dao.insertOperation(operation())

        assertTrue(
            runCatching { dao.upsertInterval(interval(endAt = 30, ongoing = true).copy(account = "other-account")) }
                .isFailure,
        )
        assertTrue(dao.intervals(OPERATION_ID).isEmpty())
    }

    private fun operation() = CloudHistoricalOperation(
        operationId = OPERATION_ID,
        account = ACCOUNT,
        readerGeneration = GENERATION,
        endpointIdentity = ENDPOINT,
        selectedStartAt = 10,
        fromAt = 10,
        throughAt = 100,
        createdAt = 1,
        updatedAt = 1,
    )

    private fun interval(endAt: Long, ongoing: Boolean) = CloudHistoricalInterval(
        operationId = OPERATION_ID,
        account = ACCOUNT,
        readerGeneration = GENERATION,
        endpointIdentity = ENDPOINT,
        appId = 440,
        startAt = 10,
        endAt = endAt,
        ongoing = ongoing,
        coverage = "CONTINUOUS",
        observedUntil = endAt,
        coverageLapseFrom = null,
        coverageLapseRecoveredAt = null,
        mayHaveStartedBefore = false,
        gameName = "Game",
        windowStart = 10,
    )

    private fun boundary(at: Long) = CloudHistoricalBoundary(
        operationId = OPERATION_ID,
        account = ACCOUNT,
        readerGeneration = GENERATION,
        endpointIdentity = ENDPOINT,
        kind = CloudHistoricalStates.BOUNDARY_LAST_TRANSITION,
        at = at,
        appId = 440,
        gameName = "Game",
        personastate = null,
        previousLastObservedAt = at,
        previousCoverageLapseFrom = null,
        previousCoverageLapseRecoveredAt = null,
        schemaVersion = 2,
    )

    private companion object {
        const val OPERATION_ID = "history-1"
        const val ACCOUNT = "76561198000000001"
        const val GENERATION = 4L
        const val ENDPOINT = "https://presence.example.test/readPresence"
    }
}
