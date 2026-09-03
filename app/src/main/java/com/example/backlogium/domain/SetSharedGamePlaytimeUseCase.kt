package com.example.backlogium.domain

import com.example.backlogium.data.local.SettingsDataStore
import com.example.backlogium.data.local.dao.GameDao
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Set or clear a family-shared game's manual playtime estimate, freely re-editable
 * (add-shared-game-playtime-and-filter).
 *
 * Constructor-shaped like [PlaytimeBackfillUseCase]: this write changes XP, so it needs the same
 * immediate recompute that class's import/reset already triggers, and neither [GameRepository]
 * (read-side joins and Steam/HLTB IO) nor `GameDetailViewModel` currently depends on
 * [GamificationUpdater] to do that inline.
 */
class SetSharedGamePlaytimeUseCase @Inject constructor(
    private val gameDao: GameDao,
    private val settings: SettingsDataStore,
    private val gamificationUpdater: GamificationUpdater,
    private val time: TimeProvider,
    private val derivedStateWrites: DerivedStateWriteCoordinator,
) {

    /**
     * @param minutes the new estimate; 0 clears it. Negative values are rejected.
     * @return `true` if the estimate was written (and XP recomputed), `false` if the game is not
     *   family-shared or [minutes] is negative — a no-op, not an error.
     */
    suspend operator fun invoke(appId: Long, minutes: Int): Boolean {
        if (minutes < 0) return false
        val game = gameDao.getById(appId) ?: return false
        if (game.source != GameSource.FAMILY_SHARED) return false

        derivedStateWrites.withLock {
            gameDao.setManualSharedMinutes(appId, minutes)
            val rules = settings.ruleConfigWithVersionFlow.first()
            gamificationUpdater.recompute(
                today = time.today(),
                source = RecomputeSource.SYNC,
                config = rules.config,
                configVersion = rules.version,
            )
        }
        return true
    }
}
