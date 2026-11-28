package com.example.backlogium.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "deadline_revisions",
    foreignKeys = [ForeignKey(
        entity = Collection::class,
        parentColumns = ["id"],
        childColumns = ["collectionId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("collectionId", "effectiveAt"), Index("batchId")],
)
data class DeadlineRevision(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val collectionId: Long,
    val effectiveAt: Long,
    // A null app ID identifies the collection-level revision.
    val appId: Long?,
    val name: String?,
    val targetDate: String?,
    val zone: String?,
    val basis: String?,
    val hidden: Boolean?,
    val present: Boolean?,
    val mainStory: Int?,
    val mainExtra: Int?,
    val completionist: Int?,
    val allStyles: Int?,
    val gameSource: String? = null,
    val batchId: String? = null,
    val batchStartedAt: Long? = null,
    val batchCompletedAt: Long? = null,
)

@Entity(tableName = "deadline_revision_batch")
data class DeadlineRevisionBatch(
    @PrimaryKey val id: Int = 0,
    val batchId: String,
    val startedAt: Long,
    val depth: Int = 1,
)

@Entity(tableName = "deadline_archives", primaryKeys = ["originId", "createdAt"])
data class DeadlineArchiveRow(
    val originId: Long,
    val createdAt: Long,
    val payload: String,
)
