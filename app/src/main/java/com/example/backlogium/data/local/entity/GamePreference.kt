package com.example.backlogium.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** App-owned soft reference: sync, hiding, and temporary game absence retain explicit clears. */
@Entity(tableName = "game_preferences")
data class GamePreference(
    @PrimaryKey val appId: Long,
    val isFavorite: Boolean,
    val artworkVariant: String? = null,
)
