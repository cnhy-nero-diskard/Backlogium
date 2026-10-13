package com.example.backlogium.domain

import androidx.room.Room
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.PlayerProfile
import com.example.backlogium.data.local.entity.RecoveredSharedPlayState
import com.example.backlogium.data.local.entity.Session
import com.example.backlogium.data.local.entity.TimingInformedSteamPlayState
import com.example.backlogium.data.local.recovery.PlaytimeRecoveryIdentity
import com.example.backlogium.data.repo.PlaytimeRecoveryCommitAdapter
import com.example.backlogium.data.repo.SessionActionWriter
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Committer fixtures for the superseded `attribute-playtime-from-presence` fallback contract that
 * `recover-missed-playtime` replaces. Each test drives the real [PlaytimeObservationCommitter]
 * through the recovery-enabled path (matching-account profile seeded) and asserts the new
 * semantics: no-evidence increases become dated unknown-time allocations or pending quantity
 * instead of synthetic sessions, stale open sessions close at their retained boundary keeping
 * their funded minutes, and validated evidence places only a bounded timed share.
 */
@RunWith(RobolectricTestRunner::class)
class CloudTimingContributionCommitTest {
    private lateinit var db: BacklogiumDatabase
    private val minute = 60_000L
    private val account = "76561198000000011"

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), BacklogiumDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    @Test fun changedPlacementKeepsSteamMinutesAndPreservesTheRecoveredFact() = runTest {
        seed()
        db.sessionDao().insert(Session(
            appId = 440, startAt = 10 * minute, endAt = 20 * minute,
            minutes = 10, open = true, recoveredSharedPlay = RecoveredSharedPlayState.PARTIAL,
        ))

        val result = commit(interval(10 * minute, 40 * minute), playtime = 120)

        // The 20-minute increase is funded and evidence-placed, never double-counted.
        assertEquals(20, result.playedDeltaByAppId[440])
        val sessions = db.sessionDao().getAll()
        assertEquals(2, sessions.size)
        val legacy = sessions.first { it.minutes == 10 }
        assertEquals(false, legacy.open)
        assertEquals(RecoveredSharedPlayState.PARTIAL, legacy.recoveredSharedPlay)
        val evidence = sessions.first { it.minutes == 20 }
        assertEquals(false, evidence.open)
        assertEquals(TimingInformedSteamPlayState.FULL, evidence.timingInformedSteamPlay)
        val share = db.playtimeRecoveryDao().activeTimedSharesForSession(evidence.id).single()
        assertEquals(20L, share.minutes)
    }

    @Test fun sourceConvertedRecoveredOpenSessionKeepsBothFactsWithoutClaimingFullRecovery() = runTest {
        seed(source = GameSource.FAMILY_SHARED)
        db.sessionDao().insert(Session(
            appId = 440, startAt = 10 * minute, endAt = 20 * minute,
            minutes = 10, open = true, recoveredSharedPlay = RecoveredSharedPlayState.FULL,
        ))
        // A shared game's conversion changes its source and Steam baseline, not its open row.
        assertEquals(1, db.gameDao().convertSharedToOwned(440, 100, 0, 10 * minute))

        val result = commit(interval(10 * minute, 40 * minute), playtime = 120)

        assertEquals(20, result.playedDeltaByAppId[440])
        val sessions = db.sessionDao().getAll()
        assertEquals(2, sessions.size)
        val converted = sessions.first { it.minutes == 10 }
        assertEquals(false, converted.open)
        // The converted row keeps its recovered fact; the owned increase does not claim recovery.
        assertEquals(RecoveredSharedPlayState.FULL, converted.recoveredSharedPlay)
        val owned = sessions.first { it.minutes == 20 }
        assertEquals(RecoveredSharedPlayState.NONE, owned.recoveredSharedPlay)
        assertEquals(TimingInformedSteamPlayState.FULL, owned.timingInformedSteamPlay)
    }

    @Test fun unaidedMinutesStopAtTheStaleBoundaryAndTheFundedSessionKeepsItsTiming() = runTest {
        seed()
        db.sessionDao().insert(Session(
            appId = 440, startAt = 10 * minute, endAt = 20 * minute, minutes = 10, open = true,
            timingInformedSteamPlay = TimingInformedSteamPlayState.FULL,
        ))

        commit(null, playtime = 120)

        // An uncovered gap is not bridged: the fully-timed row closes at its recorded boundary
        // with its funded minutes intact, and the 20 unaided minutes become a dated allocation.
        val sessions = db.sessionDao().getAll()
        assertEquals(1, sessions.size)
        val retained = sessions.single()
        assertEquals(false, retained.open)
        assertEquals(10, retained.minutes)
        assertEquals(TimingInformedSteamPlayState.FULL, retained.timingInformedSteamPlay)
        assertEquals(20L, db.playtimeRecoveryDao().sumActiveDatedMinutes(account, 440))
    }

    @Test fun unvalidatedSuggestionFallsBackWithoutInventingASession() = runTest {
        seed()
        // Without a reader-generation identity, an otherwise continuous-looking suggestion is not
        // trusted; the complete delta remains dated unknown-time credit.
        commit(interval(10 * minute, 50 * minute).copy(ongoing = true), playtime = 120,
            includeReaderIdentity = false)

        assertTrue(db.sessionDao().getAll().isEmpty())
        assertEquals(20L, db.playtimeRecoveryDao().sumActiveDatedMinutes(account, 440))
    }

    @Test fun rejectedCoverageDoesNotClaimTimingAndCreditsTheRecoveryDate() = runTest {
        seed()
        commit(interval(20 * minute, 40 * minute).copy(coverage = CloudCoverageState.UNKNOWN), playtime = 120)

        // Unknown coverage supplies no timing: no session is fabricated and every Steam minute
        // is credited as unknown-time recovery on the recovery date.
        assertTrue(db.sessionDao().getAll().isEmpty())
        val allocations = db.playtimeRecoveryDao().activeAllocations(contributionId())
        assertEquals(1, allocations.size)
        assertEquals(20L, allocations.single().minutes)
        assertEquals("1970-01-01", allocations.single().date)
    }

    @Test fun absentReaderCreditsTheRecoveryDateInsteadOfSynthesizingASession() = runTest {
        seed()
        commit(null, playtime = 120)

        // The #181 defect: the old synthetic session wrote minutes against the previous sync
        // date. Recovery writes the same Steam-authorized minutes as dated unknown-time activity
        // on the recovery date, with no invented start/end timestamps.
        assertTrue(db.sessionDao().getAll().isEmpty())
        val allocations = db.playtimeRecoveryDao().activeAllocations(contributionId())
        assertEquals(1, allocations.size)
        assertEquals(20L, allocations.single().minutes)
        assertEquals(20, db.dailyProgressDao().getByDate("1970-01-01")?.minutesPlayed)
    }

    private suspend fun seed(source: GameSource = GameSource.STEAM_OWNED) {
        db.gameDao().upsert(Game(
            appId = 440, name = "Owned", iconUrl = "", playtimeForever = 100,
            playtime2Weeks = 0, lastPlaytime = 100, source = source,
            lastSyncedAt = 10 * minute,
        ))
        db.playerProfileDao().upsert(PlayerProfile(steamId = account, lastSyncAt = 10 * minute))
    }

    private suspend fun commit(
        interval: CloudPresenceInterval?,
        playtime: Int,
        includeReaderIdentity: Boolean = true,
    ) = committer().commit(
        observed = listOf(PlaytimeObservationCommitter.ObservedGame(440, "Owned", "", playtime, 0)),
        observedPlayAt = 50 * minute,
        syncedAt = 50 * minute,
        placement = interval?.let {
            CloudPresencePlaytimePlacement.Input(
                intervals = listOf(it),
                readerIdentity = if (includeReaderIdentity) CloudReaderIdentity(account, readerGeneration = 0L) else null,
            )
        },
    )

    private fun interval(start: Long, end: Long) = CloudPresenceInterval(
        appId = 440, gameName = "Owned", startAt = start, endAt = end,
        ongoing = false, coverage = CloudCoverageState.CONTINUOUS,
        observedUntil = null, coverageLapseFrom = null, coverageLapseRecoveredAt = null,
        mayHaveStartedBefore = false,
    )

    private fun contributionId() = PlaytimeRecoveryIdentity.contributionId(account, 440L, 1L, 100L, 120L)

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
            recoveryAdapter = PlaytimeRecoveryCommitAdapter(db.playtimeRecoveryDao(), db.sessionDao()),
        )
    }
}
