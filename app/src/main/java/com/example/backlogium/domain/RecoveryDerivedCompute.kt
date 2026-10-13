package com.example.backlogium.domain

import com.example.backlogium.gamification.RuleConfig
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The staged compute/persist port the finalizer drives (recover-missed-playtime, design.md §8).
 *
 * Deliberately an interface over the *real* [GamificationUpdater]: there is exactly one XP author
 * on device, and both derived stages must be that author's public `compute`/`persist` executed
 * with the operation's captured rule config and provenance. Nothing here recomputes XP; it only
 * selects which pass of [GamificationUpdater] a stage is.
 */
interface RecoveryDerivedCompute {

    /**
     * The corrective baseline: the derived values with this operation's *fresh earned inputs*
     * excluded, to be persisted under [RecomputeSource.RETROACTIVE_PLAY].
     *
     * [excludedEarned] names the exact operation-owned earned inputs to subtract — fresh
     * contribution IDs (with revision and authorized minutes), their frozen dated quest credits,
     * and fresh achievement (app id + api name) claims — never a whole game or a whole API name.
     * See [recoveryCorrectiveCreditedByGame] and [recoveryCorrectiveDailyProgress] for the exact
     * XP and dated-input subtraction contracts.
     */
    suspend fun computeCorrective(
        today: LocalDate,
        config: RuleConfig,
        excludedEarned: RecoveryEarnedInputs,
    ): GamificationResult

    /** The ordinary compute including every fresh earned input, persisted under `SYNC`. */
    suspend fun computeEarned(today: LocalDate, config: RuleConfig): GamificationResult

    /**
     * The derived write through the existing WAL/recovery protocol. [operationId] names the
     * journal this staged persist belongs to so the write-ahead record can be attributed to it;
     * null for an ordinary sync.
     */
    suspend fun persist(
        result: GamificationResult,
        source: RecomputeSource,
        configVersion: Long,
        operationId: String?,
    )
}

/**
 * Production [RecoveryDerivedCompute] wired to the real [GamificationUpdater].
 *
 * Mixed operations call the same [GamificationUpdater.compute] with an exact contribution-identity
 * and achievement-pair exclusion set. This never removes a whole game or a globally ambiguous
 * achievement API name, and every stage remains authored by the existing gamification engine.
 */
@Singleton
class GamificationRecoveryDerivedCompute @Inject constructor(
    private val updater: GamificationUpdater,
) : RecoveryDerivedCompute {

    override suspend fun computeEarned(
        today: LocalDate,
        config: RuleConfig,
    ): GamificationResult = updater.compute(today, config)

    override suspend fun computeCorrective(
        today: LocalDate,
        config: RuleConfig,
        excludedEarned: RecoveryEarnedInputs,
    ): GamificationResult {
        return updater.compute(today, config, excludeEarned = excludedEarned)
    }

    override suspend fun persist(
        result: GamificationResult,
        source: RecomputeSource,
        configVersion: Long,
        operationId: String?,
    ) {
        updater.persist(result, source, configVersion, operationId)
    }
}
