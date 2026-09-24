package com.example.backlogium.data.repo

import androidx.room.Room
import com.example.backlogium.data.backup.RoomDatabaseTransactionScope
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.domain.CloudCoverageState
import com.example.backlogium.domain.CloudPresenceCurrentState
import com.example.backlogium.domain.CloudPresenceInterval
import com.example.backlogium.domain.CloudPresenceTransition
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class CloudPendingEvidenceTest {
    private lateinit var db: BacklogiumDatabase
    private lateinit var evidence: RoomCloudPendingEvidence

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), BacklogiumDatabase::class.java,
        ).allowMainThreadQueries().build()
        evidence = RoomCloudPendingEvidence(db.pendingCloudEvidenceDao(), db.gameDao(), RoomDatabaseTransactionScope(db))
    }

    @After fun tearDown() = db.close()

    @Test fun ownedIntervalUpsertRefinesOngoingBoundaryWithoutDuplicatingEvidence() = runTest {
        db.gameDao().upsert(Game(440, "Owned", "", 0, 0, 0))
        val opening = transition(10, 440)
        val ongoing = interval(end = 20, ongoing = true)
        evidence.retain("account-a", 1, 0, listOf(ongoing), opening)
        assertEquals(opening, evidence.boundary("account-a", 1))

        val close = transition(30, null).copy(previousLastObservedAt = 30)
        val closed = CloudPresenceRepository.ParsedCloudRead(
            account = "account-a", transitions = listOf(close), current = null,
            windowStart = 0, windowEnd = 30, readAt = 31, nextPosition = "30", hasMore = false,
        ).toSnapshot(evidence.boundary("account-a", 1)).intervals.single()
        assertEquals(10L, closed.startAt)
        assertEquals(30L, closed.endAt)
        evidence.retain("account-a", 1, 0, listOf(closed), close)
        evidence.retain("account-a", 1, 0, listOf(ongoing), opening)

        val stored = evidence.intervals("account-a", 1).single()
        assertEquals(30L, stored.endAt)
        assertEquals(false, stored.ongoing)
        assertEquals(CloudCoverageState.CONTINUOUS, stored.coverage)
        assertNull(evidence.boundary("account-b", 1))
        assertEquals(emptyList<CloudPresenceInterval>(), evidence.intervals("account-a", 2))
    }

    @Test fun evidenceIsOnlyAcquiredForSteamOwnedGamesAndClearsWithGeneration() = runTest {
        db.gameDao().upsert(Game(440, "Owned", "", 0, 0, 0))
        db.gameDao().upsert(Game(441, "Shared", "", 0, 0, 0,
            source = com.example.backlogium.domain.GameSource.FAMILY_SHARED))
        evidence.retain("account-a", 1, 0, listOf(
            interval(20, true), interval(20, true).copy(appId = 441),
            interval(20, true).copy(appId = 442),
        ), null)
        assertEquals(listOf(440L, 442L), evidence.intervals("account-a", 1).map { it.appId })
        evidence.clear()
        assertEquals(emptyList<CloudPresenceInterval>(), evidence.intervals("account-a", 1))
    }

    private fun transition(at: Long, appId: Long?) = CloudPresenceTransition(
        at = at, appId = appId, gameName = "Owned", personastate = null,
        previousLastObservedAt = null, previousCoverageLapseFrom = null,
        previousCoverageLapseRecoveredAt = null, schemaVersion = 2,
    )

    private fun interval(end: Long, ongoing: Boolean) = CloudPresenceInterval(
        appId = 440, gameName = "Owned", startAt = 10, endAt = end, ongoing = ongoing,
        coverage = CloudCoverageState.CONTINUOUS, observedUntil = null,
        coverageLapseFrom = null, coverageLapseRecoveredAt = null,
        mayHaveStartedBefore = false,
    )
}
