package com.example.backlogium.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** Account-scoped durable handoff from a committed admission to targeted achievement hydration. */
@Entity(
    tableName = "achievement_admission_work",
    primaryKeys = ["steamId", "accountGeneration", "appId"],
    foreignKeys = [
        ForeignKey(
            entity = Game::class,
            parentColumns = ["appId"],
            childColumns = ["appId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("appId"),
        Index(value = ["steamId", "accountGeneration", "nextEligibleAt", "admittedAt"]),
    ],
)
data class AchievementAdmissionWork(
    val steamId: String,
    val accountGeneration: Long,
    val appId: Long,
    val admittedAt: Long,
    val attempts: Int = 0,
    val nextEligibleAt: Long = 0,
)

data class AchievementAdmissionIdentity(
    val steamId: String,
    val accountGeneration: Long,
)

data class AchievementAdmissionQueueState(
    val pendingCount: Int,
    val nextEligibleAt: Long?,
)
