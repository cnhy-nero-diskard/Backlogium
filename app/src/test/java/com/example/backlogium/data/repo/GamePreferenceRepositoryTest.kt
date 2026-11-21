package com.example.backlogium.data.repo

import androidx.room.Room
import com.example.backlogium.data.credentials.AccountChangeMarkerStore
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.entity.Collection
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.GamePreference
import com.example.backlogium.data.local.entity.HiddenGame
import com.example.backlogium.data.local.entity.PlayerProfile
import com.example.backlogium.domain.CollectionMode
import com.example.backlogium.domain.CollectionSort
import com.example.backlogium.domain.GameSource
import com.example.backlogium.domain.GameArtworkVariant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class GamePreferenceRepositoryTest {
    private lateinit var db: BacklogiumDatabase
    private lateinit var repository: GamePreferenceRepository
    private lateinit var marker: AccountChangeMarkerStore
    private var configured = "account-a"

    @Before fun setUp() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, BacklogiumDatabase::class.java)
            .allowMainThreadQueries().build()
        marker = AccountChangeMarkerStore(context)
        marker.clear()
        db.playerProfileDao().upsert(PlayerProfile(steamId = configured))
        val credentials = object : CredentialsProvider {
            override suspend fun currentCredentials() = CredentialsState.Configured("fixture", configured)
        }
        repository = GamePreferenceRepository(db, RoomAccountDataWriteGuard(db, marker, credentials))
        db.gameDao().upsert(game())
    }

    @After fun close() { db.close() }

    private fun game(source: GameSource = GameSource.STEAM_OWNED) =
        Game(10, "Ten", "", 0, 0, 0, isGoal = true, source = source)

    @Test fun artworkAndFavoriteMutationsPreserveEachOtherAndResetClearsBoth() = runBlocking {
        db.gameDao().upsert(game(GameSource.FAMILY_SHARED))
        repository.setFavorite(10, true, configured)
        repository.setArtwork(10, GameArtworkVariant.LIBRARY_HERO, configured)
        db.gameDao().upsert(game())
        repository.setFavorite(10, false, configured)
        assertEquals(GameArtworkVariant.LIBRARY_HERO, repository.artwork(10).first().variant)
        repository.setFavorite(10, true, configured)
        repository.setArtwork(10, null, configured)
        assertEquals(GamePreference(10, true), db.gamePreferenceDao().get(10))
        repository.setArtwork(10, GameArtworkVariant.WIDE_CAPSULE, configured)
        AccountRoomReset(db).resetForAccountChange("account-b")
        assertTrue(db.gamePreferenceDao().getAll().isEmpty())
    }

    @Test fun missingDefaultsToFalseAndExplicitClearIsRetained() = runBlocking {
        assertFalse(repository.favorite(10).first().isFavorite)
        repository.setFavorite(10, true, configured)
        assertTrue(repository.favorite(10).first().isFavorite)
        repository.setFavorite(10, false, configured)
        assertEquals(listOf(GamePreference(10, false)), db.gamePreferenceDao().getAll())
        assertTrue(db.gameDao().getById(10)!!.isGoal)
    }

    @Test fun syncConversionHideAndTemporaryAbsenceRetainPreferenceAndMembership() = runBlocking {
        db.gameDao().upsert(game(GameSource.FAMILY_SHARED))
        val id = db.collectionDao().insert(Collection(name = "Queue", mode = CollectionMode.ORDERED_QUEUE,
            sort = CollectionSort.MANUAL_SEQUENCE, createdAt = 1))
        db.collectionDao().addMember(id, 10)
        repository.setFavorite(10, true, configured)
        db.gameDao().upsert(game())
        db.hiddenGameDao().upsertAll(listOf(HiddenGame(10, 1, false)))
        assertTrue(repository.favorite(10).first().isFavorite)
        assertEquals(listOf(10L), db.collectionDao().getMembers(id).map { it.appId })
        db.gameDao().deleteAll()
        assertEquals(listOf(GamePreference(10, true)), db.gamePreferenceDao().getAll())
    }

    @Test fun resetClearsAndStaleIdentityCannotWriteToNewAccount() = runBlocking {
        repository.setFavorite(10, true, configured)
        AccountRoomReset(db).resetForAccountChange("account-b")
        configured = "account-b"
        db.gameDao().upsert(game())
        assertTrue(db.gamePreferenceDao().getAll().isEmpty())
        assertTrue(runCatching { repository.setFavorite(10, true, "account-a") }.isFailure)
        assertTrue(db.gamePreferenceDao().getAll().isEmpty())
    }

    @Test fun pendingResetAndUnavailableGamesRejectBeforeWrite() = runBlocking {
        marker.markPending("account-b")
        assertTrue(runCatching { repository.setFavorite(10, true, configured) }.isFailure)
        marker.clear()
        assertTrue(runCatching { repository.setFavorite(999, true, configured) }.isFailure)
        db.hiddenGameDao().upsertAll(listOf(HiddenGame(10, 1, false)))
        assertTrue(runCatching { repository.setFavorite(10, true, configured) }.isFailure)
        assertTrue(db.gamePreferenceDao().getAll().isEmpty())
    }
}
