package com.example.backlogium.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Durable work, deliberately outside backup payloads. A null payload is recompute-only work. */
@Entity(
    tableName = "achievement_watch_work",
    indices = [Index(value = ["account", "observedAt"])],
)
data class AchievementWatchWork(
    @PrimaryKey val eventId: String,
    val account: String,
    val appId: Long,
    val epoch: String,
    val observedAt: Long,
    val recomputeNeeded: Boolean,
    val payload: String? = null,
    val deliveryState: String = PENDING,
    val epochClosed: Boolean = false,
) {
    companion object {
        const val PENDING = "pending"
        const val FOREGROUND = "foreground"
        const val BACKGROUND = "background"
        const val DELIVERED = "delivered"
        const val SUPPRESSED = "suppressed"
    }
}
