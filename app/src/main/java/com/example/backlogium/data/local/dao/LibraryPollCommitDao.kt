package com.example.backlogium.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.backlogium.data.local.entity.LibraryPollCommit
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryPollCommitDao {
    @Insert
    suspend fun insert(commit: LibraryPollCommit)

    @Query("SELECT * FROM library_poll_commits WHERE operationId = :operationId AND steamId = :steamId")
    suspend fun get(operationId: String, steamId: String): LibraryPollCommit?

    @Query(
        "SELECT * FROM library_poll_commits WHERE workId = :workId AND steamId = :steamId " +
            "AND finalized = 0 LIMIT 1",
    )
    suspend fun unfinalized(workId: String, steamId: String): LibraryPollCommit?

    @Query("UPDATE library_poll_commits SET finalized = 1 WHERE operationId = :operationId")
    suspend fun finalize(operationId: String)

    @Query("SELECT EXISTS(SELECT 1 FROM library_poll_commits WHERE steamId = :steamId)")
    suspend fun hasBaseline(steamId: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM library_poll_commits WHERE steamId = :steamId)")
    fun observeBaseline(steamId: String): Flow<Boolean>

    @Query("DELETE FROM library_poll_commits")
    suspend fun clear()
}
