package com.example.backlogium.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * A game's membership in a collection. Composite PK (collectionId, appId) so a game can belong
 * to many collections independently.
 *
 * A FK to `collections` with CASCADE drops memberships when the collection is deleted.
 * Deliberately **no** FK to `games`: Steam syncs can transiently omit games, and a hard FK
 * would cascade-delete membership rows on a glitch. The soft [appId] reference plus graceful
 * omission from rendering is safer (design.md decision).
 *
 * [orderIndex] is the manual sequence position used by ordered-queue collections; non-queue
 * modes ignore it.
 * [done] is the user's manual completion mark for ordered-queue members; non-queue modes ignore
 * it (refine-collections-ui).
 */
@Entity(
    tableName = "collection_members",
    primaryKeys = ["collectionId", "appId"],
    foreignKeys = [
        ForeignKey(
            entity = Collection::class,
            parentColumns = ["id"],
            childColumns = ["collectionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("collectionId")],
)
data class CollectionMember(
    val collectionId: Long,
    val appId: Long,
    val orderIndex: Int,
    val done: Boolean = false,
)
