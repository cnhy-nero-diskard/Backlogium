package com.example.backlogium.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A synthesized play session. Timestamps are epoch milliseconds. [endAt] is null and
 * [open] is true while the session is still being extended by successive polls; when a
 * poll shows no playtime increase the session is closed (end = last-increase time).
 */
@Entity(
    tableName = "sessions",
    foreignKeys = [
        ForeignKey(
            entity = Game::class,
            parentColumns = ["appId"],
            childColumns = ["appId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("appId")],
)
data class Session(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val appId: Long,
    val startAt: Long,
    val endAt: Long? = null,
    val minutes: Int,
    val open: Boolean,
)
