package com.example.backlogium.domain

import kotlinx.serialization.Serializable

@Serializable
data class AchievementUnlockItem(
    val apiName: String,
    val name: String,
    val iconUrl: String?,
    val unlockedAt: Long?,
    val snapshotPercent: Double?,
)

@Serializable
data class AchievementUnlockGroup(
    val eventId: String,
    val account: String,
    val appId: Long,
    val gameName: String,
    val observedAt: Long,
    val achievements: List<AchievementUnlockItem>,
)
