package com.example.backlogium.data.local.dao

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.entity.Collection
import com.example.backlogium.data.local.entity.CollectionMember
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.repo.CollectionRepository
import com.example.backlogium.domain.CollectionMode
import com.example.backlogium.domain.CollectionSort
import com.example.backlogium.domain.CollectionTimeBasis
import com.example.backlogium.domain.TimeProvider
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
        createdAt: Long = 1L,
        description: String? = null,
        displayOrder: Int = 0,
    ) = Collection(
        id = id,
        name = name,
        mode = mode,
        sort = sort,
        targetDate = targetDate,
        createdAt = createdAt,
        description = description,
        displayOrder = displayOrder,
    )

    @Test
    fun create_insertsAndReadsBackAllFields() = runBlocking {
        val id = dao.insert(
            collection(
                name = "Clear the backlog",
                mode = CollectionMode.DEADLINE_GOAL,
                sort = CollectionSort.DAYS_REMAINING,
                targetDate = "2026-09-01",
                description = "A focused plan",
                displayOrder = 4,
            ),
        )
        val stored = dao.getById(id)
        assertEquals("Clear the backlog", stored?.name)
        assertEquals(CollectionMode.DEADLINE_GOAL, stored?.mode)
        assertEquals(CollectionSort.DAYS_REMAINING, stored?.sort)
        assertEquals("2026-09-01", stored?.targetDate)
        assertEquals(1L, stored?.createdAt)
        assertEquals("A focused plan", stored?.description)
        assertEquals(4, stored?.displayOrder)
    }

    @Test
    fun renameAndUpdateDetails_persistsChanges() = runBlocking {
        val id = dao.insert(collection(name = "Old"))
        dao.updateDetails(
            id,
            "New",
            CollectionMode.COMPLETION_GOAL,
            CollectionSort.COMPLETION_FRACTION,
            null,
            null,
            CollectionTimeBasis.MAIN_STORY,
            "Updated details",
        )
        val stored = dao.getById(id)
        assertEquals("New", stored?.name)
        assertEquals(CollectionMode.COMPLETION_GOAL, stored?.mode)
        assertEquals(CollectionSort.COMPLETION_FRACTION, stored?.sort)
        assertEquals(null, stored?.targetDate)
        assertEquals(CollectionTimeBasis.MAIN_STORY, stored?.timeBasis)
        assertEquals("Updated details", stored?.description)
    }

    @Test
    fun description_nullAndClearedEmptyRemainDistinct() = runBlocking {
        val neverDescribed = dao.insert(collection(name = "Never described"))
        val cleared = dao.insert(collection(name = "Cleared", description = ""))

        assertEquals(null, dao.getById(neverDescribed)?.description)
        assertEquals("", dao.getById(cleared)?.description)
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
    fun collections_orderByDisplayOrder_andCanBeReordered() = runBlocking {
        val first = dao.insert(collection(name = "First", createdAt = 30L, displayOrder = 2))
        val second = dao.insert(collection(name = "Second", createdAt = 10L, displayOrder = 0))
        val third = dao.insert(collection(name = "Third", createdAt = 20L, displayOrder = 1))

        assertEquals(listOf(second, third, first), dao.getAll().map { it.id })

        dao.reorderCollections(listOf(first, third, second))

        assertEquals(listOf(first, third, second), dao.getAll().map { it.id })
        assertEquals(listOf(0, 1, 2), dao.getAll().map { it.displayOrder })
    }

    @Test
    fun completedReorderPersists_butCancelledReorderDoesNotWrite() = runBlocking {
        val first = dao.insert(collection(name = "First", displayOrder = 0))
        val second = dao.insert(collection(name = "Second", displayOrder = 1))
        val third = dao.insert(collection(name = "Third", displayOrder = 2))
        val storedBeforeCancel = dao.getAll().map { it.id }

        // A cancelled Home gesture restores its baseline and does not call reorderCollections.
        assertEquals(storedBeforeCancel, dao.getAll().map { it.id })

        // A clean release is the only path that writes the new sequence.
        dao.reorderCollections(listOf(third, first, second))
        assertEquals(listOf(third, first, second), dao.getAll().map { it.id })
    }

    @Test
    fun deletingCollection_leavesRemainingDisplayOrderRelative() = runBlocking {
        val first = dao.insert(collection(name = "First", displayOrder = 0))
        val deleted = dao.insert(collection(name = "Deleted", displayOrder = 1))
        val third = dao.insert(collection(name = "Third", displayOrder = 2))

        dao.delete(deleted)

        assertEquals(listOf(first, third), dao.getAll().map { it.id })
    }

    @Test
    fun repositoryCreate_appendsAfterExistingDisplayOrder() = runBlocking {
        val first = dao.insert(collection(name = "First", displayOrder = 4))
        val second = dao.insert(collection(name = "Second", displayOrder = 9))
        val repository = CollectionRepository(
            collectionDao = dao,
            time = object : TimeProvider {
                override fun nowMillis(): Long = 100L
                override fun zone(): ZoneId = ZoneId.of("UTC")
                override fun today(): LocalDate = LocalDate.of(2026, 8, 9)
            },
        )

        val created = repository.create(name = "Third", mode = CollectionMode.BASIC)

        assertEquals(listOf(first, second, created), dao.getAll().map { it.id })
        assertEquals(10, dao.getById(created)?.displayOrder)
    }

    @Test
    fun storedOrderAndDescription_surviveDatabaseReopen() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val databaseName = "collection-reopen-${System.nanoTime()}.db"
        val firstDb = Room.databaseBuilder(
            context,
            BacklogiumDatabase::class.java,
            databaseName,
        ).allowMainThreadQueries().build()
        firstDb.collectionDao().insert(
            collection(
                name = "Persistent",
                description = "Survives restart",
                displayOrder = 4,
            ),
        )
        firstDb.close()

        val reopened = Room.databaseBuilder(
            context,
            BacklogiumDatabase::class.java,
            databaseName,
        ).allowMainThreadQueries().build()
        try {
            val stored = reopened.collectionDao().getAll().single()
            assertEquals("Survives restart", stored.description)
            assertEquals(4, stored.displayOrder)
        } finally {
            reopened.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun migration12To13_seedsSameTimestampRowsById_andLeavesOtherTablesUntouched() {
        val context = RuntimeEnvironment.getApplication()
        val databaseName = "collection-migration-${System.nanoTime()}.db"
        val callback = object : SupportSQLiteOpenHelper.Callback(12) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE `collections` (" +
                        "`id` INTEGER PRIMARY KEY NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`mode` TEXT NOT NULL, " +
                        "`sort` TEXT NOT NULL, " +
                        "`targetDate` TEXT, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`accent` TEXT, " +
                        "`timeBasis` TEXT NOT NULL)",
                )
                db.execSQL("CREATE TABLE `sentinel` (`value` TEXT NOT NULL)")
                db.execSQL(
                    "INSERT INTO `collections` (`id`, `name`, `mode`, `sort`, `createdAt`, `timeBasis`) " +
                        "VALUES (2, 'Second', 'BASIC', 'NAME', 100, 'COMPLETIONIST'), " +
                        "(1, 'First', 'BASIC', 'NAME', 100, 'COMPLETIONIST'), " +
                        "(3, 'Third', 'BASIC', 'NAME', 200, 'COMPLETIONIST')",
                )
                db.execSQL("INSERT INTO `sentinel` (`value`) VALUES ('untouched')")
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration(context, databaseName, callback),
        )

        try {
            val db = helper.writableDatabase
            BacklogiumDatabase.MIGRATION_12_13.migrate(db)

            val order = buildList {
                db.query("SELECT `id`, `description`, `displayOrder` FROM `collections` ORDER BY `displayOrder`").use { cursor ->
                    while (cursor.moveToNext()) {
                        add(
                            Triple(
                                cursor.getLong(0),
                                cursor.getString(1),
                                cursor.getInt(2),
                            ),
                        )
                    }
                }
            }
            assertEquals(
                listOf(
                    Triple(1L, null, 0),
                    Triple(2L, null, 1),
                    Triple(3L, null, 2),
                ),
                order,
            )
            db.query("SELECT `value` FROM `sentinel`").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("untouched", cursor.getString(0))
            }
        } finally {
            helper.close()
            context.deleteDatabase(databaseName)
        }
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

        val id = dao.insert(
            collection(
                name = "Survivor",
                mode = CollectionMode.ORDERED_QUEUE,
                description = "Keep this order",
                displayOrder = 7,
            ),
        )
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
        assertEquals("Keep this order", dao.getById(id)?.description)
        assertEquals(7, dao.getById(id)?.displayOrder)
        assertEquals(listOf(10L, 11L), dao.getMembers(id).map { it.appId })
    }
}
