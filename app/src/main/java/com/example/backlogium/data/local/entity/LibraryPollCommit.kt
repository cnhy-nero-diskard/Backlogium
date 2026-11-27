package com.example.backlogium.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Local proof that an owned-library poll committed, including a confirmed empty response. */
@Entity(tableName = "library_poll_commits", indices = [Index("steamId"), Index("workId", "finalized")])
data class LibraryPollCommit(
    @PrimaryKey val operationId: String,
    val workId: String,
    val steamId: String,
    val committedAt: Long,
    val gameCount: Int,
    val finalized: Boolean = false,
    val accountGeneration: Long? = null,
    val executionId: String? = null,
    val executionStartedAt: Long? = null,
    val achievementProcessed: Int? = null,
    val achievementTotal: Int? = null,
    val achievementSkipped: Int? = null,
    val achievementFailed: Int? = null,
    val achievementUpdated: Int? = null,
)
