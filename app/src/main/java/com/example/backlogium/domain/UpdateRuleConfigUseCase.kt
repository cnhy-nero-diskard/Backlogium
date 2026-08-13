package com.example.backlogium.domain

import com.example.backlogium.data.repo.SettingsRepository
import com.example.backlogium.gamification.RuleConfig
import javax.inject.Inject

/**
 * Owns the write-then-recompute invariant behind a gamification rule change.
 *
 * [GamificationUpdater] is stateless: it rebuilds total XP, every stored day's `questMet`, and
 * both streaks from raw inputs under whatever config it is handed. So *every* rule is
 * retroactive — raising the daily quest goal re-evaluates the player's entire history — and a
 * saved rule that did not trigger a recompute would leave every screen showing values derived
 * from the superseded config until the next 15-minute poll.
 *
 * The pairing lives here, not in a view model, for the same reason [PlaytimeBackfillUseCase]
 * exists: a two-step invariant in the UI layer is one a future caller can perform half of.
 * [SettingsRepository] stays a pure storage seam and never learns about the domain layer.
 */
class UpdateRuleConfigUseCase @Inject constructor(
    private val settings: SettingsRepository,
    private val gamificationUpdater: GamificationUpdater,
    private val time: TimeProvider,
) {

    /**
     * Run the real recompute under a candidate [config] and return what it *would* store,
     * writing nothing. Callers use this to state the concrete consequence of a rule change
     * before it lands, rather than approximating it — an approximation can disagree with what
     * actually happens, which is worse than no warning at all.
     *
     * The returned `longestStreak` is already floored at the stored record (see
     * [GamificationResult]), so a preview never warns about a drop that [apply] will not make.
     */
    suspend fun preview(config: RuleConfig): GamificationResult =
        gamificationUpdater.compute(time.today(), config)

    /**
     * Persist [config] and immediately recompute under it, so no screen is left displaying a
     * level, quest status, or streak derived from the previous rules. Rule changes redefine the
     * progress-event baseline and therefore never celebrate the resulting transition.
     */
    suspend fun apply(config: RuleConfig) {
        settings.setRuleConfig(config)
        gamificationUpdater.recompute(time.today(), RecomputeSource.RULE_CHANGE, config)
    }
}
