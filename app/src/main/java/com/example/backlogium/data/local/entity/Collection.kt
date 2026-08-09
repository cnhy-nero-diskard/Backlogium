package com.example.backlogium.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.backlogium.domain.CollectionAccent
import com.example.backlogium.domain.CollectionMode
import com.example.backlogium.domain.CollectionSort
import com.example.backlogium.domain.CollectionTimeBasis

/**
 * A user-defined named game group (add-custom-collections). App-owned state: absent from the
 * Steam payload, so it survives every sync poll untouched — the sync worker never reads or
 * writes this table.
 *
 * [mode] and [sort] are stored as their enum names via [com.example.backlogium.data.local.Converters].
 * [targetDate] is the collection-level deadline (ISO-8601 date) used only by
 * [CollectionMode.DEADLINE_GOAL]; null for every other mode (spec: "Target date stored only
 * for deadline mode").
 * [accent] is an optional palette token; null means the default neutral styling.
 * [description] is optional collection intent text; null means it has never been described.
 * [displayOrder] is the contiguous user-controlled position used when listing collections.
 */
@Entity(tableName = "collections")
data class Collection(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val mode: CollectionMode,
    val sort: CollectionSort,
    val targetDate: String? = null,
    val accent: CollectionAccent? = null,
    val timeBasis: CollectionTimeBasis = CollectionTimeBasis.COMPLETIONIST,
    val createdAt: Long,
    val description: String? = null,
    val displayOrder: Int = 0,
)
