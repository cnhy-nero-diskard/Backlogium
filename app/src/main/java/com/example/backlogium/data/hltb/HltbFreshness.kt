package com.example.backlogium.data.hltb

/**
 * Pure freshness gate for the batch sweep. Mirrors `HltbDataDao.appIdsStaleOrMissing` so the
 * selection logic can be unit-tested without Room: a game is refreshed when it has no cached
 * fetch time, or its cached data is at least [window] old relative to [now].
 */
object HltbFreshness {

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
