package com.example.backlogium.domain

import com.example.backlogium.data.local.dao.GameDao
import com.example.backlogium.data.repo.HiddenGamesRepository
import com.example.backlogium.data.repo.SettingsRepository
import javax.inject.Inject

/**
 * What a candidate visibility change will actually do, in the terms the player is asked to accept.
 *
 * Every number here comes from running the real recompute against the candidate hidden set (see
 * [GameVisibilityUseCase.previewHide]) rather than from estimating one — an approximation that
 * disagrees with what happens is worse than no warning at all, the same standing position
 * `app-settings` takes for rule changes.
 */
data class VisibilityChangeEffect(
    /** The games this change applies to. */
    val appIds: List<Long>,
    /** Their names, for a disclosure that says what is about to disappear. */
    val names: List<String>,
    /** True for a hide, false for an unhide; the disclosure reads differently for each. */
    val hiding: Boolean,
    val totalXpBefore: Int,
    val totalXpAfter: Int,
    val levelBefore: Int,
    val levelAfter: Int,
    /** Names of games whose goal designation this change will clear. Empty when none applies. */
    val clearedGoalNames: List<String>,
) {
    /** True when the change lowers the level — stated explicitly, never left to be discovered. */
    val levelDrops: Boolean get() = levelAfter < levelBefore

    /** True when nothing derived moves, e.g. hiding a game that was never played. */
    val noDerivedChange: Boolean get() = totalXpAfter == totalXpBefore && levelAfter == levelBefore
}

/**
 * Owns the disclose-then-apply invariant behind hiding and unhiding a game (add-hidden-games).
 *
 * Hiding is retroactive: XP and level are rebuilt without the hidden game's minutes and
 * achievements, which can visibly lower the level. That is acceptable to offer only because the
 * consequence is stated in concrete, computed terms first and because it is fully reversible —
 * nothing is deleted, so unhiding restores exactly the values that would have applied had the game
 * never been hidden.
 *
 * The pairing lives here rather than in a view model for the reason [PlaytimeBackfillUseCase] and
 * [UpdateRuleConfigUseCase] exist: a two-step invariant in the UI layer is one a future caller can
 * perform half of, and half of this one is a hidden game still feeding a level nothing can explain.
 */
class GameVisibilityUseCase @Inject constructor(
    private val hiddenGames: HiddenGamesRepository,
    private val gamificationUpdater: GamificationUpdater,
    private val gameDao: GameDao,
    private val settings: SettingsRepository,
    private val time: TimeProvider,
    private val derivedStateWrites: DerivedStateWriteCoordinator,
) {

    /**
     * What hiding [appIds] would do, computed rather than estimated. Nothing is written.
     *
     * Both sides of the comparison come from the same computation — the "before" is recomputed
     * under the *current* hidden set rather than read from the stored profile — so the two numbers
     * are always comparable even if a sync landed between the last recompute and this preview.
     */
    suspend fun previewHide(appIds: Collection<Long>): VisibilityChangeEffect =
        preview(appIds, hiding = true)

    /** What unhiding [appIds] would do. Disclosed for the same reason a hide is. */
    suspend fun previewUnhide(appIds: Collection<Long>): VisibilityChangeEffect =
        preview(appIds, hiding = false)

    /**
     * Hide [appIds] and recompute. [fromBulkAction] records that the hide came from the non-game
     * review, which the hidden list shows; it changes nothing about the hide itself.
     *
     * A goal designation is cleared here, because a goal whose progress no surface reports is
     * incoherent — disclosed in the same confirmation as the XP effect, being the same kind of
     * consequence.
     */
    suspend fun hide(appIds: Collection<Long>, fromBulkAction: Boolean = false) {
        if (appIds.isEmpty()) return
        derivedStateWrites.withLock {
            hiddenGames.hide(appIds, fromBulkAction)
            appIds.forEach { gameDao.setGoalFlag(it, isGoal = false) }
            recompute()
        }
    }

    /**
     * Unhide [appIds] and recompute, restoring their playtime, achievements, history, and
     * collection memberships — none of which were ever removed.
     *
     * The goal flag is cleared here too, which is not a second hide's worth of intent: hiding
     * clears it and unhiding must not restore it (re-declaring a goal is one tap; silently
     * reinstating one the player may have moved on from is the worse default). Clearing it on this
     * side as well makes that guarantee hold even if a hide was interrupted between its two writes.
     */
    suspend fun unhide(appIds: Collection<Long>) {
        if (appIds.isEmpty()) return
        derivedStateWrites.withLock {
            hiddenGames.unhide(appIds)
            appIds.forEach { gameDao.setGoalFlag(it, isGoal = false) }
            recompute()
        }
    }

    /** Unhide everything currently hidden. */
    suspend fun unhideAll() {
        val hidden = hiddenGames.hiddenAppIdSet()
        if (hidden.isEmpty()) return
        unhide(hidden)
    }

    private suspend fun preview(
        appIds: Collection<Long>,
        hiding: Boolean,
    ): VisibilityChangeEffect {
        val targets = appIds.distinct()
        val rules = settings.readRuleConfigWithVersion()
        val today = time.today()
        val currentlyHidden = hiddenGames.hiddenAppIdSet()
        val candidate = if (hiding) currentlyHidden + targets else currentlyHidden - targets.toSet()

        val before = gamificationUpdater.compute(today, rules.config, hiddenAppIds = currentlyHidden)
        val after = gamificationUpdater.compute(today, rules.config, hiddenAppIds = candidate)

        val games = targets.mapNotNull { gameDao.getById(it) }
        return VisibilityChangeEffect(
            appIds = targets,
            names = targets.map { appId ->
                games.firstOrNull { it.appId == appId }?.name?.takeIf { name -> name.isNotBlank() }
                    ?: "App $appId"
            },
            hiding = hiding,
            totalXpBefore = before.xpState.totalXp,
            totalXpAfter = after.xpState.totalXp,
            levelBefore = before.xpState.level,
            levelAfter = after.xpState.level,
            // Only a hide can clear a goal: a hidden game's goal flag is already off.
            clearedGoalNames = if (hiding) games.filter { it.isGoal }.map { it.name } else emptyList(),
        )
    }

    private suspend fun recompute() {
        val rules = settings.readRuleConfigWithVersion()
        gamificationUpdater.recompute(
            today = time.today(),
            source = RecomputeSource.VISIBILITY_CHANGE,
            config = rules.config,
            configVersion = rules.version,
        )
    }
}
