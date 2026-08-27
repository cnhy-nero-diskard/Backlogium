package com.example.backlogium.domain

import com.example.backlogium.data.local.SettingsDataStore
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes derived values — XP, levels, streaks, quests — after a poll's raw commit, through the
 * existing cross-store write-ahead protocol and under the version check that refuses a candidate
 * computed against superseded rules.
 *
 * Shared by every observer that commits playtime, so a targeted post-play fetch triggers no second
 * derivation of its own: `CLAUDE.md`'s invariant is that the on-device engine is the sole author of
 * derived values, and one author means one call site, not two that agree today.
 */
@Singleton
class SyncDerivedStateWriter @Inject constructor(
    private val settings: SettingsDataStore,
    private val gamificationUpdater: GamificationUpdater,
    private val derivedStateWrites: DerivedStateWriteCoordinator,
) {
    /**
     * The rule configuration to compute against, read *before* the raw commit so
     * [persist] can tell a rules change apart from its own write.
     */
    suspend fun configuration(): VersionedRuleConfig = settings.ruleConfigWithVersionFlow.first()

    suspend fun persist(today: LocalDate, initialConfig: VersionedRuleConfig) {
        persistVersionChecked(
            initial = initialConfig,
            readCurrent = { settings.ruleConfigWithVersionFlow.first() },
            compute = { config -> gamificationUpdater.compute(today, config) },
            persist = { result, version ->
                gamificationUpdater.persist(result, RecomputeSource.SYNC, version)
            },
            coordinator = derivedStateWrites,
        )
    }
}
