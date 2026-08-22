package com.example.backlogium.data.backup

import androidx.room.Room
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.SettingsDataStore
import com.example.backlogium.data.local.entity.Game
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
