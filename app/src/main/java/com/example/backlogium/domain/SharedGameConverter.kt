package com.example.backlogium.domain

import com.example.backlogium.data.local.dao.GameDao
import com.example.backlogium.data.remote.dto.OwnedGameDto
import javax.inject.Inject

/**
 * Converts an admitted family-shared game to owned once Steam reports it in the player's library —
 * the player bought it.
 *
 * The baseline is the whole point. `playtime_forever` for a newly purchased game includes the hours
 * played while borrowing, which this app has *already* recorded as presence-derived sessions.
 * Diffing that total against the shared row's zero would synthesize one enormous session covering
 * time already counted, corrupting history and goal progress alike. So the conversion stores the
 * reported total as the diffing baseline and emits no sessions — exactly what first-sync baselining
 * already does for a new install — and the game's existing sessions are retained untouched.
 *
 * Called inside the sync's raw-commit transaction, before the diff reads its baselines, so the very
 * poll that first reports the game as owned already sees the fresh baseline.
 */
class SharedGameConverter @Inject constructor(
    private val gameDao: GameDao,
) {

    /**
     * @return the app ids converted by this call, for the caller's diagnostics. Empty on the
     *   overwhelmingly common path where no shared game was bought since the last poll.
     */
    suspend fun convertNewlyOwned(ownedGames: List<OwnedGameDto>, now: Long): List<Long> {
        val sharedAppIds = gameDao.sharedGames().mapTo(mutableSetOf()) { it.appId }
        if (sharedAppIds.isEmpty()) return emptyList()

        val converted = mutableListOf<Long>()
        for (dto in ownedGames) {
            if (dto.appid !in sharedAppIds) continue
            // Guarded on `source = 'FAMILY_SHARED'` in SQL, so a concurrent writer that already
            // converted this row cannot be walked back over.
            val rows = gameDao.convertSharedToOwned(
                appId = dto.appid,
                playtimeForever = dto.playtimeForever,
                playtime2Weeks = dto.playtime2Weeks,
                convertedAt = now,
            )
            if (rows > 0) converted += dto.appid
        }
        return converted
    }
}
