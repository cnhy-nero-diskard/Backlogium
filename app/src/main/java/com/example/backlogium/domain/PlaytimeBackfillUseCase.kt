package com.example.backlogium.domain

import com.example.backlogium.data.local.SettingsDataStore
import com.example.backlogium.data.local.dao.GameDao
import com.example.backlogium.data.local.dao.PlayerProfileDao
import com.example.backlogium.data.local.dao.SessionDao
import com.example.backlogium.data.local.entity.PlayerProfile
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * One-time, opt-in import of historical Steam playtime into XP (add-playtime-backfill).
 *
 * For each game it freezes the historical portion —
 * `backfillMinutes = max(0, playtimeForever − trackedMinutes(appId))` — onto the `Game` row,
 * marks the profile flag, and triggers a recompute. Freezing the offset (rather than feeding
 * the live, ever-growing `playtimeForever`) makes the import genuinely one-time: ongoing
 * tracked sessions accrue on top, and later syncs never re-import.
 *
 * The engine is unchanged: [GamificationUpdater.recompute] simply feeds
 * `backfillMinutes + trackedMinutes` as one cumulative total, which the existing per-game
 * taper bounds naturally.
 */
class PlaytimeBackfillUseCase @Inject constructor(
    private val gameDao: GameDao,
    private val sessionDao: SessionDao,
    private val playerProfileDao: PlayerProfileDao,
    private val settings: SettingsDataStore,
    private val gamificationUpdater: GamificationUpdater,
    private val time: TimeProvider,
) {

    /**
     * Import historical playtime if not already done.
     *
     * @return `true` if this call performed the import, `false` if it was a no-op because
     *   history had already been imported (idempotence guarantee).
     */
    suspend operator fun invoke(): Boolean {
        val profile = playerProfileDao.get() ?: PlayerProfile()
        // Idempotent: once imported, never re-import or double-count.
        if (profile.playtimeBackfilled) return false

        val trackedByGame = sessionDao.trackedMinutesByGame().associate { it.appId to it.minutes }
        val backfillByAppId = gameDao.getAll().associate { game ->
            val tracked = trackedByGame[game.appId] ?: 0
            game.appId to (game.playtimeForever - tracked).coerceAtLeast(0)
        }

        gameDao.applyBackfill(backfillByAppId)
        playerProfileDao.upsert(profile.copy(playtimeBackfilled = true))

        // Reflect the freshly imported history in XP/level using the injected clock + rules.
        recompute()
        return true
    }

    /**
     * Undo the import: clear every frozen offset, unset the flag, and recompute so XP falls
     * back to tracked-only. After this the import can be offered — and run — again, which
     * refreezes the same historical portion (`playtimeForever − trackedMinutes` is a set, not
     * an add, so re-importing is safe and restores the prior result). Tracked sessions, daily
     * progress, and streaks are untouched.
     */
    suspend fun reset() {
        val profile = playerProfileDao.get() ?: return
        gameDao.applyBackfill(gameDao.getAll().associate { it.appId to 0 })
        playerProfileDao.upsert(profile.copy(playtimeBackfilled = false))
        recompute()
    }

    private suspend fun recompute() {
        val config = settings.ruleConfigFlow.first()
        gamificationUpdater.recompute(time.today(), config)
    }
}
