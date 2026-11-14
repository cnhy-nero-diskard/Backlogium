package com.example.backlogium.domain

data class AchievementWatchPreferences(
    val enabled: Boolean = true,
    val intervalSeconds: Int = 60,
    val alertsEnabled: Boolean = true,
) {
    val intervalMillis: Long get() = if (intervalSeconds == 30) 30_000 else 60_000
}
