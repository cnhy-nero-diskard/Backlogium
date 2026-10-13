package com.example.backlogium.domain

import androidx.room.Room
import com.example.backlogium.data.backup.RoomDatabaseTransactionScope
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.SettingsDataStore
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.PlayerProfile
import com.example.backlogium.data.local.entity.PlaytimeContribution
import com.example.backlogium.data.local.entity.RecoveryEvidenceQuality
import com.example.backlogium.data.local.entity.RecoveryProvenance
import com.example.backlogium.data.local.entity.Session
import com.example.backlogium.data.local.entity.TimedContributionPlacement
import com.example.backlogium.data.local.recovery.PlaytimeRecoveryIdentity
import com.example.backlogium.data.repo.DataStoreSettingsRepository
import java.time.Instant
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

/**
 * Task 6.4: the historical re-filing/apply/reversal is contribution-revision aware. Re-filing never
 * moves a session that carries an active funded recovery share; reversal never deletes a row the
 * recovery graph later funded and never resurrects minutes a correction already removed. Imported
 * balance transfers keep their own confirmation/journals (unchanged surface, covered by
 * [CloudPresencePlaytimeRefilingUseCaseTest]).
 */
@RunWith(RobolectricTestRunner::class)
class CloudPresencePlaytimeRefilingRecoveryIntegrationTest {

    private lateinit var database: BacklogiumDatabase
    private lateinit var settings: DataStoreSettingsRepository
    private lateinit var useCase: CloudPresencePlaytimeRefilingUseCase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            BacklogiumDatabase::class.java,
        ).allowMainThreadQueries().build()
        settings = DataStoreSettingsRepository(SettingsDataStore(RuntimeEnvironment.getApplication()))
        val updater = GamificationUpdater(
            sessionDao = database.sessionDao(),
            dailyProgressDao = database.dailyProgressDao(),
            playerProfileDao = database.playerProfileDao(),
            hltbDataDao = database.hltbDataDao(),
            achievementDao = database.achievementDao(),
            gameDao = database.gameDao(),
            hiddenGameDao = database.hiddenGameDao(),
            progressMarksStore = InMemoryProgressMarksStore(),
        )
        useCase = CloudPresencePlaytimeRefilingUseCase(
            gameDao = database.gameDao(),
            sessionDao = database.sessionDao(),
            dailyProgressDao = database.dailyProgressDao(),
            playerProfileDao = database.playerProfileDao(),
            cloudHistoricalDao = database.cloudHistoricalDao(),
            settings = settings,
            recoveryDao = database.playtimeRecoveryDao(),
            gamificationUpdater = updater,
            time = FixedTime,
            syncCoordinator = com.example.backlogium.work.SteamSyncCoordinator(),
            derivedStateWrites = DerivedStateWriteCoordinator(),
            transaction = RoomDatabaseTransactionScope(database),
        )
        kotlinx.coroutines.runBlocking {
            settings.clearCloudPresenceRefiling()
            database.playerProfileDao().upsert(PlayerProfile(steamId = ACCOUNT))
            database.gameDao().upsert(game())
        }
    }

    @After
    fun tearDown() {
        kotlinx.coroutines.runBlocking { settings.clearCloudPresenceRefiling() }
        database.close()
    }

    @Test
    fun applyNeverMovesASessionThatCarriesAnActiveFundedShare() = runTest {
        val id = database.sessionDao().insert(
            Session(
                appId = GAME,
                startAt = utc("2026-07-25T23:50:00Z"),
                endAt = utc("2026-07-26T00:10:00Z"),
                minutes = 35,
                open = false,
            ),
        )
        val contributionId = PlaytimeRecoveryIdentity.contributionId(ACCOUNT, GAME, 1L, 500L, 535L)
        database.playtimeRecoveryDao().insertContributionChecked(
            PlaytimeContribution(
                contributionId = contributionId,
                account = ACCOUNT,
                appId = GAME,
                baselineEpoch = 1L,
                fromMinutes = 500L,
                toMinutes = 535L,
                windowStartAt = utc("2026-07-25T23:50:00Z"),
                windowEndAt = utc("2026-07-26T00:10:00Z"),
                authorizedMinutes = 35L,
                revision = 1L,
                provenance = RecoveryProvenance.RETROACTIVE_PLAY,
                zone = "UTC",
                createdAt = 1L,
            ),
        )
        database.playtimeRecoveryDao().insertTimedPlacements(
            listOf(
                TimedContributionPlacement(
                    placementId = PlaytimeRecoveryIdentity.timedPlacementId(
                        contributionId, 1L, "ev-1", "2026-07-26", id,
                    ),
                    contributionId = contributionId,
                    account = ACCOUNT,
                    appId = GAME,
                    revision = 1L,
                    sessionId = id,
                    minutes = 35L,
                    creditedDate = "2026-07-26",
                    zone = "UTC",
                    evidenceQuality = RecoveryEvidenceQuality.CLOUD,
                    createdAt = 1L,
                ),
            ),
        )
        val before = database.sessionDao().getAll().single()

        val applied = useCase.apply(
            listOf(interval(utc("2026-07-26T00:00:00Z"), utc("2026-07-26T00:10:00Z"))),
        )

        assertEquals(CloudPresenceRefilingOperation.APPLIED, applied.operation)
        assertEquals("the funded session is not refilable", 0, applied.sessionsRefiled)
        // The funded placement is authoritative: its hosting session is never re-placed.
        assertEquals(before, database.sessionDao().getAll().single())
    }

    @Test
    fun reversalPreservesAFundedShareAcquiredOnACreatedRowAfterApply() = runTest {
        database.sessionDao().insert(
            Session(
                appId = GAME,
                startAt = utc("2026-07-25T23:50:00Z"),
                endAt = utc("2026-07-26T00:10:00Z"),
                minutes = 30,
                open = false,
            ),
        )
        val applied = useCase.apply(
            listOf(
                interval(utc("2026-07-25T23:55:00Z"), utc("2026-07-26T00:00:00Z")),
                interval(utc("2026-07-26T00:00:00Z"), utc("2026-07-26T00:10:00Z")),
            ),
        )
        assertEquals(CloudPresenceRefilingOperation.APPLIED, applied.operation)
        assertEquals(1, applied.sessionsRefiled)

        // An independent recovery upgrade later funds one of the re-file's created split rows.
        val created = database.sessionDao().getAll().first { it.startAt == utc("2026-07-26T00:00:00Z") }
        val contributionId = PlaytimeRecoveryIdentity.contributionId(ACCOUNT, GAME, 1L, 535L, 550L)
        database.playtimeRecoveryDao().insertContributionChecked(
            PlaytimeContribution(
                contributionId = contributionId,
                account = ACCOUNT,
                appId = GAME,
                baselineEpoch = 1L,
                fromMinutes = 535L,
                toMinutes = 550L,
                windowStartAt = utc("2026-07-26T00:00:00Z"),
                windowEndAt = utc("2026-07-26T00:10:00Z"),
                authorizedMinutes = 15L,
                revision = 1L,
                provenance = RecoveryProvenance.RETROACTIVE_PLAY,
                zone = "UTC",
                createdAt = 1L,
            ),
        )
        database.playtimeRecoveryDao().insertTimedPlacements(
            listOf(
                TimedContributionPlacement(
                    placementId = PlaytimeRecoveryIdentity.timedPlacementId(
                        contributionId, 1L, "upgrade-ev", "2026-07-26", created.id,
                    ),
                    contributionId = contributionId,
                    account = ACCOUNT,
                    appId = GAME,
                    revision = 1L,
                    sessionId = created.id,
                    minutes = 15L,
                    creditedDate = "2026-07-26",
                    zone = "UTC",
                    evidenceQuality = RecoveryEvidenceQuality.LOCAL,
                    createdAt = 1L,
                ),
            ),
        )
        check(database.playtimeRecoveryDao().conservationHolds(contributionId))

        val reversed = useCase.reverse()

        assertEquals(CloudPresenceRefilingOperation.REVERSED, reversed.operation)
        // The funded created row survives the reversal with its minutes intact; the unfunded rows
        // revert to the pre-apply ledger.
        val sessions = database.sessionDao().getAll()
        assertTrue("funded created row must survive", sessions.any { it.id == created.id && it.minutes == 20 })
        assertTrue(
            "original row is restored",
            sessions.any {
                it.startAt == utc("2026-07-25T23:50:00Z") &&
                    it.endAt == utc("2026-07-26T00:10:00Z") && it.minutes == 30
            },
        )
    }

    @Test
    fun reversalDoesNotResurrectMinutesARecoveryCorrectionAlreadyRemoved() = runTest {
        database.sessionDao().insert(
            Session(
                appId = GAME,
                startAt = utc("2026-07-25T23:50:00Z"),
                endAt = utc("2026-07-26T00:10:00Z"),
                minutes = 30,
                open = false,
            ),
        )
        val applied = useCase.apply(
            listOf(interval(utc("2026-07-26T00:00:00Z"), utc("2026-07-26T00:10:00Z"))),
        )
        assertEquals(1, applied.sessionsRefiled)

        // After apply, an independent recovery correction advances the game's contribution graph
        // and shrinks the refiled row (its share moved to another placement).
        val contributionId = PlaytimeRecoveryIdentity.contributionId(ACCOUNT, GAME, 1L, 500L, 530L)
        database.playtimeRecoveryDao().insertContributionChecked(
            PlaytimeContribution(
                contributionId = contributionId,
                account = ACCOUNT,
                appId = GAME,
                baselineEpoch = 1L,
                fromMinutes = 500L,
                toMinutes = 530L,
                windowStartAt = utc("2026-07-26T00:00:00Z"),
                windowEndAt = utc("2026-07-26T00:10:00Z"),
                authorizedMinutes = 30L,
                revision = 2L,
                provenance = RecoveryProvenance.RETROACTIVE_PLAY,
                zone = "UTC",
                createdAt = 1L,
            ),
        )
        val refiled = database.sessionDao().getAll().single()
        database.sessionDao().update(refiled.copy(minutes = 25))

        val reversed = useCase.reverse()

        assertEquals(CloudPresenceRefilingOperation.REVERSED, reversed.operation)
        val restored = database.sessionDao().getAll().single()
        assertEquals(
            "the correction's reduced shape is preserved, not overwritten by the pre-apply total",
            25,
            restored.minutes,
        )
    }

    private suspend fun interval(startAt: Long, endAt: Long): CloudPresenceInterval =
        CloudPresenceInterval(
            appId = GAME,
            gameName = "Portal",
            startAt = startAt,
            endAt = endAt,
            ongoing = false,
            coverage = CloudCoverageState.CONTINUOUS,
            observedUntil = null,
            coverageLapseFrom = null,
            coverageLapseRecoveredAt = null,
            mayHaveStartedBefore = false,
        )

    private fun game() = Game(
        appId = GAME,
        name = "Portal",
        iconUrl = "",
        playtimeForever = 535,
        playtime2Weeks = 0,
        lastPlaytime = 535,
        source = GameSource.STEAM_OWNED,
    )

    private object FixedTime : TimeProvider {
        override fun nowMillis(): Long = utc("2026-07-26T12:00:00Z")
        override fun zone(): ZoneOffset = ZoneOffset.UTC
        override fun today(): LocalDate = LocalDate.of(2026, 7, 26)
    }

    private companion object {
        const val ACCOUNT = "76561198000000001"
        const val GAME = 440L

        fun utc(value: String): Long = Instant.parse(value).toEpochMilli()
    }
}