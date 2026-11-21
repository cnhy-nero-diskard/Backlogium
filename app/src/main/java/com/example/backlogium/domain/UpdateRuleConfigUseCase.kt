package com.example.backlogium.domain

import com.example.backlogium.data.local.dao.PlayerProfileDao
import com.example.backlogium.data.repo.SettingsRepository
import com.example.backlogium.gamification.RuleConfig
import com.example.backlogium.work.SteamSyncCoordinator
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
class UpdateRuleConfigUseCase private constructor(
    private val settings: SettingsRepository,
    private val gamificationUpdater: GamificationUpdater,
    private val time: TimeProvider,
    private val derivedStateWrites: DerivedStateWriteCoordinator,
    private val recoveryBoundary: RecoveryBoundary?,
) {

    /** Production constructor: finish any interrupted earned/corrective operation before rules move. */
    @Inject constructor(
        settings: SettingsRepository,
        gamificationUpdater: GamificationUpdater,
        time: TimeProvider,
        derivedStateWrites: DerivedStateWriteCoordinator,
        syncCoordinator: SteamSyncCoordinator,
        playerProfileDao: PlayerProfileDao,
        materializer: UnknownTimeMaterializer,
        recoveryFinalizer: RecoveryDerivedFinalizer,
    ) : this(
        settings = settings,
        gamificationUpdater = gamificationUpdater,
        time = time,
        derivedStateWrites = derivedStateWrites,
        recoveryBoundary = RecoveryBoundary(
            syncCoordinator = syncCoordinator,
            playerProfileDao = playerProfileDao,
            materializer = materializer,
            finalizer = recoveryFinalizer,
        ),
    )

    /** Narrow direct-construction seam for platform-free rule-math tests without recovery storage. */
    constructor(
        settings: SettingsRepository,
        gamificationUpdater: GamificationUpdater,
        time: TimeProvider,
        derivedStateWrites: DerivedStateWriteCoordinator = DerivedStateWriteCoordinator(),
    ) : this(settings, gamificationUpdater, time, derivedStateWrites, recoveryBoundary = null)

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
        val boundary = recoveryBoundary
        if (boundary == null) {
            persistRuleAndRecompute(config)
        } else {
            // A rule recompute is a non-earned baseline. If a process died with an operation
            // between its corrective and earned stages, applying rules first could mark that
            // operation's fresh threshold as historical. Drain pending stages under the shared
            // sync barrier before taking the ordinary derived-write lock and moving the version.
            boundary.syncCoordinator.withLock {
                val account = boundary.playerProfileDao.get()?.steamId?.trim()
                    ?.takeIf { it.isNotEmpty() }
                if (account != null) {
                    val now = time.nowMillis()
                    boundary.materializer.materializeAll(account, now)
                    boundary.finalizer.resumePending(account, now)
                }
                persistRuleAndRecompute(config)
            }
        }
    }

    private suspend fun persistRuleAndRecompute(config: RuleConfig) = derivedStateWrites.withLock {
        val stored = settings.setRuleConfigAndGetVersion(config)
        gamificationUpdater.recompute(
            today = time.today(),
            source = RecomputeSource.RULE_CHANGE,
            config = stored.config,
            configVersion = stored.version,
        )
    }

    private data class RecoveryBoundary(
        val syncCoordinator: SteamSyncCoordinator,
        val playerProfileDao: PlayerProfileDao,
        val materializer: UnknownTimeMaterializer,
        val finalizer: RecoveryDerivedFinalizer,
    )
}
