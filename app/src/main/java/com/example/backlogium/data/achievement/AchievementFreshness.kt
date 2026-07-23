package com.example.backlogium.data.achievement

/**
 * Pure freshness gate for the achievement sync. Mirrors `HltbFreshness` so selection logic is
 * unit-testable without Room: a game is refetched when it has no recorded fetch time, or its
 * cached data is at least [window] old relative to [now].
 */
object AchievementFreshness {

    fun selectStaleOrMissing(
        now: Long,
        window: Long,
        appIds: List<Long>,
        fetchedAtByAppId: Map<Long, Long>,
    ): List<Long> = appIds.filter { appId ->
        val fetchedAt = fetchedAtByAppId[appId]
        fetchedAt == null || now - fetchedAt >= window
    }
}
