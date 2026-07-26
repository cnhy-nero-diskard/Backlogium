package com.example.backlogium.data.repo

import com.example.backlogium.data.local.SettingsDataStore
import com.example.backlogium.gamification.RuleConfig
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Read/write access to app settings — currently just the tunable gamification [RuleConfig].
 *
 * Exists so consumers above `data/` depend on a repository rather than on DataStore: settings
 * are the one piece of state a cloud-backed build would most plausibly move, and [RuleConfig]
 * is already a plain type from the pure `:gamification` module, so the contract survives the
 * storage swap unchanged.
 */
@Singleton
class SettingsRepository @Inject constructor(
    private val settings: SettingsDataStore,
) {
    val ruleConfig: Flow<RuleConfig> = settings.ruleConfigFlow

    suspend fun setRuleConfig(config: RuleConfig) = settings.setRuleConfig(config)
}
