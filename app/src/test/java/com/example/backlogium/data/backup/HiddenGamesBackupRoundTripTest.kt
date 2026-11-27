package com.example.backlogium.data.backup

import com.example.backlogium.test.SettingsDataStoreRule
import org.junit.Rule

import androidx.room.Room
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.SettingsDataStore
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.GamePreference
import com.example.backlogium.data.local.entity.PlayerProfile
import com.example.backlogium.data.local.entity.HiddenGame
import com.example.backlogium.data.local.entity.Session
import com.example.backlogium.data.local.entity.Achievement
import com.example.backlogium.data.local.entity.GameAchievementSync
import com.example.backlogium.data.repo.CredentialsProvider
import com.example.backlogium.data.repo.CredentialsState
import com.example.backlogium.data.repo.AchievementInsightsRepository
import com.example.backlogium.data.repo.AchievementRepository
import com.example.backlogium.data.repo.AchievementRefresh
import com.example.backlogium.data.repo.HiddenGamesRepository
import com.example.backlogium.data.remote.SteamApi
import com.example.backlogium.data.remote.dto.PlayerAchievementDto
import com.example.backlogium.domain.DerivedStateWriteCoordinator
import com.example.backlogium.domain.GamificationUpdater
import com.example.backlogium.domain.TimeProvider
import com.example.backlogium.gamification.RuleConfig
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.lang.reflect.Proxy
import java.time.LocalDate
import java.time.ZoneId

/**
 * The hidden set survives a full export/restore (add-hidden-games).
 *
 * A restore that dropped it would silently unhide everything and re-apply XP the player
 * deliberately removed — the failure this exercises end to end, through the real export mapper and
 * the real merge engine, into a second database that has never seen the hide.
 */
@RunWith(RobolectricTestRunner::class)
class HiddenGamesBackupRoundTripTest {
    @get:Rule val settingsFixture = SettingsDataStoreRule()


    private lateinit var source: BacklogiumDatabase
    private lateinit var restored: BacklogiumDatabase

    @Before fun setUp() {
        source = newDatabase()
        restored = newDatabase()
    }

    @After fun tearDown() {
        source.close()
        restored.close()
    }

    @Test
    fun explicitFavoriteClearsAndAbsentGamePreferencesRoundTripAndLegacyPreservesThem() = runBlocking {
        source.gameDao().upsert(game(KEPT, "Kept Game"))
        val rows = listOf(GamePreference(KEPT, true, "LIBRARY_HERO"), GamePreference(TOOL, false), GamePreference(999, true, "WIDE_CAPSULE"))
        rows.forEach { source.gamePreferenceDao().upsert(it) }
        val file = exportMapper(source).buildExport()
        assertEquals(rows.map { BackupGamePreference(it.appId, it.isFavorite, BackupArtwork(it.artworkVariant)) }, file.gamePreferences)
        assertTrue(BackupValidator.validate(file) is BackupValidationResult.Valid)
        val snapshots = SnapshotStore(RuntimeEnvironment.getApplication(), kotlinx.serialization.json.Json { ignoreUnknownKeys = true })
        snapshots.write(file, 9_876_543_210L)
        try { assertEquals(file, snapshots.read("9876543210.json")) }
        finally { snapshots.delete("9876543210.json") }
        restored.gamePreferenceDao().upsert(GamePreference(TOOL, false, "HERO_CAPSULE"))
        mergeEngine(restored).merge(file, RuleConfig())
        assertEquals(rows, restored.gamePreferenceDao().getAll())
        mergeEngine(restored).merge(file, RuleConfig())
        assertEquals(rows, restored.gamePreferenceDao().getAll())
        mergeEngine(restored).merge(file.copy(gamePreferences = null), RuleConfig())
        assertEquals(rows, restored.gamePreferenceDao().getAll())
        mergeEngine(restored).merge(file.copy(gamePreferences = file.gamePreferences!!.map { it.copy(artwork = null) }), RuleConfig())
        assertEquals(rows, restored.gamePreferenceDao().getAll())
        val invalid = file.copy(gamePreferences = listOf(BackupGamePreference(KEPT, false, BackupArtwork("https://example.com/art.jpg"))))
        assertTrue(BackupValidator.validate(invalid) is BackupValidationResult.Invalid)
        assertTrue(runCatching { mergeEngine(restored).merge(invalid, RuleConfig()) }.isFailure)
        assertEquals(rows, restored.gamePreferenceDao().getAll())
    }

    @Test
    fun exportReadsPreferencesInsideTheSameSnapshotAsGames() = runBlocking {
        source.gameDao().upsert(game(KEPT, "Original"))
        source.gamePreferenceDao().upsert(GamePreference(KEPT, true))
        var snapshots = 0
        val transaction = object : DatabaseTransactionScope {
            override suspend fun <R> run(block: suspend () -> R): R {
                snapshots++
                val snapshot = RoomDatabaseTransactionScope(source).run(block)
                source.gameDao().upsert(game(TOOL, "After snapshot"))
                source.gamePreferenceDao().upsert(GamePreference(KEPT, false))
                return snapshot
            }
        }
        val file = exportMapper(source, transaction).buildExport()
        assertEquals(1, snapshots)
        assertEquals(listOf(KEPT), file.games.map { it.appId })
        assertEquals(listOf(BackupGamePreference(KEPT, true, BackupArtwork(null))), file.gamePreferences)
        assertEquals(listOf(GamePreference(KEPT, false)), source.gamePreferenceDao().getAll())
    }

    @Test
    fun exportRejectsCredentialsThatNoLongerMatchItsRoomSnapshot() = runBlocking {
        source.gameDao().upsert(game(KEPT, "Observed complete"))
        source.playerProfileDao().upsert(PlayerProfile(steamId = ACCOUNT))
        source.gameAchievementSyncDao().upsert(
            GameAchievementSync(KEPT, UNLOCK_AT, UNLOCK_AT, true, UNLOCK_AT, completedAt = UNLOCK_AT),
        )
        var credentialsCaptured = false
        val credentials = object : CredentialsProvider {
            override suspend fun currentCredentials(): CredentialsState.Configured {
                credentialsCaptured = true
                return CredentialsState.Configured(apiKey = "key", steamId = ACCOUNT)
            }
        }
        val transaction = object : DatabaseTransactionScope {
            override suspend fun <R> run(block: suspend () -> R): R {
                assertTrue("credentials are captured before the Room snapshot", credentialsCaptured)
                source.playerProfileDao().upsert(PlayerProfile(steamId = OTHER_ACCOUNT))
                source.gameAchievementSyncDao().upsert(
                    GameAchievementSync(
                        KEPT, UNLOCK_AT + 60_000L, UNLOCK_AT + 60_000L, true,
                        UNLOCK_AT + 60_000L, completedAt = UNLOCK_AT + 60_000L,
                    ),
                )
                return RoomDatabaseTransactionScope(source).run(block)
            }
        }

        val failure = runCatching {
            exportMapper(source, transaction, credentials).buildExport()
        }.exceptionOrNull()

        assertEquals("Backup export credentials do not match the Room profile", failure?.message)
        assertEquals(UNLOCK_AT + 60_000L, source.gameAchievementSyncDao().get(KEPT)?.completedAt)
    }

    @Test
    fun exportRejectsCompletionEvidenceWithoutAnAccountIdentity() = runBlocking {
        source.gameDao().upsert(game(KEPT, "Observed complete"))
        source.gameAchievementSyncDao().upsert(
            GameAchievementSync(KEPT, UNLOCK_AT, UNLOCK_AT, true, UNLOCK_AT, completedAt = UNLOCK_AT),
        )
        val noCredentials = object : CredentialsProvider {
            override suspend fun currentCredentials(): CredentialsState.Configured? = null
        }

        val failure = runCatching {
            exportMapper(source, credentials = noCredentials).buildExport()
        }.exceptionOrNull()

        assertEquals(
            "Cannot export achievement completion evidence without a Steam identity",
            failure?.message,
        )
    }

    @Test
    fun preferencesRejectInvalidKeysBeforeWritesAndCrossAccountMergePreservesConfiguredIdentity() = runBlocking {
        restored.playerProfileDao().upsert(PlayerProfile(steamId = "76561198000000000"))
        restored.gamePreferenceDao().upsert(GamePreference(999, true))
        val file = exportMapper(source).buildExport().copy(identity = BackupIdentity("76561198000000001"),
            gamePreferences = listOf(BackupGamePreference(999, false)))
        val duplicate = file.copy(gamePreferences = file.gamePreferences!! + BackupGamePreference(999, true))
        assertTrue(BackupValidator.validate(duplicate) is BackupValidationResult.Invalid)
        assertTrue(runCatching { mergeEngine(restored).merge(duplicate, RuleConfig()) }.isFailure)
        assertEquals(listOf(GamePreference(999, true)), restored.gamePreferenceDao().getAll())
        assertTrue(BackupValidator.validate(file.copy(gamePreferences = listOf(BackupGamePreference(0, true)))) is BackupValidationResult.Invalid)
        mergeEngine(restored).merge(file, RuleConfig())
        assertEquals(listOf(GamePreference(999, false)), restored.gamePreferenceDao().getAll())
        assertEquals("76561198000000000", restored.playerProfileDao().get()!!.steamId)
        assertEquals("76561198000000000", ConfiguredCredentials.currentCredentials()?.steamId)
    }

    @Test
    fun exportedHiddenGames_areHiddenAgainAfterRestore_andStayOutOfXp() = runBlocking {
        source.gameDao().upsertAll(listOf(game(KEPT, "Kept Game"), game(TOOL, "Wallpaper Engine")))
        source.sessionDao().insert(session(KEPT, minutes = 300))
        source.sessionDao().insert(session(TOOL, minutes = 400))
        source.hiddenGameDao().upsertAll(
            listOf(HiddenGame(appId = TOOL, hiddenAt = HIDDEN_AT, fromBulkAction = true)),
        )

        val file = exportMapper(source).buildExport()

        assertEquals(listOf(TOOL), file.hiddenGames.map { it.appId })
        assertTrue(file.hiddenGames.single().fromBulkAction)

        mergeEngine(restored).merge(file, RuleConfig())

        // Hidden again in a database that never saw the hide...
        assertEquals(listOf(TOOL), restored.hiddenGameDao().hiddenAppIds())
        assertEquals(HIDDEN_AT, restored.hiddenGameDao().getAll().single().hiddenAt)
        // ...and its 400 minutes did not re-enter XP: only the kept game's 300 count.
        assertEquals(2, restored.sessionDao().getAll().size)
        assertEquals(300, restored.playerProfileDao().get()!!.totalXp)
    }

    @Test
    fun aBackupTakenBeforeAnythingWasHidden_restoresWithNothingHidden() = runBlocking {
        source.gameDao().upsert(game(KEPT, "Kept Game"))
        source.sessionDao().insert(session(KEPT, minutes = 300))

        val file = exportMapper(source).buildExport()

        assertTrue(file.hiddenGames.isEmpty())

        mergeEngine(restored).merge(file, RuleConfig())

        assertTrue(restored.hiddenGameDao().hiddenAppIds().isEmpty())
        assertEquals(300, restored.playerProfileDao().get()!!.totalXp)
    }

    @Test
    fun restoringABackupWithNothingHidden_clearsLocallyHiddenGames() = runBlocking {
        source.gameDao().upsert(game(KEPT, "Kept Game"))
        source.sessionDao().insert(session(KEPT, minutes = 300))

        val file = exportMapper(source).buildExport()
        assertTrue(file.hiddenGames.isEmpty())

        restored.gameDao().upsert(game(KEPT, "Kept Game"))
        restored.sessionDao().insert(session(KEPT, minutes = 300))
        restored.hiddenGameDao().upsertAll(
            listOf(HiddenGame(appId = KEPT, hiddenAt = HIDDEN_AT)),
        )

        mergeEngine(restored).merge(file, RuleConfig())

        assertTrue(
            "a backup with no hidden games replaces the local hidden set",
            restored.hiddenGameDao().hiddenAppIds().isEmpty(),
        )
        assertEquals(300, restored.playerProfileDao().get()!!.totalXp)
    }

    @Test
    fun datedUnlockWithoutSyncMetadataAndObservedCompletionSurviveRestoreAndFullRefresh() = runBlocking {
        val completionAt = UNLOCK_AT + 60_000L
        source.gameDao().upsertAll(listOf(game(KEPT, "Observed complete"), game(TOOL, "Dated unlock")))
        source.achievementDao().upsertAll(listOf(
            Achievement(KEPT, "complete", "Complete", unlocked = true, unlockedAt = UNLOCK_AT,
                snapshotPercent = 2.0, fetchedAt = UNLOCK_AT),
            Achievement(TOOL, "dated", "Dated", unlocked = true, unlockedAt = UNLOCK_AT,
                snapshotPercent = 4.0, fetchedAt = UNLOCK_AT),
        ))
        source.gameAchievementSyncDao().upsert(
            GameAchievementSync(KEPT, UNLOCK_AT, UNLOCK_AT, true, UNLOCK_AT, completedAt = completionAt),
        )

        val file = exportMapper(source).buildExport()
        assertEquals(
            listOf(BackupAchievementCompletion(ACCOUNT, KEPT, completionAt.toIso8601())),
            file.achievementCompletions,
        )
        assertTrue(BackupValidator.validate(file) is BackupValidationResult.Valid)

        mergeEngine(restored).merge(file, RuleConfig())

        assertEquals(completionAt, restored.gameAchievementSyncDao().get(KEPT)?.completedAt)
        assertNull("dated achievement import must not fabricate fetch metadata", restored.gameAchievementSyncDao().get(TOOL))
        val repository = AchievementInsightsRepository(restored)
        val first = repository.between(ACCOUNT, UNLOCK_AT - 1, UNLOCK_AT + 180_000L).first()
        assertEquals(setOf(KEPT, TOOL), first.unlocks.map { it.appId }.toSet())
        assertEquals(completionAt, first.completions.single().observedAt)
        assertEquals(0, first.unsupportedUnlocks)

        // Exercise the production refresh/merge path: a complete response says both achievements
        // remain unlocked but supplies no unlocktime, as Steam sometimes does on later reads.
        achievementRepository(restored).applyRefreshes(listOf(
            AchievementRefresh(
                appId = KEPT,
                fetchedAt = UNLOCK_AT + 120_000L,
                achievements = listOf(PlayerAchievementDto("complete", achieved = 1, unlocktime = 0L)),
                globalPercentByName = emptyMap(),
                schemaByName = emptyMap(),
                schemaFetchedAt = null,
                fullReconciliation = true,
            ),
            AchievementRefresh(
                appId = TOOL,
                fetchedAt = UNLOCK_AT + 120_000L,
                achievements = listOf(PlayerAchievementDto("dated", achieved = 1, unlocktime = 0L)),
                globalPercentByName = emptyMap(),
                schemaByName = emptyMap(),
                schemaFetchedAt = null,
                fullReconciliation = true,
            ),
        ))
        val refreshed = repository.between(ACCOUNT, UNLOCK_AT - 1, UNLOCK_AT + 180_000L).first()
        assertEquals(first.unlocks, refreshed.unlocks)
        assertEquals(first.lifetimeRarity, refreshed.lifetimeRarity)
        assertEquals(completionAt, restored.gameAchievementSyncDao().get(KEPT)?.completedAt)
    }

    private fun newDatabase() = Room.inMemoryDatabaseBuilder(
        RuntimeEnvironment.getApplication(), BacklogiumDatabase::class.java,
    ).allowMainThreadQueries().build()

    private fun exportMapper(
        db: BacklogiumDatabase,
        transaction: DatabaseTransactionScope = RoomDatabaseTransactionScope(db),
        credentials: CredentialsProvider = ConfiguredCredentials,
    ) = BackupExportMapper(
        gameDao = db.gameDao(),
        gameAchievementSyncDao = db.gameAchievementSyncDao(),
        achievementDao = db.achievementDao(),
        sessionDao = db.sessionDao(),
        dailyProgressDao = db.dailyProgressDao(),
        hltbDataDao = db.hltbDataDao(),
        playerProfileDao = db.playerProfileDao(),
        collectionDao = db.collectionDao(),
        hiddenGameDao = db.hiddenGameDao(),
        gamePreferenceDao = db.gamePreferenceDao(),
        excludedSharedGameDao = db.excludedSharedGameDao(),
        settings = settingsFixture.create(),
        credentials = credentials,
        time = FixedTime,
        transaction = transaction,
    )

    private fun achievementRepository(db: BacklogiumDatabase) = AchievementRepository(
        steamApi = Proxy.newProxyInstance(
            SteamApi::class.java.classLoader,
            arrayOf(SteamApi::class.java),
        ) { _, method, _ -> error("Unexpected Steam API call in refresh-merge test: ${method.name}") } as SteamApi,
        achievementDao = db.achievementDao(),
        gameAchievementSyncDao = db.gameAchievementSyncDao(),
        gameDao = db.gameDao(),
        hiddenGamesRepository = HiddenGamesRepository(
            db.hiddenGameDao(), db.gameDao(), db.gameGenreCacheDao(), FixedTime,
        ),
        time = FixedTime,
    )

    private fun mergeEngine(db: BacklogiumDatabase) = BackupMergeEngine(
        gameDao = db.gameDao(),
        sessionDao = db.sessionDao(),
        dailyProgressDao = db.dailyProgressDao(),
        hltbDataDao = db.hltbDataDao(),
        achievementDao = db.achievementDao(),
        gameAchievementSyncDao = db.gameAchievementSyncDao(),
        playerProfileDao = db.playerProfileDao(),
        collectionDao = db.collectionDao(),
        hiddenGameDao = db.hiddenGameDao(),
        gamePreferenceDao = db.gamePreferenceDao(),
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
        transaction = RoomDatabaseTransactionScope(db),
        derivedStateWrites = DerivedStateWriteCoordinator(),
        credentials = ConfiguredCredentials,
    )

    private fun game(appId: Long, name: String) = Game(
        appId = appId,
        name = name,
        iconUrl = "",
        playtimeForever = 0,
        playtime2Weeks = 0,
        lastPlaytime = 0,
    )

    private fun session(appId: Long, minutes: Int) = Session(
        appId = appId,
        startAt = 1_700_000_000_000L,
        endAt = 1_700_000_000_000L + minutes * 60_000L,
        minutes = minutes,
        open = false,
    )

    private object ConfiguredCredentials : CredentialsProvider {
        override suspend fun currentCredentials() =
            CredentialsState.Configured(apiKey = "key", steamId = "76561198000000000")
    }

    private object FixedTime : TimeProvider {
        override fun nowMillis(): Long = 1_700_000_900_000L
        override fun zone(): ZoneId = ZoneId.of("UTC")
        override fun today(): LocalDate = LocalDate.parse("2026-08-22")
    }

    private companion object {
        const val KEPT = 1L
        const val TOOL = 2L
        const val HIDDEN_AT = 1_700_000_500_000L
        const val ACCOUNT = "76561198000000000"
        const val OTHER_ACCOUNT = "76561198000000001"
        const val UNLOCK_AT = 1_700_000_000_000L
    }
}
