package com.example.backlogium.data.repo

import androidx.room.Room
import androidx.room.withTransaction
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.entity.LibraryPollCommit
import com.example.backlogium.data.local.entity.PlayerProfile
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class LibraryPollCommitTest {
    private val database = Room.inMemoryDatabaseBuilder(
        RuntimeEnvironment.getApplication(), BacklogiumDatabase::class.java,
    ).build()
    private val commits = database.libraryPollCommitDao()

    @After
    fun close() = database.close()

    @Test
    fun restoredTimestampIsUnconfirmedAndConfirmedEmptySurvivesRefreshFailure() = runTest {
        database.playerProfileDao().upsert(PlayerProfile(steamId = "active", lastSyncAt = 10))
        assertFalse(commits.hasBaseline("active"))

        val empty = LibraryPollCommit("empty", "manual-work", "active", 20, 0)
        database.withTransaction {
            commits.insert(empty)
            database.playerProfileDao().updateSyncStatus(20, null)
        }
        database.playerProfileDao().updateLastSyncError("Private library")

        assertTrue(commits.hasBaseline("active"))
        assertEquals(empty, commits.get("empty", "active"))
        assertNull(commits.get("failed-refresh", "active"))
        assertNull(commits.get("empty", "other"))
        assertFalse(commits.hasBaseline("other"))

        AccountRoomReset(database).resetForAccountChange("other")
        assertFalse(commits.hasBaseline("active"))
        assertFalse(commits.hasBaseline("other"))
    }

    @Test
    fun interruptedRawTransactionRollsBackLibraryConfirmation() = runTest {
        val failure = runCatching {
            database.withTransaction {
                database.playerProfileDao().upsert(PlayerProfile(steamId = "active", lastSyncAt = 20))
                commits.insert(LibraryPollCommit("poll", "manual-work", "active", 20, 0))
                error("interrupted before commit")
            }
        }
        assertTrue(failure.isFailure)
        assertNull(database.playerProfileDao().get())
        assertNull(commits.get("poll", "active"))
    }
}
