package com.example.backlogium.data.notifications

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/** Channel IDs are permanent: Android owns their sound/vibration choices after creation. */
enum class ProgressCategory(val channelId: String, val title: String, val defaultEnabled: Boolean = false) {
    OWNED("owned_games", "New owned games"),
    SHARED("shared_games", "Family-shared games", true),
    STREAK("broken_streaks", "Broken streaks"),
    QUOTA("daily_quota", "Daily quota"),
    COMPLETION("completed_games", "Completed games"),
    RECAP("daily_recap", "Yesterday’s recap"),
}

@Serializable
@Entity(tableName = "progress_alerts")
data class ProgressAlert(
    @PrimaryKey val eventKey: String,
    val account: String,
    val generation: Long,
    val category: String,
    val sourceId: String,
    val appId: Long?,
    val title: String,
    val text: String,
    val state: String = PENDING,
    val sharedFallbackPending: Boolean = false,
) {
    companion object {
        const val PENDING = "PENDING"
        const val POST_CLAIMED = "POST_CLAIMED"
        const val DELIVERED = "DELIVERED"
        const val SUPPRESSED = "SUPPRESSED"
    }
}
