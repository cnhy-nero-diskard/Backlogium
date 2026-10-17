package com.example.backlogium.domain

import com.example.backlogium.data.local.dao.GameDao
import com.example.backlogium.data.local.dao.PlaytimeRecoveryDao
import com.example.backlogium.data.remote.dto.OwnedGameDto
import com.example.backlogium.data.remote.dto.validatedPlaytime2Weeks
import com.example.backlogium.data.remote.dto.validatedPlaytimeForever
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
 * XP credit is preserved across the transition: the shared row's manual estimate is folded into
 * `backfillMinutes` (always 0 while shared) by `GameDao.convertSharedToOwned`, because XP is
 * computed from `backfill + manual + tracked` and never from `playtimeForever` — clearing the
 * estimate would drop credited minutes with no later backfill import to restore them on an
 * already-imported profile.
 *
 * Called inside the sync's raw-commit transaction, before the diff reads its baselines, so the very
 * poll that first reports the game as owned already sees the fresh baseline.
 */
class SharedGameConverter @Inject constructor(
    private val gameDao: GameDao,
    private val recoveryDao: PlaytimeRecoveryDao? = null,
    private val time: TimeProvider = SystemTimeProvider(),
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
            // The conversion stores Steam's reported total as the fresh diffing baseline, so a
            // response without a usable cumulative reading cannot convert: writing zero would
            // re-seed the baseline at nothing and lose the boundary.
            val playtimeForever = dto.validatedPlaytimeForever ?: continue
            // Guarded on `source = 'FAMILY_SHARED'` in SQL, so a concurrent writer that already
            // converted this row cannot be walked back over.
            val rows = gameDao.convertSharedToOwned(
                appId = dto.appid,
                playtimeForever = playtimeForever,
                playtime2Weeks = dto.validatedPlaytime2Weeks ?: 0,
                convertedAt = now,
            )
            if (rows > 0) {
                recoveryDao?.let { recovery ->
                    val account = recovery.activeAccount()?.takeIf { it.isNotBlank() }
                    if (account != null) {
                        val oldEpoch = recovery.state(account, dto.appid)?.baselineEpoch ?: 0L
                        val epoch = Math.addExact(oldEpoch, 1L)
                        val zone = time.zone()
                        recovery.reestablishBaseline(
                            account = account,
                            appId = dto.appid,
                            absoluteMinutes = playtimeForever.toLong(),
                            observedAt = now,
                            date = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toString(),
                            zone = zone.id,
                            epoch = epoch,
                        )
                    }
                }
                converted += dto.appid
            }
        }
        return converted
    }
}
