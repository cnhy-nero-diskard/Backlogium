package com.example.backlogium.data.local.dao

import androidx.room.Room
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.entity.Collection
import com.example.backlogium.data.local.entity.CollectionMember
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.domain.CollectionMode
import com.example.backlogium.domain.CollectionSort
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Room DAO/integration tests for collections (tasks 1.6, 5.2): create/rename/delete, add/remove/
 * reorder members, multi-collection membership, and that collections survive a games-table
 * rebuild (the Steam sync path). Runs against a real in-memory Room schema under Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
class CollectionDaoTest {

    private lateinit var db: BacklogiumDatabase
    private lateinit var dao: CollectionDao
    private lateinit var gameDao: GameDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            BacklogiumDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.collectionDao()
        gameDao = db.gameDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun collection(
        id: Long = 0L,
        name: String = "My list",
        mode: CollectionMode = CollectionMode.BASIC,
        sort: CollectionSort = CollectionSort.NAME,
        targetDate: String? = null,
    ) = Collection(id = id, name = name, mode = mode, sort = sort, targetDate = targetDate, createdAt = 1L)

    @Test
    fun create_insertsAndReadsBackAllFields() = runBlocking {
        val id = dao.insert(collection(name = "Clear the backlog", mode = CollectionMode.DEADLINE_GOAL, sort = CollectionSort.DAYS_REMAINING, targetDate = "2026-09-01"))
        val stored = dao.getById(id)
        assertEquals("Clear the backlog", stored?.name)
        assertEquals(CollectionMode.DEADLINE_GOAL, stored?.mode)
        assertEquals(CollectionSort.DAYS_REMAINING, stored?.sort)
        assertEquals("2026-09-01", stored?.targetDate)
        assertEquals(1L, stored?.createdAt)
    }

    @Test
    fun renameAndUpdateDetails_persistsChanges() = runBlocking {
        val id = dao.insert(collection(name = "Old"))
        dao.updateDetails(id, "New", CollectionMode.COMPLETION_GOAL, CollectionSort.COMPLETION_FRACTION, null, null)
        val stored = dao.getById(id)
        assertEquals("New", stored?.name)
        assertEquals(CollectionMode.COMPLETION_GOAL, stored?.mode)
        assertEquals(CollectionSort.COMPLETION_FRACTION, stored?.sort)
        assertEquals(null, stored?.targetDate)
    }

    @Test
    fun delete_removesCollection() = runBlocking {
        val id = dao.insert(collection(name = "Doomed"))
        dao.delete(id)
        assertEquals(null, dao.getById(id))
        assertEquals(emptyList<Collection>(), dao.getAll())
    }

    @Test
    fun delete_cascadesToMembers() = runBlocking {
        val id = dao.insert(collection(name = "With members"))
        gameDao.upsert(Game(appId = 7L, name = "Seven", iconUrl = "", playtimeForever = 0, playtime2Weeks = 0, lastPlaytime = 0))
        dao.addMember(id, 7L)
        assertEquals(1, dao.getMembers(id).size)
        dao.delete(id)
        assertEquals(emptyList<CollectionMember>(), dao.getMembers(id))
    }

    @Test
    fun addAndRemoveMember_reflectsMembership() = runBlocking {
        val id = dao.insert(collection(name = "Members"))
        dao.addMember(id, 1L)
        dao.addMember(id, 2L)
        assertEquals(listOf(1L, 2L), dao.getMembers(id).map { it.appId })
        dao.removeMember(id, 1L)
        assertEquals(listOf(2L), dao.getMembers(id).map { it.appId })
    }

    @Test
    fun addMember_isIdempotentForSameGame() = runBlocking {
        val id = dao.insert(collection(name = "Once"))
        dao.addMember(id, 5L)
        dao.addMember(id, 5L)
        assertEquals(1, dao.getMembers(id).size)
    }

    @Test
    fun reorderMembers_persistsNewSequence() = runBlocking {
        val id = dao.insert(collection(name = "Queue", mode = CollectionMode.ORDERED_QUEUE))
        dao.addMember(id, 1L)
        dao.addMember(id, 2L)
        dao.addMember(id, 3L)
        dao.reorderMembers(id, listOf(3L, 1L, 2L))
        assertEquals(listOf(3L, 1L, 2L), dao.getMembers(id).map { it.appId })
        assertEquals(listOf(0, 1, 2), dao.getMembers(id).map { it.orderIndex })
    }

    @Test
    fun sameGameBelongsToMultipleCollectionsIndependently() = runBlocking {
        val a = dao.insert(collection(name = "A"))
        val b = dao.insert(collection(name = "B"))
        dao.addMember(a, 9L)
        dao.addMember(b, 9L)
        dao.addMember(a, 8L)
        assertTrue(dao.getMembers(a).map { it.appId }.containsAll(listOf(9L, 8L)))
        assertEquals(listOf(9L), dao.getMembers(b).map { it.appId })
    }

    @Test
    fun collectionsSurviveGamesTableRebuild() = runBlocking {
        // A Steam poll rebuilds the games table wholesale (SteamSyncWorker.persistPoll).
        gameDao.upsert(Game(appId = 10L, name = "Ten", iconUrl = "", playtimeForever = 0, playtime2Weeks = 0, lastPlaytime = 0))
        gameDao.upsert(Game(appId = 11L, name = "Eleven", iconUrl = "", playtimeForever = 10, playtime2Weeks = 0, lastPlaytime = 0))

        val id = dao.insert(collection(name = "Survivor", mode = CollectionMode.ORDERED_QUEUE))
        dao.addMember(id, 10L)
        dao.addMember(id, 11L)

        // The rebuild upserts freshly-built Game rows for the same app ids.
        val rebuilt = listOf(
            Game(appId = 10L, name = "Ten", iconUrl = "", playtimeForever = 5, playtime2Weeks = 0, lastPlaytime = 5),
            Game(appId = 11L, name = "Eleven", iconUrl = "", playtimeForever = 15, playtime2Weeks = 0, lastPlaytime = 15),
        )
        gameDao.upsertAll(rebuilt)

        // Collections and memberships are app-owned: absent from the Steam payload, so intact.
        assertEquals(1, dao.getAll().size)
        assertEquals("Survivor", dao.getById(id)?.name)
        assertEquals(listOf(10L, 11L), dao.getMembers(id).map { it.appId })
    }
}
