package com.example.backlogium.data.backup

import com.example.backlogium.data.local.dao.AchievementCounts
import com.example.backlogium.data.local.dao.AchievementDao
import com.example.backlogium.data.local.dao.AchievementRarity
import com.example.backlogium.data.local.dao.AchievementUnlock
import com.example.backlogium.data.local.dao.CollectionDao
import com.example.backlogium.data.local.dao.ExcludedSharedGameDao
import com.example.backlogium.data.local.dao.DailyProgressDao
import com.example.backlogium.data.local.dao.GameDao
import com.example.backlogium.domain.GameSource
import com.example.backlogium.data.local.dao.GameSessionCounts
import com.example.backlogium.domain.GameRecencyState
import com.example.backlogium.domain.LibraryRecency
import com.example.backlogium.data.local.dao.GameSessionInstant
import com.example.backlogium.data.local.dao.GameTrackedMinutes
import com.example.backlogium.data.local.dao.HltbDataDao
import com.example.backlogium.data.local.dao.PlayerProfileDao
import com.example.backlogium.data.local.dao.SessionDao
import com.example.backlogium.data.local.entity.Achievement
import com.example.backlogium.data.local.entity.Collection
import com.example.backlogium.data.local.entity.CollectionMember
import com.example.backlogium.data.local.entity.DailyProgress
import com.example.backlogium.data.local.entity.ExcludedSharedGame
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.HltbData
import com.example.backlogium.data.local.entity.HltbDataOrigin
import com.example.backlogium.data.local.entity.HltbMatchStatus
import com.example.backlogium.data.local.entity.PlayerProfile
import com.example.backlogium.data.local.entity.Session
import com.example.backlogium.domain.CollectionMode
import com.example.backlogium.domain.CollectionSort
import com.example.backlogium.domain.GamificationUpdater
import com.example.backlogium.domain.TimeProvider
import com.example.backlogium.gamification.RuleConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * Covers the merge engine's core invariants (tasks.md 3.6): no double-counted/duplicated
 * sessions, backfill of non-overlapping data, the achievement rarity snapshot protection, the
 * longest-streak high-water mark, and that aggregates are always recomputed rather than trusted
 * from the imported file.
 */
class BackupMergeEngineTest {

    private data class Harness(
        val engine: BackupMergeEngine,
        val gameDao: FakeGameDao,
        val sessionDao: FakeSessionDao,
        val profileDao: FakePlayerProfileDao,
        val collectionDao: FakeCollectionDao,
        val achievementDao: FakeAchievementDao,
        val excludedDao: FakeExcludedSharedGameDao,
    )

    private fun newEngine(
        games: MutableMap<Long, Game> = mutableMapOf(),
        sessions: MutableList<Session> = mutableListOf(),
        days: MutableMap<String, DailyProgress> = mutableMapOf(),
        hltb: MutableMap<Long, HltbData> = mutableMapOf(),
        achievements: MutableList<Achievement> = mutableListOf(),
        profile: PlayerProfile? = null,
        collections: MutableMap<Long, Collection> = mutableMapOf(),
        today: LocalDate = LocalDate.parse("2026-07-17"),
        nowMillis: Long = 0L,
    ): Harness {
        val gameDao = FakeGameDao(games)
        val sessionDao = FakeSessionDao(sessions)
        val dailyProgressDao = FakeDailyProgressDao(days)
        val hltbDataDao = FakeHltbDataDao(hltb)
        val achievementDao = FakeAchievementDao(achievements)
        val profileDao = FakePlayerProfileDao(profile)
        val collectionDao = FakeCollectionDao(collections)
        val excludedDao = FakeExcludedSharedGameDao()
        val time = FixedTimeProvider(today, nowMillis)
        val gamificationUpdater = GamificationUpdater(
            sessionDao, dailyProgressDao, profileDao, hltbDataDao, achievementDao, gameDao,
        )
        val engine = BackupMergeEngine(
            gameDao, sessionDao, dailyProgressDao, hltbDataDao, achievementDao, profileDao,
            collectionDao, excludedDao, gamificationUpdater, time,
        )
        return Harness(engine, gameDao, sessionDao, profileDao, collectionDao, achievementDao, excludedDao)
    }

    private fun baseFile(
        sessions: List<BackupSession> = emptyList(),
        achievements: List<BackupAchievement> = emptyList(),
        games: List<BackupGame> = emptyList(),
        longestStreak: Int = 0,
        totalXp: Int = 999_999, // deliberately implausible, to prove it's never trusted
        currentStreak: Int = 999,
        playtimeBackfilled: Boolean = false,
        collections: List<BackupCollection> = emptyList(),
        collectionMembers: List<BackupCollectionMember> = emptyList(),
        excludedSharedGames: List<BackupExcludedSharedGame> = emptyList(),
        hltbData: List<BackupHltbData> = emptyList(),
    ) = BackupFile(
        exportedAt = "2026-07-01T00:00:00Z",
        identity = BackupIdentity(steamId64 = "1"),
        ruleConfig = RuleConfig().toBackupForTest(),
        games = games,
        achievements = achievements,
        sessions = sessions,
        dailyProgress = emptyList(),
        hltbData = hltbData,
        librarySortPrefs = BackupLibrarySortPrefs(focus = "NAME", library = "PLAYTIME"),
        playerProfile = BackupPlayerProfile(
            totalXp = totalXp,
            level = 99,
            currentStreak = currentStreak,
            longestStreak = longestStreak,
            playtimeBackfilled = playtimeBackfilled,
        ),
        computed = BackupComputed(emptyList(), emptyList()),
        collections = collections,
        collectionMembers = collectionMembers,
        excludedSharedGames = excludedSharedGames,
    )

    @Test
    fun hltbImportKeepsBackupGatheredAtAndOrigin() = runTest {
        val stored = mutableMapOf<Long, HltbData>()
        val harness = newEngine(hltb = stored, nowMillis = 9_999L)
        val file = baseFile(
            games = listOf(
                BackupGame(appId = 620L, name = "Portal 2", isGoal = false, backfillMinutes = 0),
            ),
            hltbData = listOf(
                BackupHltbData(
                    appId = 620L,
                    hltbId = 42L,
                    mainStoryMinutes = 300,
                    mainExtraMinutes = 450,
                    completionistMinutes = 600,
                    allStylesMinutes = 480,
                    matchStatus = HltbMatchStatus.RESOLVED.name,
                    fetchedAt = 1_234L,
                    origin = HltbDataOrigin.MANUAL.name,
                ),
            ),
        )

        harness.engine.merge(file, RuleConfig())

        assertEquals(1_234L, stored.getValue(620L).fetchedAt)
        assertEquals(HltbDataOrigin.MANUAL, stored.getValue(620L).origin)
    }

    @Test
    fun restore_preservesSharedSourceAndStickyExclusion() = runTest {
        val excludedAt = "2026-06-20T12:00:00Z"
        val harness = newEngine()
        val file = baseFile(
            games = listOf(
                BackupGame(
                    appId = 620L,
                    name = "Portal 2",
                    isGoal = true,
                    backfillMinutes = 25,
                    source = "FAMILY_SHARED",
                ),
            ),
            excludedSharedGames = listOf(
                BackupExcludedSharedGame(appId = 620L, name = "Portal 2", excludedAt = excludedAt),
            ),
        )

        harness.engine.merge(file, RuleConfig())

        assertEquals(GameSource.FAMILY_SHARED, harness.gameDao.getById(620L)?.source)
        assertTrue(harness.excludedDao.isExcluded(620L))
    }

    @Test
    fun restore_insertingUnknownGames_recordsNoArrivalAndNoReturn() = runTest {
        // Restoring many games is not many acquisitions.
        val harness = newEngine(nowMillis = 1_700_000_000_000L)
        val file = baseFile(
            games = listOf(
                BackupGame(appId = 1L, name = "Game 1", isGoal = false, backfillMinutes = 0),
                BackupGame(appId = 2L, name = "Game 2", isGoal = false, backfillMinutes = 0),
            ),
        )

        harness.engine.merge(file, RuleConfig())

        harness.gameDao.getAll().forEach { game ->
            assertNull(game.firstSeenAt)
            assertNull(game.lastPlayedAt)
            assertNull(game.returnedToPlayAt)
        }
    }

    @Test
    fun restore_carriesTheRecencyTimesTheBackupRecorded() = runTest {
        val arrivedAt = 1_699_000_000_000L
        val playedAt = 1_699_500_000_000L
        val returnedAt = 1_699_400_000_000L
        val harness = newEngine(nowMillis = 1_700_000_000_000L)
        val file = baseFile(
            games = listOf(
                BackupGame(
                    appId = 1L,
                    name = "Game 1",
                    isGoal = false,
                    backfillMinutes = 0,
                    firstSeenAt = arrivedAt.toIso8601(),
                    lastPlayedAt = playedAt.toIso8601(),
                    returnedToPlayAt = returnedAt.toIso8601(),
                ),
            ),
        )

        harness.engine.merge(file, RuleConfig())

        val restored = harness.gameDao.getById(1L)!!
        assertEquals(arrivedAt, restored.firstSeenAt)
        assertEquals(playedAt, restored.lastPlayedAt)
        assertEquals(returnedAt, restored.returnedToPlayAt)
    }

    @Test
    fun restore_backupPredatingTheFields_importsWithThemAbsent() = runTest {
        val harness = newEngine(
            games = mutableMapOf(1L to testGame(1L)),
            nowMillis = 1_700_000_000_000L,
        )
        val file = baseFile(
            games = listOf(BackupGame(appId = 1L, name = "Game 1", isGoal = true, backfillMinutes = 30)),
        )

        harness.engine.merge(file, RuleConfig())

        val merged = harness.gameDao.getById(1L)!!
        assertNull(merged.firstSeenAt)
        assertNull(merged.returnedToPlayAt)
        assertEquals(30, merged.backfillMinutes)
    }

    /**
     * add-shared-game-playtime-and-filter: a family-shared game's manual playtime estimate must
     * survive a backup round-trip, both for a fresh insert and for an update against an existing
     * row, and an older backup with no `manualSharedMinutes` field must default to 0 without
     * failing.
     */
    @Test
    fun restore_manualSharedMinutesSurvivesInsert() = runTest {
        val harness = newEngine(games = mutableMapOf(), nowMillis = 1_700_000_000_000L)
        val file = baseFile(
            games = listOf(
                BackupGame(
                    appId = 441L,
                    name = "Borrowed Game",
                    isGoal = false,
                    backfillMinutes = 0,
                    source = "FAMILY_SHARED",
                    manualSharedMinutes = 90,
                ),
            ),
        )

        harness.engine.merge(file, RuleConfig())

        assertEquals(90, harness.gameDao.getById(441L)?.manualSharedMinutes)
    }

    @Test
    fun restore_manualSharedMinutesSurvivesUpdate() = runTest {
        val harness = newEngine(
            games = mutableMapOf(441L to testGame(441L).copy(source = GameSource.FAMILY_SHARED)),
            nowMillis = 1_700_000_000_000L,
        )
        val file = baseFile(
            games = listOf(
                BackupGame(
                    appId = 441L,
                    name = "Borrowed Game",
                    isGoal = false,
                    backfillMinutes = 0,
                    source = "FAMILY_SHARED",
                    manualSharedMinutes = 90,
                ),
            ),
        )

        harness.engine.merge(file, RuleConfig())

        assertEquals(90, harness.gameDao.getById(441L)?.manualSharedMinutes)
    }

    @Test
    fun restore_backupPredatingManualSharedMinutesPreservesTheLocalEstimate() = runTest {
        val harness = newEngine(
            games = mutableMapOf(
                441L to testGame(441L).copy(source = GameSource.FAMILY_SHARED, manualSharedMinutes = 45),
            ),
            nowMillis = 1_700_000_000_000L,
        )
        val file = baseFile(
            games = listOf(
                BackupGame(appId = 441L, name = "Borrowed Game", isGoal = false, backfillMinutes = 0),
            ),
        )

        harness.engine.merge(file, RuleConfig())

        assertEquals(
            "an absent field means the backup has no opinion, same as source/recency -- not a zero",
            45,
            harness.gameDao.getById(441L)?.manualSharedMinutes,
        )
    }

    /**
     * An owned row must never carry a manual estimate: a fresh restore of an owned backup with
     * a nonzero `manualSharedMinutes` forces 0, so GamificationUpdater's unconditional
     * `backfill + manual + tracked` sum cannot consume it as XP.
     */
    @Test
    fun restore_ownedInsertForcesManualSharedMinutesToZero() = runTest {
        listOf("STEAM_OWNED", null).forEach { source ->
            val harness = newEngine(games = mutableMapOf(), nowMillis = 1_700_000_000_000L)
            val file = baseFile(
                games = listOf(
                    BackupGame(
                        appId = 441L,
                        name = "Owned Game",
                        isGoal = false,
                        backfillMinutes = 0,
                        source = source,
                        manualSharedMinutes = 90,
                    ),
                ),
            )

            harness.engine.merge(file, RuleConfig())

            val restored = harness.gameDao.getById(441L)!!
            assertEquals(GameSource.STEAM_OWNED, restored.source)
            assertEquals(0, restored.manualSharedMinutes)
        }
    }

    /**
     * Restoring an owned source over a shared row carrying an estimate must clear it atomically
     * with the source flip: `setManualSharedMinutes` is SQL-guarded to shared rows, so a
     * backup value of 0 would no-op after the flip and a nonzero one must not survive it.
     */
    @Test
    fun restore_restoringOwnedSourceClearsStaleManualEstimate() = runTest {
        listOf(0, 90).forEach { backupManual ->
            val harness = newEngine(
                games = mutableMapOf(
                    441L to testGame(441L).copy(source = GameSource.FAMILY_SHARED, manualSharedMinutes = 45),
                ),
                nowMillis = 1_700_000_000_000L,
            )
            val file = baseFile(
                games = listOf(
                    BackupGame(
                        appId = 441L,
                        name = "Now Owned Game",
                        isGoal = false,
                        backfillMinutes = 0,
                        source = "STEAM_OWNED",
                        manualSharedMinutes = backupManual,
                    ),
                ),
            )

            harness.engine.merge(file, RuleConfig())

            val restored = harness.gameDao.getById(441L)!!
            assertEquals(GameSource.STEAM_OWNED, restored.source)
            assertEquals(
                "backup manual $backupManual must not leave a stale estimate on the owned row",
                0,
                restored.manualSharedMinutes,
            )
        }
    }

    @Test
    fun restore_absentRecencyFieldsDoNotEraseLocallyObservedOnes() = runTest {
        val locallyObserved = 1_699_900_000_000L
        val harness = newEngine(
            games = mutableMapOf(1L to testGame(1L).copy(firstSeenAt = locallyObserved)),
            nowMillis = 1_700_000_000_000L,
        )
        val file = baseFile(
            games = listOf(BackupGame(appId = 1L, name = "Game 1", isGoal = false, backfillMinutes = 0)),
        )

        harness.engine.merge(file, RuleConfig())

        assertEquals(locallyObserved, harness.gameDao.getById(1L)!!.firstSeenAt)
    }

    @Test
    fun restore_isInterpretedOnItsOwnTimeline() = runTest {
        val now = 1_700_000_000_000L
        val day = 24L * 60 * 60 * 1_000

        suspend fun restoredStateFor(arrivedAt: Long): GameRecencyState? {
            val harness = newEngine(nowMillis = now)
            harness.engine.merge(
                baseFile(
                    games = listOf(
                        BackupGame(
                            appId = 1L,
                            name = "Game 1",
                            isGoal = false,
                            backfillMinutes = 0,
                            firstSeenAt = arrivedAt.toIso8601(),
                        ),
                    ),
                ),
                RuleConfig(),
            )
            val restored = harness.gameDao.getById(1L)!!
            return LibraryRecency.derive(
                firstSeenAt = restored.firstSeenAt,
                returnedToPlayAt = restored.returnedToPlayAt,
                playtimeForever = restored.playtimeForever,
                firstSessionAt = null,
                now = now,
            )
        }

        assertNull(restoredStateFor(now - 90 * day))
        assertEquals(GameRecencyState.NEWLY_ADDED, restoredStateFor(now - day))
    }


    @Test
    fun overlappingSession_replacesInPlace_noDuplicate() = runTest {
        val existing = Session(appId = 1L, startAt = 1_000L, endAt = 2_000L, minutes = 10, open = false)
        val harness = newEngine(
            games = mutableMapOf(1L to testGame(1L)),
            sessions = mutableListOf(existing),
        )
        val file = baseFile(
            sessions = listOf(
                BackupSession(appId = 1L, startAt = 1_000L.toIso8601(), endAt = 2_000L.toIso8601(), minutes = 25),
            ),
        )

        harness.engine.merge(file, RuleConfig())

        val all = harness.sessionDao.getAll()
        assertEquals(1, all.size)
        assertEquals(25, all.single().minutes)
    }

    @Test
    fun nonOverlappingSession_isAdded_existingUntouched() = runTest {
        val existing = Session(appId = 1L, startAt = 1_000L, endAt = 2_000L, minutes = 10, open = false)
        val harness = newEngine(
            games = mutableMapOf(1L to testGame(1L)),
            sessions = mutableListOf(existing),
        )
        val file = baseFile(
            sessions = listOf(
                BackupSession(appId = 1L, startAt = 5_000L.toIso8601(), endAt = 6_000L.toIso8601(), minutes = 15),
            ),
        )

        harness.engine.merge(file, RuleConfig())

        val all = harness.sessionDao.getAll()
        assertEquals(2, all.size)
        assertTrue(all.any { it.startAt == 1_000L && it.minutes == 10 })
        assertTrue(all.any { it.startAt == 5_000L && it.minutes == 15 })
    }

    @Test
    fun collectionsAndMembers_mergeIntoLocalStore() = runTest {
        val harness = newEngine()
        val file = baseFile(
            collections = listOf(
                BackupCollection(
                    id = 1L,
                    name = "Queue",
                    mode = "ORDERED_QUEUE",
                    sort = "MANUAL_SEQUENCE",
                    targetDate = null,
                    createdAt = 5L,
                    description = "Play in sequence",
                    displayOrder = 3,
                ),
            ),
            collectionMembers = listOf(
                BackupCollectionMember(collectionId = 1L, appId = 10L, orderIndex = 0),
                BackupCollectionMember(collectionId = 1L, appId = 11L, orderIndex = 1),
            ),
        )

        harness.engine.merge(file, RuleConfig())

        val stored = harness.collectionDao.getAll()
        assertEquals(1, stored.size)
        assertEquals("Queue", stored.single().name)
        assertEquals(CollectionMode.ORDERED_QUEUE, stored.single().mode)
        assertEquals(CollectionSort.MANUAL_SEQUENCE, stored.single().sort)
        assertEquals("Play in sequence", stored.single().description)
        assertEquals(3, stored.single().displayOrder)
        assertEquals(listOf(10L, 11L), harness.collectionDao.getMembers(1L).map { it.appId })
    }

    @Test
    fun collectionMerge_isIdempotentByCollectionId() = runTest {
        val harness = newEngine(
            collections = mutableMapOf(1L to Collection(id = 1L, name = "Before", mode = CollectionMode.BASIC, sort = CollectionSort.NAME, createdAt = 3L)),
        )
        val file = baseFile(
            collections = listOf(
                BackupCollection(id = 1L, name = "After", mode = "COMPLETION_GOAL", sort = "COMPLETION_FRACTION", targetDate = null, createdAt = 3L),
            ),
        )

        harness.engine.merge(file, RuleConfig())

        val stored = harness.collectionDao.getAll()
        assertEquals(1, stored.size) // id 1 updated in place, not duplicated
        assertEquals("After", stored.single().name)
        assertEquals(CollectionMode.COMPLETION_GOAL, stored.single().mode)
    }

    @Test
    fun collectionMerge_legacyFileWithoutAccentAndDone_restoresWithDefaults() = runTest {
        val harness = newEngine()
        val file = baseFile(
            collections = listOf(
                BackupCollection(id = 1L, name = "Legacy", mode = "BASIC", sort = "NAME", targetDate = null, createdAt = 1L),
            ),
            collectionMembers = listOf(
                BackupCollectionMember(collectionId = 1L, appId = 10L, orderIndex = 0),
            ),
        )

        harness.engine.merge(file, RuleConfig())

        val stored = harness.collectionDao.getAll().single()
        assertEquals(null, stored.accent)
        val member = harness.collectionDao.getMembers(1L).single()
        assertEquals(false, member.done)
    }

    @Test
    fun collectionMerge_unknownAccentString_fallsBackToNull() = runTest {
        val harness = newEngine()
        val file = baseFile(
            collections = listOf(
                BackupCollection(id = 1L, name = "Weird", mode = "BASIC", sort = "NAME", targetDate = null, createdAt = 1L, accent = "NOT_A_COLOR"),
            ),
        )

        harness.engine.merge(file, RuleConfig())

        assertEquals(null, harness.collectionDao.getAll().single().accent)
    }

    @Test
    fun collectionMemberMerge_doneFlagRoundTrips() = runTest {
        val harness = newEngine()
        val file = baseFile(
            collections = listOf(
                BackupCollection(id = 1L, name = "Queue", mode = "ORDERED_QUEUE", sort = "MANUAL_SEQUENCE", targetDate = null, createdAt = 1L),
            ),
            collectionMembers = listOf(
                BackupCollectionMember(collectionId = 1L, appId = 10L, orderIndex = 0, done = true),
            ),
        )

        harness.engine.merge(file, RuleConfig())

        assertEquals(true, harness.collectionDao.getMembers(1L).single().done)
    }

    @Test
    fun achievementSnapshot_importedEarlierUnlock_replacesLocal() = runTest {
        // Earlier-unlock-wins (auditfix-backup-integrity design.md decision 5): the local
        // snapshot's unlock (500) is later than the import's (100), so the import replaces it.
        val local = Achievement(
            appId = 1L, apiName = "ACH", unlocked = true, unlockedAt = 500L,
            snapshotPercent = 2.0, fetchedAt = 0L,
        )
        val harness = newEngine(
            games = mutableMapOf(1L to testGame(1L)),
            achievements = mutableListOf(local),
        )
        val file = baseFile(
            achievements = listOf(
                BackupAchievement(
                    appId = 1L, apiName = "ACH", displayName = "Ach",
                    snapshotPercent = 99.0, unlockedAt = 100L.toIso8601(),
                ),
            ),
        )

        harness.engine.merge(file, RuleConfig())

        val stored = harness.achievementDao.getOne(1L, "ACH")!!
        assertEquals(99.0, stored.snapshotPercent)
        assertEquals(100L, stored.unlockedAt)
    }

    @Test
    fun achievementSnapshot_importedLaterUnlock_localRetained() = runTest {
        // The local snapshot's unlock (100) is earlier than the import's (500), so local wins.
        val local = Achievement(
            appId = 1L, apiName = "ACH", unlocked = true, unlockedAt = 100L,
            snapshotPercent = 2.0, fetchedAt = 0L,
        )
        val harness = newEngine(
            games = mutableMapOf(1L to testGame(1L)),
            achievements = mutableListOf(local),
        )
        val file = baseFile(
            achievements = listOf(
                BackupAchievement(
                    appId = 1L, apiName = "ACH", displayName = "Ach",
                    snapshotPercent = 99.0, unlockedAt = 500L.toIso8601(),
                ),
            ),
        )

        harness.engine.merge(file, RuleConfig())

        val stored = harness.achievementDao.getOne(1L, "ACH")!!
        assertEquals(2.0, stored.snapshotPercent)
        assertEquals(100L, stored.unlockedAt)
    }

    @Test
    fun achievementSnapshot_mergeOrderIndependent_convergesOnEarlierUnlock() = runTest {
        val gameId = 1L
        fun freshFile(unlockedAtMillis: Long, snapshotPercent: Double) = baseFile(
            achievements = listOf(
                BackupAchievement(
                    appId = gameId, apiName = "ACH", displayName = "Ach",
                    snapshotPercent = snapshotPercent, unlockedAt = unlockedAtMillis.toIso8601(),
                ),
            ),
        )

        val forward = newEngine(games = mutableMapOf(gameId to testGame(gameId)))
        forward.engine.merge(freshFile(500L, 2.0), RuleConfig())
        forward.engine.merge(freshFile(100L, 99.0), RuleConfig())

        val reverse = newEngine(games = mutableMapOf(gameId to testGame(gameId)))
        reverse.engine.merge(freshFile(100L, 99.0), RuleConfig())
        reverse.engine.merge(freshFile(500L, 2.0), RuleConfig())

        val forwardResult = forward.achievementDao.getOne(gameId, "ACH")!!
        val reverseResult = reverse.achievementDao.getOne(gameId, "ACH")!!
        assertEquals(100L, forwardResult.unlockedAt)
        assertEquals(99.0, forwardResult.snapshotPercent)
        assertEquals(forwardResult.unlockedAt, reverseResult.unlockedAt)
        assertEquals(forwardResult.snapshotPercent, reverseResult.snapshotPercent)
    }

    @Test
    fun achievementSnapshot_neverRefreshedToACurrentValue_equalUnlockKeepsLowerPercent() = runTest {
        // Equal unlock timestamps: the lower percentage is the earlier observation (global rarity
        // only rises), so the local 2.0 stands and the higher imported value is discarded.
        val local = Achievement(
            appId = 1L, apiName = "ACH", unlocked = true, unlockedAt = 100L,
            snapshotPercent = 2.0, fetchedAt = 0L,
        )
        val harness = newEngine(
            games = mutableMapOf(1L to testGame(1L)),
            achievements = mutableListOf(local),
        )
        val file = baseFile(
            achievements = listOf(
                BackupAchievement(
                    appId = 1L, apiName = "ACH", displayName = "Ach",
                    snapshotPercent = 55.0, unlockedAt = 100L.toIso8601(),
                ),
            ),
        )

        harness.engine.merge(file, RuleConfig())

        val stored = harness.achievementDao.getOne(1L, "ACH")!!
        assertEquals(2.0, stored.snapshotPercent)
    }

    @Test
    fun achievementSnapshot_equalUnlockLowerImportedPercent_replacesLocal() = runTest {
        val local = Achievement(
            appId = 1L, apiName = "ACH", unlocked = true, unlockedAt = 100L,
            snapshotPercent = 55.0, fetchedAt = 0L,
        )
        val harness = newEngine(
            games = mutableMapOf(1L to testGame(1L)),
            achievements = mutableListOf(local),
        )
        val file = baseFile(
            achievements = listOf(
                BackupAchievement(
                    appId = 1L, apiName = "ACH", displayName = "Ach",
                    snapshotPercent = 2.0, unlockedAt = 100L.toIso8601(),
                ),
            ),
        )

        harness.engine.merge(file, RuleConfig())

        assertEquals(2.0, harness.achievementDao.getOne(1L, "ACH")!!.snapshotPercent)
    }

    @Test
    fun achievementSnapshot_equalUnlockDifferentPercents_convergesRegardlessOfOrder() = runTest {
        // The tie-break is what makes this converge at all: without it, A→B keeps A's value and
        // B→A keeps B's, contradicting the order-independence the whole rule exists to provide.
        fun fileWith(percent: Double) = baseFile(
            achievements = listOf(
                BackupAchievement(
                    appId = 1L, apiName = "ACH", displayName = "Ach",
                    snapshotPercent = percent, unlockedAt = 100L.toIso8601(),
                ),
            ),
        )

        val forward = newEngine(games = mutableMapOf(1L to testGame(1L)))
        forward.engine.merge(fileWith(55.0), RuleConfig())
        forward.engine.merge(fileWith(2.0), RuleConfig())

        val reverse = newEngine(games = mutableMapOf(1L to testGame(1L)))
        reverse.engine.merge(fileWith(2.0), RuleConfig())
        reverse.engine.merge(fileWith(55.0), RuleConfig())

        assertEquals(2.0, forward.achievementDao.getOne(1L, "ACH")!!.snapshotPercent)
        assertEquals(
            forward.achievementDao.getOne(1L, "ACH")!!.snapshotPercent,
            reverse.achievementDao.getOne(1L, "ACH")!!.snapshotPercent,
        )
    }

    @Test
    fun achievementSnapshot_replacingEarlierUnlock_preservesFieldsAbsentFromBackup() = runTest {
        // `retired`, `description`, and `hidden` have no representation in the backup format.
        // Rebuilding the row from scratch would resurrect a retired achievement into the
        // unlocked/XP queries, which filter on `retired = 0`.
        val local = Achievement(
            appId = 1L, apiName = "ACH", unlocked = true, unlockedAt = 500L,
            snapshotPercent = 2.0, description = "Local description", hidden = true,
            retired = true, iconUrl = "icon.png", globalPercent = 30.0, fetchedAt = 7L,
        )
        val harness = newEngine(
            games = mutableMapOf(1L to testGame(1L)),
            achievements = mutableListOf(local),
        )
        val file = baseFile(
            achievements = listOf(
                BackupAchievement(
                    appId = 1L, apiName = "ACH", displayName = "Ach",
                    snapshotPercent = 99.0, unlockedAt = 100L.toIso8601(),
                ),
            ),
        )

        harness.engine.merge(file, RuleConfig())

        val stored = harness.achievementDao.getOne(1L, "ACH")!!
        // The backup is authoritative for these:
        assertEquals(99.0, stored.snapshotPercent)
        assertEquals(100L, stored.unlockedAt)
        // These it cannot speak to, so they must survive untouched:
        assertTrue(stored.retired)
        assertTrue(stored.hidden)
        assertEquals("Local description", stored.description)
        assertEquals("icon.png", stored.iconUrl)
        assertEquals(30.0, stored.globalPercent)
    }

    @Test
    fun achievementSnapshot_noLocalValue_importedValueStored() = runTest {
        val harness = newEngine(games = mutableMapOf(1L to testGame(1L)))
        val file = baseFile(
            achievements = listOf(
                BackupAchievement(
                    appId = 1L, apiName = "ACH", displayName = "Ach",
                    snapshotPercent = 10.0, unlockedAt = 100L.toIso8601(),
                ),
            ),
        )

        harness.engine.merge(file, RuleConfig())

        // Rare-tier (10%) achievement XP (40, per default RuleConfig) proves the import's
        // snapshot was stored and fed into the recompute.
        assertEquals(40, harness.profileDao.get()!!.totalXp)
    }

    @Test
    fun longestStreak_importLower_neverLowersStored() = runTest {
        val harness = newEngine(profile = PlayerProfile(longestStreak = 10))
        val file = baseFile(longestStreak = 1)

        harness.engine.merge(file, RuleConfig())

        assertEquals(10, harness.profileDao.get()!!.longestStreak)
    }

    @Test
    fun longestStreak_importHigherThanStoredAndRecomputed_raisesStored() = runTest {
        val harness = newEngine(profile = PlayerProfile(longestStreak = 2))
        val file = baseFile(longestStreak = 50)

        harness.engine.merge(file, RuleConfig())

        assertEquals(50, harness.profileDao.get()!!.longestStreak)
    }

    @Test
    fun aggregates_neverTrustedFromFile_alwaysRecomputed() = runTest {
        val harness = newEngine(profile = PlayerProfile())
        // No sessions/achievements at all -> recompute must yield 0 XP, ignoring the file's
        // deliberately implausible totalXp/currentStreak (see baseFile's defaults).
        val file = baseFile()

        harness.engine.merge(file, RuleConfig())

        val profile = harness.profileDao.get()!!
        assertEquals(0, profile.totalXp)
        assertTrue(profile.currentStreak < 999)
    }

    @Test
    fun playtimeBackfilled_orMerged_neverUnset() = runTest {
        val harness = newEngine(profile = PlayerProfile(playtimeBackfilled = true))
        val file = baseFile(playtimeBackfilled = false)

        harness.engine.merge(file, RuleConfig())

        assertTrue(harness.profileDao.get()!!.playtimeBackfilled)
    }

    @Test
    fun game_missingLocally_insertedAsSkeleton() = runTest {
        val harness = newEngine()
        val file = baseFile(
            games = listOf(BackupGame(appId = 7L, name = "New Game", isGoal = true, backfillMinutes = 30)),
        )

        harness.engine.merge(file, RuleConfig())

        val created = harness.gameDao.getById(7L)
        assertEquals("New Game", created?.name)
        assertEquals(true, created?.isGoal)
        assertEquals(30, created?.backfillMinutes)
    }

    @Test
    fun game_existingLocally_onlyGoalAndBackfillFieldsChange() = runTest {
        val existing = testGame(1L).copy(
            name = "Existing Name", iconUrl = "icon.png", playtimeForever = 500,
        )
        val harness = newEngine(games = mutableMapOf(1L to existing))
        val file = baseFile(
            games = listOf(BackupGame(appId = 1L, name = "Imported Name", isGoal = true, backfillMinutes = 20)),
        )

        harness.engine.merge(file, RuleConfig())

        val merged = harness.gameDao.getById(1L)!!
        assertEquals(true, merged.isGoal)
        assertEquals(20, merged.backfillMinutes)
        // Live Steam-fetched fields must survive the merge untouched — only isGoal/backfillMinutes
        // are natural-key-upserted (design.md decision 2's table).
        assertEquals("Existing Name", merged.name)
        assertEquals("icon.png", merged.iconUrl)
        assertEquals(500, merged.playtimeForever)
    }
}
private fun RuleConfig.toBackupForTest() = BackupRuleConfig(
    xpPerMinute = xpPerMinute,
    levelBase = levelBase,
    questThresholdMin = questThresholdMin,
    questMode = questMode.name,
    streakGraceDays = streakGraceDays,
    commonAchievementXp = commonAchievementXp,
    uncommonAchievementXp = uncommonAchievementXp,
    rareAchievementXp = rareAchievementXp,
    epicAchievementXp = epicAchievementXp,
    legendaryAchievementXp = legendaryAchievementXp,
)

private fun testGame(appId: Long) = Game(
    appId = appId,
    name = "Game $appId",
    iconUrl = "",
    playtimeForever = 0,
    playtime2Weeks = 0,
    lastPlaytime = 0,
)

private class FixedTimeProvider(private val today: LocalDate, private val millis: Long) : TimeProvider {
    override fun nowMillis(): Long = millis
    override fun zone(): ZoneId = ZoneId.of("UTC")
    override fun today(): LocalDate = today
}

private class FakeGameDao(private val store: MutableMap<Long, Game>) : GameDao {
    override suspend fun upsertAll(games: List<Game>) {
        games.forEach { store[it.appId] = it }
    }

    override suspend fun upsert(game: Game) {
        store[game.appId] = game
    }

    override suspend fun insertSteamGameIfMissing(
        appId: Long,
        name: String,
        iconUrl: String,
        playtimeForever: Int,
        playtime2Weeks: Int,
        lastPlaytime: Int,
        lastSyncedAt: Long,
        firstSeenAt: Long?,
        lastPlayedAt: Long?,
    ) {
        if (appId !in store) {
            store[appId] = Game(
                appId = appId,
                name = name,
                iconUrl = iconUrl,
                playtimeForever = playtimeForever,
                playtime2Weeks = playtime2Weeks,
                lastPlaytime = lastPlaytime,
                lastSyncedAt = lastSyncedAt,
                firstSeenAt = firstSeenAt,
                lastPlayedAt = lastPlayedAt,
            )
        }
    }

    override suspend fun updateSteamFields(
        appId: Long,
        name: String,
        iconUrl: String,
        playtimeForever: Int,
        playtime2Weeks: Int,
        lastPlaytime: Int,
        lastSyncedAt: Long,
        lastPlayedAt: Long?,
        returnedToPlayAt: Long?,
    ) {
        store[appId]?.let {
            store[appId] = it.copy(
                name = name,
                iconUrl = iconUrl,
                playtimeForever = playtimeForever,
                playtime2Weeks = playtime2Weeks,
                lastPlaytime = lastPlaytime,
                lastSyncedAt = lastSyncedAt,
                lastPlayedAt = lastPlayedAt,
                returnedToPlayAt = returnedToPlayAt ?: it.returnedToPlayAt,
            )
        }
    }

    override suspend fun updateRecencyFields(
        appId: Long,
        firstSeenAt: Long?,
        lastPlayedAt: Long?,
        returnedToPlayAt: Long?,
    ) = Unit

    override fun observeLibrary(): Flow<List<Game>> = flowOf(store.values.toList())
    override fun observeGoalGames(): Flow<List<Game>> = flowOf(emptyList())
    override fun observeBacklog(): Flow<List<Game>> = flowOf(emptyList())
    override suspend fun allAppIds(): List<Long> = store.keys.toList()
    override fun observeAppIds(): Flow<List<Long>> = flowOf(store.keys.toList())
    override suspend fun getAll(): List<Game> = store.values.toList()
    override suspend fun getById(appId: Long): Game? = store[appId]
    override suspend fun setGoal(appId: Long, isGoal: Boolean, targetMinutes: Int?) {
        store[appId]?.let { store[appId] = it.copy(isGoal = isGoal, targetMinutes = targetMinutes) }
    }

    override suspend fun setGoalFlag(appId: Long, isGoal: Boolean) {
        store[appId]?.let { store[appId] = it.copy(isGoal = isGoal) }
    }

    override suspend fun count(): Int = store.size
    override suspend fun deleteAll() = store.clear()
    override suspend fun setBackfillMinutes(appId: Long, minutes: Int) {
        store[appId]?.let { store[appId] = it.copy(backfillMinutes = minutes) }
    }
    override suspend fun setRecencyFromBackup(appId: Long, firstSeenAt: Long?, lastPlayedAt: Long?, returnedToPlayAt: Long?) {
        store[appId]?.let { store[appId] = it.copy(firstSeenAt = firstSeenAt ?: it.firstSeenAt, lastPlayedAt = lastPlayedAt ?: it.lastPlayedAt, returnedToPlayAt = returnedToPlayAt ?: it.returnedToPlayAt) }
    }

    override suspend fun insertSharedGameIfMissing(
        appId: Long,
        name: String,
        iconUrl: String,
        admittedAt: Long,
    ) {
        store.putIfAbsent(
            appId,
            Game(
                appId = appId,
                name = name,
                iconUrl = iconUrl,
                playtimeForever = 0,
                playtime2Weeks = 0,
                lastPlaytime = 0,
                lastSyncedAt = admittedAt,
                source = GameSource.FAMILY_SHARED,
            ),
        )
    }

    override suspend fun ownedGamesForDiffing(): List<Game> =
        store.values.filter { it.source == GameSource.STEAM_OWNED }

    override suspend fun sharedGames(): List<Game> =
        store.values.filter { it.source == GameSource.FAMILY_SHARED }

    override suspend fun convertSharedToOwned(
        appId: Long,
        playtimeForever: Int,
        playtime2Weeks: Int,
        convertedAt: Long,
    ): Int {
        val existing = store[appId]?.takeIf { it.source == GameSource.FAMILY_SHARED } ?: return 0
        val preservedBackfill = (existing.backfillMinutes.toLong() + existing.manualSharedMinutes.toLong())
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        store[appId] = existing.copy(
            source = GameSource.STEAM_OWNED,
            playtimeForever = playtimeForever,
            playtime2Weeks = playtime2Weeks,
            lastPlaytime = playtimeForever,
            backfillMinutes = preservedBackfill,
            manualSharedMinutes = 0,
            lastSyncedAt = convertedAt,
        )
        return 1
    }

    override suspend fun deleteSharedGame(appId: Long): Int =
        if (store[appId]?.source == GameSource.FAMILY_SHARED) {
            store.remove(appId)
            1
        } else {
            0
        }

    override suspend fun setManualSharedMinutes(appId: Long, minutes: Int) {
        store[appId]?.takeIf { it.source == GameSource.FAMILY_SHARED }?.let {
            store[appId] = it.copy(manualSharedMinutes = minutes)
        }
    }
}

private class FakeExcludedSharedGameDao : ExcludedSharedGameDao {
    private val store = mutableMapOf<Long, ExcludedSharedGame>()
    override suspend fun upsert(row: ExcludedSharedGame) { store[row.appId] = row }
    override suspend fun getAll(): List<ExcludedSharedGame> = store.values.toList()
    override fun observeAll(): Flow<List<ExcludedSharedGame>> = flowOf(store.values.toList())
    override suspend fun isExcluded(appId: Long): Boolean = appId in store
    override suspend fun delete(appId: Long) { store.remove(appId) }
    override suspend fun deleteAll() { store.clear() }
}

private class FakeSessionDao(private val store: MutableList<Session>) : SessionDao {
    private var nextId = (store.maxOfOrNull { it.id } ?: 0L) + 1

    override suspend fun insert(session: Session): Long {
        val withId = session.copy(id = nextId++)
        store += withId
        return withId.id
    }

    override suspend fun tryOpenSession(appId: Long, startAt: Long, endAt: Long?, minutes: Int): Long {
        if (store.any { it.appId == appId && it.open }) return -1L
        return insert(Session(appId = appId, startAt = startAt, endAt = endAt, minutes = minutes, open = true))
    }

    override suspend fun update(session: Session) {
        val index = store.indexOfFirst { it.id == session.id }
        if (index >= 0) store[index] = session
    }

    override suspend fun getOpenSession(appId: Long): Session? =
        store.firstOrNull { it.appId == appId && it.open }

    override suspend fun getAllOpenSessions(): List<Session> =
        store.filter { it.open }

    override fun observeSince(cutoff: Long): Flow<List<Session>> =
        flowOf(store.filter { it.startAt >= cutoff })

    override fun observeBetween(startInclusive: Long, endExclusive: Long): Flow<List<Session>> =
        flowOf(store.filter { it.startAt >= startInclusive && it.startAt < endExclusive })

    override fun observeClosedSince(cutoff: Long): Flow<List<Session>> =
        flowOf(store.filter { it.startAt >= cutoff && !it.open })
    override suspend fun getAll(): List<Session> = store.sortedBy { it.startAt }
    override suspend fun deleteAll() = store.clear()
    override fun observeEarliestSessionStart(): Flow<Long?> = flowOf(store.minOfOrNull { it.startAt })
    override fun observeFirstSessionStartByGame(): Flow<List<GameSessionInstant>> = flowOf(
        store.groupBy { it.appId }.map { (appId, rows) -> GameSessionInstant(appId, rows.minOf { it.startAt }) },
    )
    override suspend fun latestSessionInstantByGame(): List<GameSessionInstant> =
        store.groupBy { it.appId }.map { (appId, rows) -> GameSessionInstant(appId, rows.maxOf { it.endAt ?: it.startAt }) }
    override fun observeLatestSessionInstantByGame(): Flow<List<GameSessionInstant>> = flowOf(
        store.groupBy { it.appId }.map { (appId, rows) -> GameSessionInstant(appId, rows.maxOf { it.endAt ?: it.startAt }) },
    )
    override suspend fun findByNaturalKey(appId: Long, startAt: Long, endAt: Long?): Session? =
        store.firstOrNull { it.appId == appId && it.startAt == startAt && it.endAt == endAt }

    override suspend fun trackedMinutesByGame(): List<GameTrackedMinutes> =
        store.groupBy { it.appId }.map { (appId, s) -> GameTrackedMinutes(appId, s.sumOf { it.minutes }) }

    override fun observeTrackedMinutesByGame(): Flow<List<GameTrackedMinutes>> = flowOf(emptyList())

    override fun observeMinutesByGameSince(cutoff: Long): Flow<List<GameTrackedMinutes>> = flowOf(
        store.filter { it.startAt >= cutoff }
            .groupBy { it.appId }
            .map { (appId, sessions) -> GameTrackedMinutes(appId, sessions.sumOf { it.minutes }) },
    )

    override fun observeMinutesByGameBetween(
        startInclusive: Long,
        endExclusive: Long,
    ): Flow<List<GameTrackedMinutes>> = flowOf(
        store.filter { it.startAt >= startInclusive && it.startAt < endExclusive }
            .groupBy { it.appId }
            .map { (appId, sessions) -> GameTrackedMinutes(appId, sessions.sumOf { it.minutes }) },
    )

    override fun observeSessionCountsByGame(): Flow<List<GameSessionCounts>> = flowOf(emptyList())
}

private class FakeDailyProgressDao(private val store: MutableMap<String, DailyProgress>) : DailyProgressDao {
    override suspend fun upsert(day: DailyProgress) {
        store[day.date] = day
    }

    override suspend fun ensureDate(date: String) {
        store.putIfAbsent(date, DailyProgress(date))
    }

    override suspend fun addMinutes(date: String, minutesPlayed: Int, goalMinutesPlayed: Int) {
        val day = store[date] ?: DailyProgress(date)
        store[date] = day.copy(
            minutesPlayed = day.minutesPlayed + minutesPlayed,
            goalMinutesPlayed = day.goalMinutesPlayed + goalMinutesPlayed,
        )
    }

    override suspend fun setMinutes(date: String, minutesPlayed: Int, goalMinutesPlayed: Int) {
        val day = store[date] ?: DailyProgress(date)
        store[date] = day.copy(
            minutesPlayed = minutesPlayed,
            goalMinutesPlayed = goalMinutesPlayed,
        )
    }

    override suspend fun updateQuestMet(date: String, questMet: Boolean) {
        val day = store[date] ?: DailyProgress(date)
        store[date] = day.copy(questMet = questMet)
    }

    override suspend fun getByDate(date: String): DailyProgress? = store[date]
    override fun observeAll(): Flow<List<DailyProgress>> = flowOf(store.values.toList())
    override suspend fun getAllOrdered(): List<DailyProgress> = store.values.sortedBy { it.date }
    override suspend fun deleteAll() = store.clear()
}

private class FakeHltbDataDao(private val store: MutableMap<Long, HltbData>) : HltbDataDao {
    override suspend fun upsert(data: HltbData) {
        store[data.appId] = data
    }

    override suspend fun upsertAll(data: List<HltbData>) {
        data.forEach { upsert(it) }
    }

    override suspend fun deleteDatasetRows() {
        store.values.removeAll { it.origin == HltbDataOrigin.DATASET }
    }

    override suspend fun getByAppId(appId: Long): HltbData? = store[appId]
    override fun observeAll(): Flow<List<HltbData>> = flowOf(store.values.toList())
    override suspend fun getAll(): List<HltbData> = store.values.toList()
    override fun observeAllWithDataset(): Flow<List<HltbData>> = flowOf(store.values.toList())
    override suspend fun getAllWithDataset(): List<HltbData> = store.values.toList()
    override fun observeNeedsReview(): Flow<List<HltbData>> = flowOf(emptyList())
    override fun observeMatchCenter(): Flow<List<HltbData>> = flowOf(emptyList())
    override suspend fun getMatchCenter(): List<HltbData> = emptyList()
    override suspend fun markNeedsReviewWithBroaderCandidates(appId: Long, candidatesJson: String): Int = 0
}

private class FakeAchievementDao(private val store: MutableList<Achievement>) : AchievementDao {
    override suspend fun upsertAll(achievements: List<Achievement>) {
        achievements.forEach { incoming ->
            val index = store.indexOfFirst { it.appId == incoming.appId && it.apiName == incoming.apiName }
            if (index >= 0) store[index] = incoming else store += incoming
        }
    }

    override fun observeForGame(appId: Long): Flow<List<Achievement>> = flowOf(emptyList())
    override suspend fun getForGame(appId: Long): List<Achievement> =
        store.filter { it.appId == appId }
    override suspend fun deleteAll() = store.clear()

    override suspend fun getOne(appId: Long, apiName: String): Achievement? =
        store.firstOrNull { it.appId == appId && it.apiName == apiName }

    override fun observeCounts(): Flow<List<AchievementCounts>> = flowOf(emptyList())
    override suspend fun getAllUnlocked(): List<Achievement> = store.filter { it.unlocked }
    override fun observeUnlockedRarity(): Flow<List<AchievementRarity>> = flowOf(
        store.filter { it.unlocked }.map { AchievementRarity(it.appId, it.snapshotPercent) },
    )
    override fun observeUnlockedSince(cutoff: Long): Flow<List<AchievementUnlock>> = flowOf(
        store.filter { it.unlocked && (it.unlockedAt ?: 0L) >= cutoff }
            .map { AchievementUnlock(it.appId, it.iconUrl, it.unlockedAt ?: 0L) },
    )
}

private class FakePlayerProfileDao(initial: PlayerProfile?) : PlayerProfileDao {
    private var profile = initial

    override suspend fun upsert(profile: PlayerProfile) {
        this.profile = profile
    }

    override suspend fun insertIfMissing() {
        if (profile == null) profile = PlayerProfile()
    }

    override fun observe(): Flow<PlayerProfile?> = flowOf(profile)
    override suspend fun get(): PlayerProfile? = profile
    override suspend fun resetForAccountChange(steamId: String) {
        profile = (profile ?: PlayerProfile()).copy(steamId = steamId)
    }

    override suspend fun updateSyncStatus(lastSyncAt: Long, lastSyncError: String?) {
        profile = (profile ?: PlayerProfile()).copy(
            lastSyncAt = maxOf(profile?.lastSyncAt ?: 0L, lastSyncAt),
            lastSyncError = lastSyncError,
        )
    }

    override suspend fun updateSteamIdentity(
        steamId: String,
        steamLevel: Int,
        personaName: String?,
        avatarUrl: String?,
        storeRegion: String?,
    ) {
        profile = (profile ?: PlayerProfile()).copy(steamId = steamId, steamLevel = steamLevel, personaName = personaName, avatarUrl = avatarUrl, storeRegion = storeRegion)
    }

    override suspend fun storeRegion(): String? = profile?.storeRegion

    override suspend fun lastSuccessfulWishlistReadAt(): Long? = profile?.lastSuccessfulWishlistReadAt

    override suspend fun updateLastSuccessfulWishlistReadAt(readAt: Long) {
        profile = (profile ?: PlayerProfile()).copy(
            lastSuccessfulWishlistReadAt = maxOf(profile?.lastSuccessfulWishlistReadAt ?: 0L, readAt),
        )
    }

    override suspend fun clearLastSuccessfulWishlistReadAt() {
        profile = (profile ?: PlayerProfile()).copy(lastSuccessfulWishlistReadAt = null)
    }

    override suspend fun updateHeaderIdentity(personaName: String?, avatarUrl: String?, storeRegion: String?) {
        profile = (profile ?: PlayerProfile()).copy(personaName = personaName, avatarUrl = avatarUrl, storeRegion = storeRegion)
    }

    override suspend fun updateGamification(totalXp: Int, level: Int, currentStreak: Int, longestStreak: Int, gamificationConfigVersion: Long) {
        profile = (profile ?: PlayerProfile()).copy(totalXp = totalXp, level = level, currentStreak = currentStreak, longestStreak = maxOf(profile?.longestStreak ?: 0, longestStreak), gamificationConfigVersion = gamificationConfigVersion, pendingImportRecompute = false)
    }

    override suspend fun updatePlaytimeBackfilled(playtimeBackfilled: Boolean) {
        profile = (profile ?: PlayerProfile()).copy(playtimeBackfilled = playtimeBackfilled)
    }

    override suspend fun updateLastSyncError(message: String) {
        profile = (profile ?: PlayerProfile()).copy(lastSyncError = message)
    }

    override suspend fun markPendingImportRecompute() {
        profile = (profile ?: PlayerProfile()).copy(pendingImportRecompute = true)
    }

    override suspend fun raiseLongestStreak(longestStreak: Int) {
        profile = (profile ?: PlayerProfile()).copy(
            longestStreak = maxOf(profile?.longestStreak ?: 0, longestStreak),
        )
    }
}

private class FakeCollectionDao(
    private val store: MutableMap<Long, Collection> = mutableMapOf(),
) : CollectionDao {
    private val membersByCollection: MutableMap<Long, MutableList<CollectionMember>> = mutableMapOf()

    private fun members(collectionId: Long): List<CollectionMember> =
        membersByCollection[collectionId]?.sortedBy { it.orderIndex } ?: emptyList()

    override fun observeCollections(): Flow<List<Collection>> = flowOf(store.values.toList())

    override suspend fun getAll(): List<Collection> = store.values.toList()

    override suspend fun getById(id: Long): Collection? = store[id]

    override suspend fun insert(collection: Collection): Long {
        val id = collection.id.takeIf { it != 0L } ?: ((store.keys.maxOrNull() ?: 0L) + 1)
        store[id] = collection.copy(id = id)
        return id
    }

    override suspend fun update(collection: Collection) {
        store[collection.id] = collection
    }

    override suspend fun upsert(collection: Collection) {
        store[collection.id] = collection
    }

    override suspend fun upsertMember(member: CollectionMember) {
        val list = membersByCollection.getOrPut(member.collectionId) { mutableListOf() }
        list.removeAll { it.appId == member.appId }
        list += member
    }

    override suspend fun updateDetails(
        id: Long,
        name: String,
        mode: CollectionMode,
        sort: CollectionSort,
        targetDate: String?,
        accent: com.example.backlogium.domain.CollectionAccent?,
        timeBasis: com.example.backlogium.domain.CollectionTimeBasis,
        description: String?,
    ) {
        store[id]?.let {
            store[id] = it.copy(
                name = name,
                mode = mode,
                sort = sort,
                targetDate = targetDate,
                accent = accent,
                timeBasis = timeBasis,
                description = description,
            )
        }
    }

    override suspend fun setDisplayOrder(id: Long, displayOrder: Int) {
        store[id]?.let { store[id] = it.copy(displayOrder = displayOrder) }
    }

    override fun observeAllMembers(): kotlinx.coroutines.flow.Flow<List<CollectionMember>> =
        flowOf(membersByCollection.values.flatten().sortedWith(compareBy({ it.collectionId }, { it.orderIndex })))

    override suspend fun getAllMembers(): List<CollectionMember> =
        membersByCollection.values.flatten().sortedWith(compareBy({ it.collectionId }, { it.orderIndex }))
    override suspend fun deleteAllMembers() = membersByCollection.clear()
    override suspend fun deleteAll() {
        store.clear()
        membersByCollection.clear()
    }

    override suspend fun setMemberDone(collectionId: Long, appId: Long, done: Boolean) {
        val list = membersByCollection[collectionId] ?: return
        val idx = list.indexOfFirst { it.appId == appId }
        if (idx >= 0) list[idx] = list[idx].copy(done = done)
    }

    override suspend fun delete(id: Long) {
        store.remove(id)
        membersByCollection.remove(id)
    }

    override fun observeMembers(collectionId: Long): Flow<List<CollectionMember>> =
        flowOf(members(collectionId))

    override suspend fun getMembers(collectionId: Long): List<CollectionMember> = members(collectionId)

    override suspend fun insertMember(member: CollectionMember) {
        val list = membersByCollection.getOrPut(member.collectionId) { mutableListOf() }
        list.removeAll { it.appId == member.appId }
        list += member
    }

    override suspend fun removeMember(collectionId: Long, appId: Long) {
        membersByCollection[collectionId]?.removeAll { it.appId == appId }
    }

    override suspend fun setOrderIndex(collectionId: Long, appId: Long, orderIndex: Int) {
        val list = membersByCollection[collectionId] ?: return
        val idx = list.indexOfFirst { it.appId == appId }
        if (idx >= 0) list[idx] = list[idx].copy(orderIndex = orderIndex)
    }
}
