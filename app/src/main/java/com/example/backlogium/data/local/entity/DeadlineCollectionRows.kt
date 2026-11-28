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
    indices = [Index("collectionId", "effectiveAt")],
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
)

@Entity(tableName = "deadline_archives", primaryKeys = ["originId", "createdAt"])
data class DeadlineArchiveRow(
    val originId: Long,
    val createdAt: Long,
    val payload: String,
)
