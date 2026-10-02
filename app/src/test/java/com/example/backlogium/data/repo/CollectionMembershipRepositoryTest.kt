package com.example.backlogium.data.repo

import androidx.room.Room
import com.example.backlogium.data.backup.RoomDatabaseTransactionScope
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.entity.Collection
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.PlayerProfile
import com.example.backlogium.domain.CollectionMode
import com.example.backlogium.domain.CollectionSort
import com.example.backlogium.domain.TimeProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class CollectionMembershipRepositoryTest {
    private lateinit var db: BacklogiumDatabase
    private lateinit var shortcuts: CollectionMembershipRepository
    private lateinit var editor: CollectionRepository
    private val identity = object : AccountDataWriteGuard {
        override suspend fun capture() = "fixture"
        override suspend fun check(steamId: String) { check(steamId == "fixture") }
    }
    private val time = object : TimeProvider {
        override fun nowMillis() = 1L
        override fun zone() = ZoneId.of("UTC")
        override fun today() = LocalDate.of(2026, 10, 3)
    }
    private var id = 0L

    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), BacklogiumDatabase::class.java)
            .allowMainThreadQueries().build()
        db.playerProfileDao().upsert(PlayerProfile(steamId = "fixture"))
        (1L..3L).forEach { db.gameDao().upsert(Game(it, "Game $it", "", 0, 0, 0)) }
        id = db.collectionDao().insert(Collection(name = "Queue", mode = CollectionMode.ORDERED_QUEUE,
            sort = CollectionSort.MANUAL_SEQUENCE, createdAt = 1))
        shortcuts = CollectionMembershipRepository(db, identity)
        editor = CollectionRepository(db.collectionDao(), fakeHiddenGamesRepository(hidden = setOf(2)),
            time, RoomDatabaseTransactionScope(db))
    }
    @After fun close() { db.close() }

    @Test fun duplicateAddPreservesQueueDoneAndPickerMembership() = runBlocking {
        assertTrue(shortcuts.add(id, 1, "fixture"))
        shortcuts.add(id, 2, "fixture")
        db.collectionDao().setMemberDone(id, 1, true)
        val before = db.collectionDao().getMembers(id)
        assertFalse(shortcuts.add(id, 1, "fixture"))
        assertEquals(before, db.collectionDao().getMembers(id))
        assertTrue(shortcuts.picker(1).first().targets.single().alreadyMember)
    }

    @Test fun removalCompactsOnlyChosenCollectionAndRetainsHiddenDoneRows() = runBlocking {
        val other = db.collectionDao().insert(Collection(name = "Other", mode = CollectionMode.BASIC,
            sort = CollectionSort.NAME, createdAt = 1))
        (1L..3L).forEach { shortcuts.add(id, it, "fixture") }
        shortcuts.add(other, 1, "fixture")
        db.collectionDao().setMemberDone(id, 2, true)
        assertTrue(shortcuts.remove(id, 1, "fixture"))
        val survivors = db.collectionDao().getMembers(id)
        assertEquals(listOf(2L, 3L), survivors.map { it.appId })
        assertEquals(listOf(0, 1), survivors.map { it.orderIndex })
        assertTrue(survivors.first().done)
        assertEquals(listOf(1L), db.collectionDao().getMembers(other).map { it.appId })
    }

    @Test fun missingTargetsAndStaleAccountFailWithoutOrphans() = runBlocking {
        db.collectionDao().delete(id)
        assertTrue(runCatching { shortcuts.add(id, 1, "fixture") }.isFailure)
        assertTrue(runCatching { shortcuts.add(999, 999, "fixture") }.isFailure)
        assertTrue(runCatching { shortcuts.add(999, 1, "other") }.isFailure)
        assertTrue(db.collectionDao().getAllMembers().isEmpty())
    }

    @Test fun staleEditorRejectsShortcutChangeBeforeAnyFieldsAreSaved() = runBlocking {
        shortcuts.add(id, 1, "fixture")
        val draft = editor.editorSnapshot(id)!!.copy(name = "Changed name", description = "Changed details")
        shortcuts.add(id, 3, "fixture")
        assertTrue(runCatching { editor.save(draft) }.exceptionOrNull() is com.example.backlogium.domain.CollectionEditConflict)
        assertEquals("Queue", db.collectionDao().getById(id)!!.name)
        assertNull(db.collectionDao().getById(id)!!.description)
        assertEquals(listOf(1L, 3L), db.collectionDao().getMembers(id).map { it.appId })
        editor.save(editor.editorSnapshot(id)!!.copy(name = "Refreshed"))
        assertEquals("Refreshed", db.collectionDao().getById(id)!!.name)
    }
}
