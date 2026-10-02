package com.example.backlogium.data.backup

import androidx.room.Room
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.SettingsDataStore
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.GamePreference
import com.example.backlogium.data.local.entity.PlayerProfile
import com.example.backlogium.data.local.entity.HiddenGame
import com.example.backlogium.data.local.entity.Session
import com.example.backlogium.data.repo.CredentialsProvider
import com.example.backlogium.data.repo.CredentialsState
import com.example.backlogium.domain.DerivedStateWriteCoordinator
import com.example.backlogium.domain.GamificationUpdater
import com.example.backlogium.domain.TimeProvider
import com.example.backlogium.gamification.RuleConfig
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
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
        val rows = listOf(GamePreference(KEPT, true), GamePreference(TOOL, false), GamePreference(999, true))
        rows.forEach { source.gamePreferenceDao().upsert(it) }
        val file = exportMapper(source).buildExport()
        assertEquals(rows.map { BackupGamePreference(it.appId, it.isFavorite) }, file.gamePreferences)
        assertTrue(BackupValidator.validate(file) is BackupValidationResult.Valid)
        val snapshots = SnapshotStore(RuntimeEnvironment.getApplication(), kotlinx.serialization.json.Json { ignoreUnknownKeys = true })
        snapshots.write(file, 9_876_543_210L)
        try { assertEquals(file, snapshots.read("9876543210.json")) }
        finally { snapshots.delete("9876543210.json") }
        mergeEngine(restored).merge(file, RuleConfig())
        assertEquals(rows, restored.gamePreferenceDao().getAll())
        mergeEngine(restored).merge(file, RuleConfig())
        assertEquals(rows, restored.gamePreferenceDao().getAll())
        mergeEngine(restored).merge(file.copy(gamePreferences = null), RuleConfig())
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
        assertEquals(listOf(BackupGamePreference(KEPT, true)), file.gamePreferences)
        assertEquals(listOf(GamePreference(KEPT, false)), source.gamePreferenceDao().getAll())
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

    private fun newDatabase() = Room.inMemoryDatabaseBuilder(
        RuntimeEnvironment.getApplication(), BacklogiumDatabase::class.java,
    ).allowMainThreadQueries().build()

    private fun exportMapper(db: BacklogiumDatabase, transaction: DatabaseTransactionScope = RoomDatabaseTransactionScope(db)) = BackupExportMapper(
        gameDao = db.gameDao(),
        achievementDao = db.achievementDao(),
        sessionDao = db.sessionDao(),
        dailyProgressDao = db.dailyProgressDao(),
        hltbDataDao = db.hltbDataDao(),
        playerProfileDao = db.playerProfileDao(),
        collectionDao = db.collectionDao(),
        hiddenGameDao = db.hiddenGameDao(),
        gamePreferenceDao = db.gamePreferenceDao(),
        excludedSharedGameDao = db.excludedSharedGameDao(),
        settings = SettingsDataStore(RuntimeEnvironment.getApplication()),
        credentials = ConfiguredCredentials,
        time = FixedTime,
        transaction = transaction,
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
    }
}
