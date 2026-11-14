package com.example.backlogium.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(tableName = "achievement_catalogue", primaryKeys = ["appId", "apiName"],
    foreignKeys = [ForeignKey(entity = Game::class, parentColumns = ["appId"], childColumns = ["appId"], onDelete = ForeignKey.CASCADE)])
data class AchievementCatalogueEntry(
    val appId: Long,
    val apiName: String,
    val displayName: String,
    val iconUrl: String?,
    val description: String?,
    val hidden: Boolean,
    val globalPercent: Double?,
)

/** A row with no entries is a successfully confirmed empty catalogue, not a failed request. */
@Entity(tableName = "achievement_catalogue_cache",
    foreignKeys = [ForeignKey(entity = Game::class, parentColumns = ["appId"], childColumns = ["appId"], onDelete = ForeignKey.CASCADE)])
data class AchievementCatalogueCache(@PrimaryKey val appId: Long, val fetchedAt: Long)
