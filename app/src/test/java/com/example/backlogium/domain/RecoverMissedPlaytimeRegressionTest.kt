package com.example.backlogium.domain

import androidx.room.Room
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.PlayerProfile
import com.example.backlogium.data.local.entity.Session
import com.example.backlogium.data.repo.SessionActionWriter
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Spec-driven acceptance fixtures for the `recover-missed-playtime` change.
 *
 * These tests compile against the *existing* commit/placement path — the real
 * [PlaytimeObservationCommitter] writing into a real in-memory Room database — and assert the
 * recovery behaviour mandated by the change's delta specs (`openspec/changes/recover-missed-
 * playtime/specs/playtime-recovery/spec.md` and `steam-sync/spec.md`). Per the repository rule in
 * CLAUDE.md ("specs win over tests"), the spec is authoritative and the current implementation is
 * the defect to be fixed: every test in this file is **intentionally red** until the
 * `recover-missed-playtime` tasks that implement recovery land (task group 3: pure attribution
 * planning; task group 4: the shared atomic commit path; task 5.x: canonical totals/corrections;
 * task 6.1: no-cloud recovery). They are deliberately not `@Ignore`d and not filtered out: a red
 * fixture here is the change's expected missing-behaviour signal, and task 1.1's whole purpose is
 * to make that signal compile and fail before implementation.
 *
 * What is asserted on the *current* storage surfaces:
 * - `daily_progress`: exact credited minutes on exact calendar dates (the "raw daily credit" the
 *   change commits together with its recovery baseline).
 * - `sessions`: exact absence of invented session rows. Unknown-time recovered play must never
 *   fabricate a start/end timestamp, so a pure-fallback commit must leave the session ledger
 *   empty; the only row a partial-evidence commit may write is the bounded timestamped share the
 *   evidence itself supports.
 * - `Commit.playedDeltaByAppId`: the exact Steam-authorized quantity, unchanged by the change.
 *
 * Date layout (UTC): 2026-07-20 is Monday, 07-21 Tuesday, 07-22 Wednesday, 07-23 Thursday,
 * 07-24 Friday.
 */
@RunWith(RobolectricTestRunner::class)
class RecoverMissedPlaytimeRegressionTest {

    private val appId = 440L
    private val account = "76561198000000001" // Non-secret, synthetic test identity.

    private val monday = atUtc(2026, 7, 20, 20, 0)
    private val tuesday = atUtc(2026, 7, 21, 20, 0)
    private val wednesday = atUtc(2026, 7, 22, 20, 0)
    private val friday = atUtc(2026, 7, 24, 20, 0)

    private lateinit var db: BacklogiumDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), BacklogiumDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    // -------------------------------------------------------------------------------------------
    // Fixture 1 — consecutive-day recovery (playtime-recovery: "Consecutive-day recovery",
    // steam-sync: "No presence record available").
    //
    // Tuesday's reading is 500, Wednesday's is 570, and no usable session timing exists. The spec
    // says Wednesday receives 70 unknown-time minutes, Tuesday receives none, and no session
    // boundary is fabricated. The current code instead opens a synthetic session that starts at the
    // previous sync (Tuesday) and credits its *start* date — Tuesday — with a fabricated timestamp.
    // This test is red until task 3.2 (same/consecutive-date fallback, "no-evidence Wed 70") lands.
    // -------------------------------------------------------------------------------------------
    @Test
    fun tuesday500ToWednesday570_creditsWednesday70UnknownTimeWithoutAnySession() = runTest {
        seed(playtime = 500, lastSyncAt = tuesday)

        val result = commit(observedPlayAt = wednesday, playtime = 570)

        assertEquals(70, result.playedDeltaByAppId[appId])
        assertEquals(70, playedByDate()["2026-07-22"]) // Wednesday, the recovery date.
        assertNull("Tuesday must receive none of the increase", playedByDate()["2026-07-21"])
        assertNoSessions() // Unknown-time play has no start/end timestamps.
    }

    // -------------------------------------------------------------------------------------------
    // Fixture 2 — multi-day exact distribution (playtime-recovery: "Four-day exact distribution").
    //
    // Monday's reading is 500, Friday's is 605: 105 unmatched minutes over the four eligible dates
    // Tuesday..Friday inclusive become 27/26/26/26, earliest-first remainder, all multi-day
    // estimated, and no date before the baseline (Monday) is invented. The current code credits all
    // 105 to the synthetic session's start date (Monday) with fabricated timestamps. Red until task
    // 3.3 ("105 -> 27/26/26/26") lands.
    // -------------------------------------------------------------------------------------------
    @Test
    fun monday500ToFriday605_distributesExactResidualAs27262626() = runTest {
        seed(playtime = 500, lastSyncAt = monday)

        val result = commit(observedPlayAt = friday, playtime = 605)

        assertEquals(105, result.playedDeltaByAppId[appId])
        val byDate = playedByDate()
        assertEquals(27, byDate["2026-07-21"]) // Tuesday, the earliest eligible date, takes q+1.
        assertEquals(26, byDate["2026-07-22"]) // Wednesday
        assertEquals(26, byDate["2026-07-23"]) // Thursday
        assertEquals(26, byDate["2026-07-24"]) // Friday, the recovery date
        assertNull("the pre-baseline date must not be invented", byDate["2026-07-20"]) // Monday
        assertEquals(105, byDate.values.sum())
        assertNoSessions()
    }

    // -------------------------------------------------------------------------------------------
    // Fixture 3 — bounded evidence placement plus residual (playtime-recovery: "Partial date
    // coverage" and "Bounded partial evidence"; design.md table: "35 evidence-informed + 70
    // fallback; 105 total").
    //
    // A 105-minute increase has a single 35-minute usable interval on Wednesday. Steam authorizes
    // 105 minutes total; 35 are placed against the evidence (the only session that may be written,
    // bounded by the interval), and the remaining 70 are distributed over all four eligible dates
    // (Tuesday..Friday — Wednesday is only *partly* covered, so it stays eligible): 70/4 = 17 r 2,
    // so the earliest two dates get 18 each (Tuesday 18, Wednesday 18) and the rest 17. Wednesday's
    // daily total is therefore 35 + 18 = 53, and the grand total is exactly 105. The current code
    // lets the tiny 35-minute fragment absorb the whole 105 into one session. Red until task 3.4
    // ("35/70 conservation") lands.
    // -------------------------------------------------------------------------------------------
    @Test
    fun evidencePlaces35Of105_andResidual70KeepsExactConservation() = runTest {
        seed(playtime = 500, lastSyncAt = monday)

        val interval = CloudPresenceInterval(
            appId = appId, gameName = "Owned",
            startAt = atUtc(2026, 7, 22, 17, 0), endAt = atUtc(2026, 7, 22, 17, 35),
            ongoing = false, coverage = CloudCoverageState.CONTINUOUS,
            observedUntil = null, coverageLapseFrom = null, coverageLapseRecoveredAt = null,
            mayHaveStartedBefore = false,
        )
        val result = commit(
            observedPlayAt = friday,
            playtime = 605,
            placement = CloudPresencePlaytimePlacement.Input(
                listOf(interval), CloudReaderIdentity(account, 1L),
            ),
        )

        // Exact conservation: evidence-informed 35 + residual 70 = the authorized 105.
        assertEquals(105, result.playedDeltaByAppId[appId])
        val byDate = playedByDate()
        assertEquals(18, byDate["2026-07-21"]) // Tuesday residual share
        assertEquals(53, byDate["2026-07-22"]) // Wednesday: 35 evidence + 18 residual share
        assertEquals(17, byDate["2026-07-23"]) // Thursday residual share
        assertEquals(17, byDate["2026-07-24"]) // Friday residual share
        assertEquals(105, byDate.values.sum())

        // Only the evidence share may carry timestamps; the residual is unknown-time and must not
        // fabricate a session. The single row is the 35-minute evidence-informed share, bounded by
        // the confirmed interval rather than spanning the whole gap.
        val sessions = db.sessionDao().getAll()
        assertEquals(1, sessions.size)
        val only = sessions.single()
        assertEquals(35, only.minutes)
        assertTrue("evidence session starts inside its interval", only.startAt >= interval.startAt)
        assertTrue(
            "evidence session ends inside its interval",
            (only.endAt ?: Long.MAX_VALUE) <= (interval.endAt ?: Long.MAX_VALUE),
        )
    }

    // -------------------------------------------------------------------------------------------
    // Fixture 4 — repeat reads across restart are exactly-once (playtime-recovery: "Downtime
    // survives process death" and "Reconciliation retry"; steam-sync: "Same increase observed by
    // both sources"; tasks 4.1/4.2).
    //
    // The same 605 observation is committed twice — the second time through a freshly constructed
    // committer over the same database, as after a process restart — after the sync side has
    // advanced the profile's lastSyncAt. The second read derives its delta from the baseline the
    // first commit advanced, so it must add nothing: the stored recovery is exactly the first
    // commit's state (27/26/26/26, 105 total, no sessions). The current code fails on placement
    // (credits Monday and writes a session) while its baseline still prevents double-counting, so
    // the quantity guard here documents what must keep holding while the placement changes.
    // -------------------------------------------------------------------------------------------
    @Test
    fun repeatedReadsAcrossRestart_doNotDoubleCreditTheRecovery() = runTest {
        seed(playtime = 500, lastSyncAt = monday)

        val first = commit(observedPlayAt = friday, playtime = 605)
        assertEquals(105, first.playedDeltaByAppId[appId])

        // The worker records the successful sync before the restart; a fresh committer then reads
        // the same 605 total again.
        db.playerProfileDao().updateSyncStatus(lastSyncAt = friday, lastSyncError = null)
        val second = committer().commit(
            observed = listOf(PlaytimeObservationCommitter.ObservedGame(appId, "Owned", "", 605, 0)),
            observedPlayAt = friday,
            syncedAt = friday,
        )

        assertTrue("the repeated observation must authorize no new minutes", !second.recordedPlay)
        val byDate = playedByDate()
        assertEquals(27, byDate["2026-07-21"])
        assertEquals(26, byDate["2026-07-22"])
        assertEquals(26, byDate["2026-07-23"])
        assertEquals(26, byDate["2026-07-24"])
        assertEquals(105, byDate.values.sum())
        assertNoSessions()
    }

    // -------------------------------------------------------------------------------------------
    // Fixture 5 — no-cloud recovery (playtime-recovery: "Cloud disabled"; task 6.1).
    //
    // The committer as constructed here has no cloud wiring at all (`pendingEvidencePruner` stays
    // null and no placement input is supplied): there is no cloud request to attempt, only the
    // ordinary cumulative-playtime read against the persisted baseline. The spec says local
    // fallback recovery must still succeed — Wednesday gets its exact 70 unknown-time minutes,
    // Tuesday nothing, and no session is invented. Today this path credits Tuesday and writes a
    // synthetic session, so the fixture is red with the missing recovery behaviour, exactly like
    // fixture 1 but exercising the literal no-cloud commit signature.
    // -------------------------------------------------------------------------------------------
    @Test
    fun noCloudIncrease_recoversLocallyThroughOrdinaryReads() = runTest {
        seed(playtime = 500, lastSyncAt = tuesday)

        val noCloud = committer().commit( // no placement, no pruner — the no-cloud path by construction
            observed = listOf(PlaytimeObservationCommitter.ObservedGame(appId, "Owned", "", 570, 0)),
            observedPlayAt = wednesday,
            syncedAt = wednesday,
        )

        assertEquals(70, noCloud.playedDeltaByAppId[appId])
        assertTrue(noCloud.clockRollbacks.isEmpty())
        assertEquals(70, playedByDate()["2026-07-22"]) // Wednesday, the recovery date
        assertNull("Tuesday must receive none of the increase", playedByDate()["2026-07-21"])
        assertNoSessions()
    }

    // -------------------------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------------------------

    private suspend fun seed(playtime: Int, lastSyncAt: Long) {
        db.gameDao().upsert(
            Game(
                appId = appId, name = "Owned", iconUrl = "",
                playtimeForever = playtime, playtime2Weeks = 0,
                lastPlaytime = playtime, source = GameSource.STEAM_OWNED,
                lastSyncedAt = lastSyncAt,
            ),
        )
        db.playerProfileDao().upsert(PlayerProfile(steamId = account, lastSyncAt = lastSyncAt))
    }

    private suspend fun commit(
        observedPlayAt: Long,
        playtime: Int,
        placement: CloudPresencePlaytimePlacement.Input? = null,
    ) = committer().commit(
        observed = listOf(PlaytimeObservationCommitter.ObservedGame(appId, "Owned", "", playtime, 0)),
        observedPlayAt = observedPlayAt,
        syncedAt = observedPlayAt,
        placement = placement,
    )

    private fun committer() = PlaytimeObservationCommitter(
        gameDao = db.gameDao(),
        sessionDao = db.sessionDao(),
        dailyProgressDao = db.dailyProgressDao(),
        profileDao = db.playerProfileDao(),
        hiddenGameDao = db.hiddenGameDao(),
        differ = SessionDiffer(),
        time = object : TimeProvider {
            override fun nowMillis() = friday
            override fun zone() = ZoneOffset.UTC
            override fun today() = LocalDate.of(2026, 7, 24)
        },
        sessionActionWriter = SessionActionWriter(
            sessionDao = db.sessionDao(),
            dailyProgressDao = db.dailyProgressDao(),
            hiddenGameDao = db.hiddenGameDao(),
            time = object : TimeProvider {
                override fun nowMillis() = friday
                override fun zone() = ZoneOffset.UTC
                override fun today() = LocalDate.of(2026, 7, 24)
            },
        ),
    )

    private suspend fun playedByDate(): Map<String, Int> =
        db.dailyProgressDao().getAllOrdered().associate { it.date to it.minutesPlayed }

    private suspend fun assertNoSessions() {
        val sessions: List<Session> = db.sessionDao().getAll()
        assertTrue("recovered unknown-time play must not fabricate session timestamps: $sessions", sessions.isEmpty())
    }

    private fun atUtc(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Instant.parse("%04d-%02d-%02dT%02d:%02d:00Z".format(year, month, day, hour, minute)).toEpochMilli()
}
