package com.example.backlogium.domain

import androidx.room.Room
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.RecoveredSharedPlayState
import com.example.backlogium.data.local.entity.Session
import com.example.backlogium.data.local.entity.TimingInformedSteamPlayState
import com.example.backlogium.data.repo.SessionActionWriter
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.LocalDate
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
class CloudTimingContributionCommitTest {
    private lateinit var db: BacklogiumDatabase
    private val minute = 60_000L

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), BacklogiumDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    @Test fun changedPlacementUsesSteamMinutesAndPreservesRecoveredFact() = runTest {
        seed()
        db.sessionDao().insert(Session(
            appId = 440, startAt = 10 * minute, endAt = 20 * minute,
            minutes = 10, open = true, recoveredSharedPlay = RecoveredSharedPlayState.PARTIAL,
        ))

        val result = commit(interval(10 * minute, 40 * minute), playtime = 120)

        assertEquals(20, result.playedDeltaByAppId[440])
        val stored = db.sessionDao().getAll().single()
        assertEquals(30, stored.minutes)
        assertEquals(TimingInformedSteamPlayState.PARTIAL, stored.timingInformedSteamPlay)
        assertEquals(RecoveredSharedPlayState.PARTIAL, stored.recoveredSharedPlay)
    }

    @Test fun unchangedSuggestionAndRejectedCoverageDoNotClaimTiming() = runTest {
        seed()
        // The unaided Open starts at the previous sync and ends at the observation.
        commit(interval(10 * minute, 50 * minute).copy(ongoing = true), playtime = 120)
        assertEquals(TimingInformedSteamPlayState.NONE, db.sessionDao().getAll().single().timingInformedSteamPlay)

        db.sessionDao().deleteAll()
        db.gameDao().upsert(db.gameDao().getById(440)!!.copy(lastPlaytime = 100))
        commit(interval(20 * minute, 40 * minute).copy(coverage = CloudCoverageState.UNKNOWN), playtime = 120)
        assertEquals(TimingInformedSteamPlayState.NONE, db.sessionDao().getAll().single().timingInformedSteamPlay)
    }

    @Test fun absentReaderWritesUnmarkedSession() = runTest {
        seed()
        commit(null, playtime = 120)
        assertEquals(TimingInformedSteamPlayState.NONE, db.sessionDao().getAll().single().timingInformedSteamPlay)
        assertEquals(20, db.sessionDao().getAll().single().minutes)
    }

    private suspend fun seed() {
        db.gameDao().upsert(Game(
            appId = 440, name = "Owned", iconUrl = "", playtimeForever = 100,
            playtime2Weeks = 0, lastPlaytime = 100,
        ))
        db.playerProfileDao().insertIfMissing()
        db.playerProfileDao().updateSyncStatus(10 * minute, null)
    }

    private suspend fun commit(interval: CloudPresenceInterval?, playtime: Int) = committer().commit(
        observed = listOf(PlaytimeObservationCommitter.ObservedGame(440, "Owned", "", playtime, 0)),
        observedPlayAt = 50 * minute,
        syncedAt = 50 * minute,
        placement = interval?.let { CloudPresencePlaytimePlacement.Input(listOf(it)) },
    )

    private fun interval(start: Long, end: Long) = CloudPresenceInterval(
        appId = 440, gameName = "Owned", startAt = start, endAt = end,
        ongoing = false, coverage = CloudCoverageState.CONTINUOUS,
        observedUntil = null, coverageLapseFrom = null, coverageLapseRecoveredAt = null,
        mayHaveStartedBefore = false,
    )

    private fun committer(): PlaytimeObservationCommitter {
        val time = object : TimeProvider {
            override fun nowMillis() = 50 * minute
            override fun zone() = ZoneOffset.UTC
            override fun today() = LocalDate.of(2026, 9, 24)
        }
        return PlaytimeObservationCommitter(
            gameDao = db.gameDao(), sessionDao = db.sessionDao(),
            dailyProgressDao = db.dailyProgressDao(), profileDao = db.playerProfileDao(),
            hiddenGameDao = db.hiddenGameDao(), differ = SessionDiffer(), time = time,
            sessionActionWriter = SessionActionWriter(
                db.sessionDao(), db.dailyProgressDao(), db.hiddenGameDao(), time,
            ),
        )
    }
}
