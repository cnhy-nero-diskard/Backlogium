package com.example.backlogium.data.backup

import androidx.room.Room
import androidx.room.withTransaction
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.SettingsDataStore
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.GameRecoveryState
import com.example.backlogium.data.local.entity.PlaytimeContribution
import com.example.backlogium.data.local.entity.PlaytimeRecoveryJournal
import com.example.backlogium.data.local.entity.PlayerProfile
import com.example.backlogium.data.local.entity.RecoveryJournalStatus
import com.example.backlogium.data.local.entity.Session
import com.example.backlogium.data.local.entity.TimedContributionPlacement
import com.example.backlogium.data.local.entity.UnknownTimeAllocation
import com.example.backlogium.data.local.entity.UnknownTimeAllocationRecipe
import com.example.backlogium.data.repo.CredentialsProvider
import com.example.backlogium.data.repo.CredentialsState
import com.example.backlogium.data.repo.PlaytimeRecoveryCommitAdapter
import com.example.backlogium.data.repo.SessionActionWriter
import com.example.backlogium.domain.DerivedStateWriteCoordinator
import com.example.backlogium.domain.GamificationUpdater
import com.example.backlogium.domain.PlaytimeObservationCommitter
import com.example.backlogium.domain.SessionDiffer
import com.example.backlogium.domain.TimeProvider
import com.example.backlogium.gamification.RuleConfig
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * End-to-end version 3 recovery round trip (recover-missed-playtime tasks.md 8.1, 8.4, 8.5) through
 * the real export mapper, the real recovery store and the real merge engine, into a second database
 * that has never seen the recovery graph.
 */
@RunWith(RobolectricTestRunner::class)
class BackupRecoveryRoundTripTest {

    private lateinit var source: BacklogiumDatabase
    private lateinit var restored: BacklogiumDatabase
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @Before fun setUp() {
        source = newDatabase()
        restored = newDatabase()
    }

    @After fun tearDown() {
        source.close()
        restored.close()
    }

    @Test
    fun recoveryRoundTrip_preservesExactQuantityDatesZonesAndUncertainty() = runBlocking {
        source.gameDao().upsert(game(APP, "Game"))
        seedContribution(source)
        seedBaseline(source, highWater = 605, date = "2026-07-17")
        source.sessionDao().insert(session(APP, minutes = 60))

        val file = exportMapper(source).buildExport()

        assertEquals(3, file.formatVersion)
        val recovery = file.recovery!!
        assertNotNull(recovery.unknownAllocations)
        assertEquals(4, recovery.unknownAllocations!!.size)
        assertEquals(105L, recovery.unknownAllocations!!.sumOf { it.minutes })
        assertEquals(
            setOf("2026-07-14", "2026-07-15", "2026-07-16", "2026-07-17"),
            recovery.unknownAllocations!!.map { it.date }.toSet(),
        )
        assertTrue(recovery.unknownAllocations!!.all { it.zone == "UTC" })
        assertEquals(1L, recovery.contributions!!.single().revision)
        assertEquals("RETROACTIVE_PLAY", recovery.contributions!!.single().provenance)
        assertEquals(605L, recovery.observations!!.single().highWaterMinutes)

        // No credentials, and a bucket carries no gameplay session boundary.
        val encoded = json.encodeToString(BackupFile.serializer(), file)
        assertFalse(encoded.contains("apiKey"))
        assertFalse(encoded.contains("secret"))
        val firstBucket = json.parseToJsonElement(encoded).jsonObject
            .getValue("recovery").jsonObject
            .getValue("unknownAllocations").jsonArray[0].jsonObject
        assertFalse(firstBucket.containsKey("startAt"))
        assertFalse(firstBucket.containsKey("endAt"))

        mergeEngine(restored).merge(file, RuleConfig())

        val restoredRecovery = recoveryStore(restored).read(ACCOUNT)
        assertEquals(4, restoredRecovery.unknownAllocations!!.size)
        assertEquals(105L, restoredRecovery.unknownAllocations!!.sumOf { it.minutes })
        assertEquals(
            recovery.unknownAllocations!!.map { it.date },
            restoredRecovery.unknownAllocations!!.map { it.date },
        )
        assertEquals(605L, restoredRecovery.observations!!.single().highWaterMinutes)
        assertEquals(1L, restoredRecovery.contributions!!.single().revision)
        // The independent session cloud facts survive alongside the recovery graph.
        assertEquals(1, restored.sessionDao().getAll().size)
        assertEquals(60, restored.sessionDao().getAll().single().minutes)
        // The restore recomputes derived state from the merged raw data.
        assertFalse(restored.playerProfileDao().get()!!.pendingImportRecompute)
        assertTrue(restored.playerProfileDao().get()!!.totalXp > 0)
    }

    @Test
    fun newerSameAccountBaseline_isNotRolledBackByAnOlderRestore() = runBlocking {
        source.gameDao().upsert(game(APP, "Game"))
        seedBaseline(source, highWater = 605, date = "2026-07-17")
        val file = exportMapper(source).buildExport()

        // The receiving install already has a newer usable baseline for the same account.
        seedBaseline(restored, highWater = 700, date = "2026-07-18")

        mergeEngine(restored).merge(file, RuleConfig())

        val baseline = restored.playtimeRecoveryDao().state(ACCOUNT, APP)!!
        assertEquals(700L, baseline.highWaterMinutes)
        assertEquals("2026-07-18", baseline.lastObservationDate)
    }

    @Test
    fun interruptedImport_rollsRecoveryBackTogether() = runBlocking {
        source.gameDao().upsert(game(APP, "Game"))
        seedContribution(source)
        seedBaseline(source, highWater = 605, date = "2026-07-17")
        val file = exportMapper(source).buildExport()

        // A collection member with no parent collection violates the foreign key *after* the
        // recovery graph has been written inside the same transaction.
        val broken = file.copy(
            collectionMembers = listOf(BackupCollectionMember(collectionId = 999L, appId = APP, orderIndex = 0)),
        )

        val failure = runCatching { mergeEngine(restored).merge(broken, RuleConfig()) }
        assertTrue("expected the FK violation to propagate", failure.isFailure)

        assertTrue(restored.playtimeRecoveryDao().statesForAccount(ACCOUNT).isEmpty())
        assertTrue(restored.playtimeRecoveryDao().contributionsForGame(ACCOUNT, APP).isEmpty())
        assertTrue(restored.playtimeRecoveryDao().activeAllocations("c1").isEmpty())
        assertEquals(0, restored.sessionDao().getAll().size)
    }

    @Test
    fun timedShareAndLegacyRemainder_restoreLinkedNotDoubleCounted() = runBlocking {
        source.gameDao().upsert(game(APP, "Game"))
        val sessionId = source.sessionDao().insert(
            session(APP, minutes = 40, startAt = "2026-07-15T10:00:00Z", endAt = "2026-07-15T11:00:00Z"),
        )
        seedContribution(
            source,
            timed = listOf(
                TimedContributionPlacement(
                    placementId = "c1:1:t:ev:2026-07-15:$sessionId",
                    contributionId = "c1",
                    account = ACCOUNT,
                    appId = APP,
                    revision = 1,
                    sessionId = sessionId,
                    minutes = 35,
                    creditedDate = "2026-07-15",
                    zone = "UTC",
                    evidenceIdentity = "ev",
                    evidenceQuality = "LOCAL",
                    createdAt = 0,
                ),
            ),
        )
        seedBaseline(source, highWater = 605, date = "2026-07-17")

        val file = exportMapper(source).buildExport()
        val exportedShare = file.recovery!!.timedPlacements!!.single()
        assertEquals("2026-07-15T10:00:00Z", exportedShare.sessionStartAt)
        assertEquals("2026-07-15T11:00:00Z", exportedShare.sessionEndAt)

        mergeEngine(restored).merge(file, RuleConfig())

        // The 40-minute session (35 funded + 5 legacy) is one row; the funded 35 is re-linked to it.
        val restoredSession = restored.sessionDao().getAll().single()
        assertEquals(40, restoredSession.minutes)
        val share = restored.playtimeRecoveryDao().activeTimedSharesForSession(restoredSession.id).single()
        assertEquals(35L, share.minutes)
        // Canonical credited minutes = session minutes + dated allocations = 40 + 70 = 110.
        // A double count would read 140 (70 allocations plus the 70-minute share counted again).
        val allocationMinutes = restored.playtimeRecoveryDao().sumActiveDatedMinutes(ACCOUNT, APP)
        assertEquals(110L, restoredSession.minutes.toLong() + allocationMinutes)
        assertEquals(105L, restored.playtimeRecoveryDao().conservedAllocatedMinutes("c1"))
    }

    @Test
    fun detachedTimedShare_roundTripsWithoutInventingASession() = runBlocking {
        source.gameDao().upsert(game(APP, "Game"))
        seedContribution(
            source,
            timed = listOf(
                TimedContributionPlacement(
                    placementId = "c1:1:t:detached:2026-07-15:-",
                    contributionId = "c1",
                    account = ACCOUNT,
                    appId = APP,
                    revision = 1,
                    sessionId = null,
                    minutes = 35,
                    creditedDate = "2026-07-15",
                    zone = "UTC",
                    evidenceIdentity = "detached-evidence",
                    evidenceQuality = "LOCAL",
                    createdAt = 0,
                ),
            ),
        )
        seedBaseline(source, highWater = 605, date = "2026-07-17")

        val file = exportMapper(source).buildExport()
        assertTrue(BackupValidator.validate(file) is BackupValidationResult.Valid)
        assertNull(file.recovery!!.timedPlacements!!.single().sessionStartAt)
        assertNull(file.recovery!!.timedPlacements!!.single().sessionEndAt)

        mergeEngine(restored).merge(file, RuleConfig())

        val restoredShare = recoveryStore(restored).read(ACCOUNT).timedPlacements!!.single()
        assertNull(restoredShare.sessionStartAt)
        assertNull(restoredShare.sessionEndAt)
        assertEquals(105L, restored.playtimeRecoveryDao().conservedAllocatedMinutes("c1"))
        assertTrue(restored.sessionDao().getAll().isEmpty())

        val secondExport = exportMapper(restored).buildExport()
        assertTrue(BackupValidator.validate(secondExport) is BackupValidationResult.Valid)
        assertNull(secondExport.recovery!!.timedPlacements!!.single().sessionStartAt)
        assertNull(secondExport.recovery!!.timedPlacements!!.single().sessionEndAt)
    }

    @Test
    fun foreignBackupBaseline_remainsInactiveForConfiguredAccount() = runBlocking {
        val foreignAccount = "76561198000000001"
        val file = BackupFile(
            formatVersion = 3,
            exportedAt = "2026-07-17T12:00:00Z",
            identity = BackupIdentity(steamId64 = foreignAccount),
            ruleConfig = BackupRuleConfig(1, 100, 30, "ANY_GAME", 0, 5, 10, 20, 40, 80),
            games = listOf(BackupGame(appId = APP, name = "Foreign game", isGoal = false, backfillMinutes = 0)),
            achievements = emptyList(),
            sessions = emptyList(),
            dailyProgress = emptyList(),
            hltbData = emptyList(),
            librarySortPrefs = BackupLibrarySortPrefs(focus = "NAME", library = "PLAYTIME"),
            playerProfile = BackupPlayerProfile(0, 1, 0, 0, false),
            computed = BackupComputed(emptyList(), emptyList()),
            recovery = BackupRecovery(
                observations = listOf(
                    BackupRecoveryObservation(
                        account = foreignAccount,
                        appId = APP,
                        baselineEpoch = 1,
                        highWaterMinutes = 605,
                        lastObservationAt = "2026-07-17T12:00:00Z",
                        lastObservationDate = "2026-07-17",
                        lastObservationZone = "UTC",
                        clockState = "VALID",
                    ),
                ),
                contributions = listOf(
                    BackupContribution(
                        contributionId = "foreign-contribution",
                        account = foreignAccount,
                        appId = APP,
                        baselineEpoch = 1,
                        fromMinutes = 500,
                        toMinutes = 605,
                        windowStartAt = "2026-07-13T12:00:00Z",
                        windowEndAt = "2026-07-17T12:00:00Z",
                        authorizedMinutes = 105,
                        revision = 1,
                        provenance = "RETROACTIVE_PLAY",
                        zone = "UTC",
                        observedZone = "UTC",
                    ),
                ),
                timedPlacements = emptyList(),
                unknownAllocations = listOf(27L, 26L, 26L, 26L).mapIndexed { index, minutes ->
                    BackupUnknownAllocation(
                        placementId = "foreign-contribution:p$index",
                        contributionId = "foreign-contribution",
                        account = foreignAccount,
                        appId = APP,
                        revision = 1,
                        date = listOf("2026-07-14", "2026-07-15", "2026-07-16", "2026-07-17")[index],
                        minutes = minutes,
                        method = "MULTI_DAY_ESTIMATE",
                        zone = "UTC",
                        windowStartAt = "2026-07-13T12:00:00Z",
                        windowEndAt = "2026-07-17T12:00:00Z",
                    )
                },
                recipes = emptyList(),
                replacements = emptyList(),
            ),
        )
        assertTrue(BackupValidator.validate(file) is BackupValidationResult.Valid)

        val summary = mergeEngine(restored).merge(file, RuleConfig())

        assertTrue(summary.warnings.any { it.detail.contains("foreign recovery baseline") && it.detail.contains("kept inactive") })
        assertNull(restored.playtimeRecoveryDao().state(foreignAccount, APP))
        assertNull(restored.playtimeRecoveryDao().state(ACCOUNT, APP))
        assertEquals(105L, restored.playtimeRecoveryDao().conservedAllocatedMinutes("foreign-contribution"))
        assertEquals(foreignAccount, restored.playtimeRecoveryDao().contribution("foreign-contribution")!!.account)
        assertEquals(ACCOUNT, restored.playerProfileDao().get()!!.steamId)
    }

    @Test
    fun olderBackupAfterUpgrade_cannotResurrectRetiredSyntheticSession() = runBlocking {
        // The receiving install already retired the synthetic session via a replacement journal
        // whose portable tombstone names its natural key.
        seedLocalRecoveryWithSessionTombstone(restored)
        // The older (pre-upgrade) backup still carries the full synthetic session.
        val olderFile = olderFileWithSyntheticSession()

        mergeEngine(restored).merge(olderFile, RuleConfig())

        assertTrue(
            "a retired synthetic must not be recreated next to its upgraded placement",
            restored.sessionDao().getAll().none { it.startAt == SYNTH_START_MILLIS },
        )
        // Local funded recovery is retained, not erased, by the legacy-style import.
        assertFalse(restored.playtimeRecoveryDao().statesForAccount(ACCOUNT).isEmpty())
        assertFalse(restored.playtimeRecoveryDao().contributionsForGame(ACCOUNT, APP).isEmpty())
    }

    @Test
    fun partialOverlap_mergesToTheExactUnionOnRoundTrip() = runBlocking {
        // restored already funds (535..605]; the imported file funds (500..570].
        restored.gameDao().upsert(game(APP, "Game"))
        seedContribution(restored, from = 535, to = 605, contributionId = "cI")
        seedBaseline(restored, highWater = 605, date = "2026-07-17")
        source.gameDao().upsert(game(APP, "Game"))
        seedContribution(source, from = 500, to = 570, contributionId = "cL")
        seedBaseline(source, highWater = 570, date = "2026-07-16")

        val file = exportMapper(source).buildExport()
        mergeEngine(restored).merge(file, RuleConfig())

        val contributions = restored.playtimeRecoveryDao().contributionsForGame(ACCOUNT, APP)
        assertEquals(2, contributions.size)
        assertEquals(105L, contributions.sumOf { it.authorizedMinutes })
        assertEquals(
            listOf(500L, 605L),
            listOf(
                contributions.minOf { it.fromMinutes },
                contributions.maxOf { it.toMinutes },
            ),
        )
        // The newer local baseline (605 at 07-17) survives the import.
        assertEquals(605L, restored.playtimeRecoveryDao().state(ACCOUNT, APP)!!.highWaterMinutes)
    }

    @Test
    fun importedBaseline_setsTheDetectorPairSoNothingIsReCredited() = runBlocking {
        source.gameDao().upsert(game(APP, "Game"))
        seedContribution(source)
        seedBaseline(source, highWater = 605, date = "2026-07-17")
        val file = exportMapper(source).buildExport()

        mergeEngine(restored).merge(file, RuleConfig())

        // A restore puts Game.lastPlaytime in step with the authoritative recovery high-water, so
        // a future Steam read at 605 diffs to zero instead of re-crediting the imported quantity.
        assertEquals(605, restored.gameDao().getById(APP)!!.lastPlaytime)
        assertEquals(605L, restored.playtimeRecoveryDao().state(ACCOUNT, APP)!!.highWaterMinutes)
    }

    @Test
    fun restoredPositiveEpoch_unchangedThenIncreasingSteamReadAndRepeatedImportStayConserved() = runBlocking {
        source.gameDao().upsert(game(APP, "Game"))
        seedContribution(source)
        seedBaseline(source, highWater = 605, date = "2026-07-17")
        val backup = exportMapper(source).buildExport()

        // A pre-existing successful local poll supplies the ordinary diff boundary. Restore must
        // adopt the recovery high-water into Game.lastPlaytime before the next Steam read.
        restored.playerProfileDao().upsert(PlayerProfile(steamId = ACCOUNT, lastSyncAt = FixedTime.nowMillis()))
        mergeEngine(restored).merge(backup, RuleConfig())

        val dao = restored.playtimeRecoveryDao()
        val restoredBaseline = dao.state(ACCOUNT, APP)!!
        assertEquals(1L, restoredBaseline.baselineEpoch)
        assertEquals(605L, restoredBaseline.highWaterMinutes)
        assertEquals(605, restored.gameDao().getById(APP)!!.lastPlaytime)
        assertEquals(105L, dao.conservedAllocatedMinutes("c1"))
        assertTrue(dao.validateGraph("c1"))

        val committer = observationCommitter(restored)
        val unchangedAt = iso("2026-07-17T13:00:00Z")
        val unchanged = restored.withTransaction {
            committer.commit(
                observed = listOf(observed(APP, 605)),
                observedPlayAt = unchangedAt,
                syncedAt = unchangedAt,
                expectedAccount = ACCOUNT,
            )
        }
        assertFalse(unchanged.recordedPlay)
        assertEquals(0, unchanged.playedDeltaByAppId[APP] ?: 0)
        assertEquals(0, unchanged.recoveredContributions)
        assertEquals(1, dao.contributionsForGame(ACCOUNT, APP).size)
        assertEquals(605L, dao.state(ACCOUNT, APP)!!.highWaterMinutes)
        assertEquals(605, restored.gameDao().getById(APP)!!.lastPlaytime)
        assertTrue(restored.sessionDao().getAll().isEmpty())

        // The worker records the successful observation boundary after the raw commit. A later
        // authoritative increase must be measured from the restored high-water, not from zero.
        restored.playerProfileDao().updateSyncStatus(unchangedAt, null)
        val increasedAt = iso("2026-07-17T13:30:00Z")
        val increased = restored.withTransaction {
            committer.commit(
                observed = listOf(observed(APP, 630)),
                observedPlayAt = increasedAt,
                syncedAt = increasedAt,
                expectedAccount = ACCOUNT,
            )
        }
        assertTrue(increased.recordedPlay)
        assertEquals(25, increased.playedDeltaByAppId[APP])
        assertEquals(1, increased.recoveredContributions)

        val afterIncrease = dao.contributionsForGame(ACCOUNT, APP)
        assertEquals(2, afterIncrease.size)
        assertEquals(130L, afterIncrease.sumOf { it.authorizedMinutes })
        afterIncrease.forEach { contribution ->
            assertTrue(dao.validateGraph(contribution.contributionId))
            assertEquals(contribution.authorizedMinutes, dao.conservedAllocatedMinutes(contribution.contributionId))
        }
        assertEquals(130L, afterIncrease.sumOf { dao.conservedAllocatedMinutes(it.contributionId) })
        assertEquals(630L, dao.state(ACCOUNT, APP)!!.highWaterMinutes)
        assertEquals(630, restored.gameDao().getById(APP)!!.lastPlaytime)
        val sessionsAfterIncrease = restored.sessionDao().getAll()

        // Replaying the older, valid backup must neither re-fund its original range nor roll the
        // detector pair back over the newer local observation.
        repeat(2) {
            mergeEngine(restored).merge(backup, RuleConfig())
            val current = dao.contributionsForGame(ACCOUNT, APP)
            assertEquals(2, current.size)
            assertEquals(130L, current.sumOf { it.authorizedMinutes })
            assertEquals(130L, current.sumOf { dao.conservedAllocatedMinutes(it.contributionId) })
            assertEquals(630L, dao.state(ACCOUNT, APP)!!.highWaterMinutes)
            assertEquals(630, restored.gameDao().getById(APP)!!.lastPlaytime)
            assertEquals(sessionsAfterIncrease, restored.sessionDao().getAll())
        }
    }

    @Test
    fun legacyV1AndV2Restore_firstSteamReadBaselinesWithoutLifetimeCredit() = runBlocking {
        for ((version, database) in listOf(1 to source, 2 to restored)) {
            val backup = legacyBackup(version)
            assertTrue(BackupValidator.validate(backup) is BackupValidationResult.Valid)
            mergeEngine(database).merge(backup, RuleConfig())
            val legacySession = database.sessionDao().getAll().single()

            val committer = observationCommitter(database)
            val firstReadAt = iso("2026-07-17T13:00:00Z")
            val firstRead = database.withTransaction {
                committer.commit(
                    observed = listOf(observed(APP, 605)),
                    observedPlayAt = firstReadAt,
                    syncedAt = firstReadAt,
                    expectedAccount = ACCOUNT,
                )
            }
            assertFalse("legacy history must not become a new lifetime delta", firstRead.recordedPlay)
            assertEquals(0, firstRead.playedDeltaByAppId[APP] ?: 0)
            assertTrue(database.playtimeRecoveryDao().contributionsForGame(ACCOUNT, APP).isEmpty())
            assertEquals(605, database.gameDao().getById(APP)!!.lastPlaytime)
            assertEquals(legacySession, database.sessionDao().findByNaturalKey(APP, legacySession.startAt, legacySession.endAt))
            assertEquals(1, database.sessionDao().getAll().count { it.id == legacySession.id })

            database.playerProfileDao().updateSyncStatus(firstReadAt, null)
            val increaseAt = iso("2026-07-17T13:30:00Z")
            val increase = database.withTransaction {
                committer.commit(
                    observed = listOf(observed(APP, 625)),
                    observedPlayAt = increaseAt,
                    syncedAt = increaseAt,
                    expectedAccount = ACCOUNT,
                )
            }
            assertTrue("only play newer than the legacy baseline is eligible", increase.recordedPlay)
            assertEquals(20, increase.playedDeltaByAppId[APP])
            assertEquals(20L, database.playtimeRecoveryDao()
                .contributionsForGame(ACCOUNT, APP).sumOf { it.authorizedMinutes })
            assertEquals(625L, database.playtimeRecoveryDao().state(ACCOUNT, APP)!!.highWaterMinutes)
            assertEquals(625, database.gameDao().getById(APP)!!.lastPlaytime)
            assertEquals(1, database.sessionDao().getAll().count { it.id == legacySession.id })
        }
    }

    @Test
    fun tenYearCompactRecipe_exportsAndRestoresLosslessly() = runBlocking {
        source.gameDao().upsert(game(APP, "Game"))
        val width = 1_000_000L
        val eligibleCount = 3_652
        val quotient = width / eligibleCount
        val remainder = width % eligibleCount
        source.playtimeRecoveryDao().commitAllocatedGraph(
            contribution = PlaytimeContribution(
                contributionId = "c-recipe",
                account = ACCOUNT,
                appId = APP,
                baselineEpoch = 1,
                fromMinutes = 0,
                toMinutes = width,
                windowEndAt = iso("2026-07-17T12:00:00Z"),
                authorizedMinutes = width,
                revision = 1,
                provenance = "RETROACTIVE_PLAY",
                zone = "UTC",
                createdAt = 0,
            ),
            timed = emptyList(),
            allocations = emptyList(),
            recipe = UnknownTimeAllocationRecipe(
                contributionId = "c-recipe",
                account = ACCOUNT,
                appId = APP,
                revision = 1,
                method = "MULTI_DAY_ESTIMATE",
                quotient = quotient,
                remainderCount = remainder,
                dateStart = "2026-01-01",
                dateEnd = "2035-12-31",
                eligibleCount = eligibleCount,
                zone = "UTC",
                pending = false,
                materializedMinutes = 0,
                updatedAt = 0,
            ),
        )

        val file = exportMapper(source).buildExport()
        val shipped = file.recovery!!.recipes!!.single()
        assertEquals(quotient, shipped.quotient)
        assertEquals(remainder, shipped.remainderCount)
        assertEquals(eligibleCount, shipped.eligibleCount)
        assertEquals(0L, shipped.materializedMinutes)

        mergeEngine(restored).merge(file, RuleConfig())

        val restoredRecipe = restored.playtimeRecoveryDao().recipe("c-recipe")!!
        assertEquals(quotient, restoredRecipe.quotient)
        assertEquals(remainder, restoredRecipe.remainderCount)
        assertEquals(eligibleCount, restoredRecipe.eligibleCount)
        assertEquals(width, restored.playtimeRecoveryDao().conservedAllocatedMinutes("c-recipe"))
    }

    // ------------------------------------------------------------------ fixtures

    private suspend fun seedBaseline(
        db: BacklogiumDatabase,
        highWater: Long,
        date: String,
    ) {
        db.playtimeRecoveryDao().upsertState(
            GameRecoveryState(
                account = ACCOUNT,
                appId = APP,
                baselineEpoch = 1,
                highWaterMinutes = highWater,
                lastObservationAt = iso(date + "T12:00:00Z"),
                lastObservationDate = date,
                lastObservationZone = "UTC",
                clockState = "VALID",
                updatedAt = 0,
            ),
        )
    }

    private suspend fun seedContribution(
        db: BacklogiumDatabase,
        from: Long = 500,
        to: Long = 605,
        contributionId: String = "c1",
        timed: List<TimedContributionPlacement> = emptyList(),
    ) {
        val width = to - from
        val timedMinutes = timed.sumOf { it.minutes }
        require(timedMinutes <= width)
        db.playtimeRecoveryDao().commitAllocatedGraph(
            contribution = PlaytimeContribution(
                contributionId = contributionId,
                account = ACCOUNT,
                appId = APP,
                baselineEpoch = 1,
                fromMinutes = from,
                toMinutes = to,
                windowStartAt = iso("2026-07-13T12:00:00Z"),
                windowEndAt = iso("2026-07-17T12:00:00Z"),
                authorizedMinutes = width,
                revision = 1,
                provenance = "RETROACTIVE_PLAY",
                zone = "UTC",
                observedZone = "UTC",
                createdAt = 0,
            ),
            timed = timed,
            allocations = distributeDates(width - timedMinutes, contributionId),
            recipe = null,
        )
    }

    /** Exact earliest-date-first shares over four dates, so a width always conserves. */
    private fun distributeDates(width: Long, contributionId: String): List<UnknownTimeAllocation> {
        val dates = listOf("2026-07-14", "2026-07-15", "2026-07-16", "2026-07-17")
        val q = width / dates.size
        val r = (width % dates.size).toInt()
        return dates.mapIndexedNotNull { index, date ->
            val minutes = if (index < r) q + 1 else q
            if (minutes <= 0L) null else allocation(date, minutes, contributionId)
        }
    }

    private fun allocation(date: String, minutes: Long, contributionId: String = "c1") = UnknownTimeAllocation(
        placementId = "$contributionId:1:u:$date",
        contributionId = contributionId,
        account = ACCOUNT,
        appId = APP,
        revision = 1,
        date = date,
        minutes = minutes,
        method = "MULTI_DAY_ESTIMATE",
        zone = "UTC",
        windowStartAt = iso("2026-07-13T12:00:00Z"),
        windowEndAt = iso("2026-07-17T12:00:00Z"),
        pendingDate = false,
        createdAt = 0,
    )

    private suspend fun seedLocalRecoveryWithSessionTombstone(db: BacklogiumDatabase) {
        db.playtimeRecoveryDao().upsertState(
            GameRecoveryState(
                account = ACCOUNT,
                appId = APP,
                baselineEpoch = 1,
                highWaterMinutes = 605,
                lastObservationAt = iso("2026-07-17T12:00:00Z"),
                lastObservationDate = "2026-07-17",
                lastObservationZone = "UTC",
                clockState = "VALID",
                updatedAt = 0,
            ),
        )
        db.playtimeRecoveryDao().commitAllocatedGraph(
            contribution = PlaytimeContribution(
                contributionId = "cX",
                account = ACCOUNT,
                appId = APP,
                baselineEpoch = 1,
                fromMinutes = 0,
                toMinutes = 120,
                windowStartAt = iso("2026-07-09T12:00:00Z"),
                windowEndAt = iso("2026-07-10T12:00:00Z"),
                authorizedMinutes = 120,
                revision = 1,
                provenance = "RETROACTIVE_PLAY",
                zone = "UTC",
                createdAt = 0,
            ),
            timed = emptyList(),
            allocations = listOf(allocation("2026-07-10", 120, "cX")),
            recipe = null,
        )
        db.playtimeRecoveryDao().upsertJournal(
            PlaytimeRecoveryJournal(
                operationId = "retire-op",
                contributionId = "cX",
                account = ACCOUNT,
                appId = APP,
                currentRevision = 1,
                affectedDatesCsv = "2026-07-10",
                replacedPlacementIdsCsv = null,
                replacedRecipeJson = null,
                payloadJson = RecoveryBackupSessionTombstones.encode(
                    listOf(BackupSessionTombstone(APP, "2026-07-10T00:00:00Z", "2026-07-10T02:00:00Z")),
                ),
                derivedStatus = RecoveryJournalStatus.FINALIZED,
                createdAt = 0,
                updatedAt = 0,
            ),
        )
    }

    /** A pre-upgrade v3 backup that still carries the synthetic session the local install retired. */
    private fun olderFileWithSyntheticSession() = BackupFile(
        formatVersion = 3,
        exportedAt = "2026-07-09T12:00:00Z",
        identity = BackupIdentity(steamId64 = ACCOUNT),
        ruleConfig = BackupRuleConfig(1, 100, 30, "ANY_GAME", 0, 5, 10, 20, 40, 80),
        games = listOf(BackupGame(appId = APP, name = "Game", isGoal = false, backfillMinutes = 0)),
        achievements = emptyList(),
        sessions = listOf(
            BackupSession(
                appId = APP,
                startAt = "2026-07-10T00:00:00Z",
                endAt = "2026-07-10T02:00:00Z",
                minutes = 120,
                cloudContribution = BackupCloudContribution(
                    BackupContributionState.UNKNOWN,
                    BackupContributionState.UNKNOWN,
                ),
            ),
        ),
        dailyProgress = emptyList(),
        hltbData = emptyList(),
        librarySortPrefs = BackupLibrarySortPrefs(focus = "NAME", library = "PLAYTIME"),
        playerProfile = BackupPlayerProfile(totalXp = 0, level = 1, currentStreak = 0, longestStreak = 0, playtimeBackfilled = false),
        computed = BackupComputed(emptyList(), emptyList()),
        recovery = BackupRecovery(
            observations = emptyList(),
            contributions = emptyList(),
            timedPlacements = emptyList(),
            unknownAllocations = emptyList(),
            recipes = emptyList(),
            replacements = emptyList(),
        ),
    )

    private fun newDatabase() = Room.inMemoryDatabaseBuilder(
        RuntimeEnvironment.getApplication(), BacklogiumDatabase::class.java,
    ).allowMainThreadQueries().build()

    private fun exportMapper(db: BacklogiumDatabase) = BackupExportMapper(
        gameDao = db.gameDao(),
        achievementDao = db.achievementDao(),
        sessionDao = db.sessionDao(),
        dailyProgressDao = db.dailyProgressDao(),
        hltbDataDao = db.hltbDataDao(),
        playerProfileDao = db.playerProfileDao(),
        collectionDao = db.collectionDao(),
        hiddenGameDao = db.hiddenGameDao(),
        excludedSharedGameDao = db.excludedSharedGameDao(),
        recoveryStore = recoveryStore(db),
        settings = SettingsDataStore(RuntimeEnvironment.getApplication()),
        credentials = ConfiguredCredentials,
        time = FixedTime,
    )

    private fun mergeEngine(db: BacklogiumDatabase) = BackupMergeEngine(
        gameDao = db.gameDao(),
        sessionDao = db.sessionDao(),
        dailyProgressDao = db.dailyProgressDao(),
        hltbDataDao = db.hltbDataDao(),
        achievementDao = db.achievementDao(),
        playerProfileDao = db.playerProfileDao(),
        collectionDao = db.collectionDao(),
        hiddenGameDao = db.hiddenGameDao(),
        excludedSharedGameDao = db.excludedSharedGameDao(),
        gamificationUpdater = GamificationUpdater(
            db.sessionDao(),
            db.dailyProgressDao(),
            db.playerProfileDao(),
            db.hltbDataDao(),
            db.achievementDao(),
            db.gameDao(),
            db.hiddenGameDao(),
        ),
        time = FixedTime,
        derivedStateWrites = DerivedStateWriteCoordinator(),
        transaction = RoomDatabaseTransactionScope(db),
        recoveryStore = recoveryStore(db),
        credentials = ConfiguredCredentials,
    )

    private fun recoveryStore(db: BacklogiumDatabase) =
        DaoRecoveryBackupStore(db.playtimeRecoveryDao(), db.sessionDao())

    private fun observationCommitter(db: BacklogiumDatabase) = PlaytimeObservationCommitter(
        gameDao = db.gameDao(),
        sessionDao = db.sessionDao(),
        dailyProgressDao = db.dailyProgressDao(),
        profileDao = db.playerProfileDao(),
        hiddenGameDao = db.hiddenGameDao(),
        differ = SessionDiffer(),
        time = FixedTime,
        sessionActionWriter = SessionActionWriter(
            db.sessionDao(), db.dailyProgressDao(), db.hiddenGameDao(), FixedTime,
        ),
        recoveryAdapter = PlaytimeRecoveryCommitAdapter(db.playtimeRecoveryDao(), db.sessionDao()),
    )

    private fun observed(appId: Long, playtime: Int) =
        PlaytimeObservationCommitter.ObservedGame(appId, "Game", "", playtime, 0)

    private fun legacyBackup(version: Int) = BackupFile(
        formatVersion = version,
        exportedAt = "2026-07-17T12:00:00Z",
        identity = BackupIdentity(steamId64 = ACCOUNT),
        ruleConfig = BackupRuleConfig(1, 100, 30, "ANY_GAME", 0, 5, 10, 20, 40, 80),
        games = listOf(BackupGame(appId = APP, name = "Game", isGoal = false, backfillMinutes = 0)),
        achievements = emptyList(),
        sessions = listOf(
            BackupSession(
                appId = APP,
                startAt = "2026-07-16T10:00:00Z",
                endAt = "2026-07-16T11:00:00Z",
                minutes = 60,
                cloudContribution = if (version == 1) null else BackupCloudContribution(
                    BackupContributionState.UNKNOWN,
                    BackupContributionState.UNKNOWN,
                ),
            ),
        ),
        dailyProgress = emptyList(),
        hltbData = emptyList(),
        librarySortPrefs = BackupLibrarySortPrefs(focus = "NAME", library = "PLAYTIME"),
        playerProfile = BackupPlayerProfile(0, 1, 0, 0, false),
        computed = BackupComputed(emptyList(), emptyList()),
        recovery = null,
    )

    private fun game(appId: Long, name: String) = Game(
        appId = appId,
        name = name,
        iconUrl = "",
        playtimeForever = 0,
        playtime2Weeks = 0,
        lastPlaytime = 0,
    )

    private fun session(
        appId: Long,
        minutes: Int,
        startAt: String = "2026-07-17T10:00:00Z",
        endAt: String = "2026-07-17T11:00:00Z",
    ) = Session(
        appId = appId,
        startAt = iso(startAt),
        endAt = iso(endAt),
        minutes = minutes,
        open = false,
    )

    private fun iso(text: String): Long = java.time.Instant.parse(text).toEpochMilli()

    private object ConfiguredCredentials : CredentialsProvider {
        override suspend fun currentCredentials() =
            CredentialsState.Configured(apiKey = "key", steamId = ACCOUNT)
    }

    private object FixedTime : TimeProvider {
        override fun nowMillis(): Long = java.time.Instant.parse("2026-07-17T12:00:00Z").toEpochMilli()
        override fun zone(): ZoneId = ZoneId.of("UTC")
        override fun today(): LocalDate = LocalDate.parse("2026-07-17")
    }

    private companion object {
        const val ACCOUNT = "76561198000000000"
        const val APP = 440L
        val SYNTH_START_MILLIS: Long = java.time.Instant.parse("2026-07-10T00:00:00Z").toEpochMilli()
    }
}
