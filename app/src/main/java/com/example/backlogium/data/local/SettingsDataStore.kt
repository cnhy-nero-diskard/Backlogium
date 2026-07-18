package com.example.backlogium.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.backlogium.BuildConfig
import com.example.backlogium.gamification.QuestMode
import com.example.backlogium.gamification.RuleConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "settings")

/**
 * App settings backed by Preferences DataStore: the effective SteamID (an optional
 * override of the build-time [BuildConfig.STEAM_ID]) and the tunable gamification
 * [RuleConfig]. Rule values fall back to [RuleConfig] defaults when unset, so a fresh
 * install already has sensible rules.
 */
@Singleton
class SettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val STEAM_ID = stringPreferencesKey("steam_id")
        val XP_PER_MINUTE = intPreferencesKey("xp_per_minute")
        val LEVEL_BASE = intPreferencesKey("level_base")
        val QUEST_THRESHOLD_MIN = intPreferencesKey("quest_threshold_min")
        val QUEST_MODE = stringPreferencesKey("quest_mode")
        val STREAK_GRACE_DAYS = intPreferencesKey("streak_grace_days")
    }

    /** The SteamID to use for requests: the stored override, else the build-time value. */
    val steamIdFlow: Flow<String> = context.dataStore.data.map { prefs ->
        val stored = prefs[Keys.STEAM_ID]
        if (!stored.isNullOrBlank()) stored else BuildConfig.STEAM_ID
    }

    val ruleConfigFlow: Flow<RuleConfig> = context.dataStore.data.map { prefs ->
        val defaults = RuleConfig()
        RuleConfig(
            xpPerMinute = prefs[Keys.XP_PER_MINUTE] ?: defaults.xpPerMinute,
            levelBase = prefs[Keys.LEVEL_BASE] ?: defaults.levelBase,
            questThresholdMin = prefs[Keys.QUEST_THRESHOLD_MIN] ?: defaults.questThresholdMin,
            questMode = prefs[Keys.QUEST_MODE]?.let { runCatching { QuestMode.valueOf(it) }.getOrNull() }
                ?: defaults.questMode,
            streakGraceDays = prefs[Keys.STREAK_GRACE_DAYS] ?: defaults.streakGraceDays,
        )
    }

    suspend fun setSteamId(steamId: String) {
        context.dataStore.edit { it[Keys.STEAM_ID] = steamId }
    }

    suspend fun setRuleConfig(config: RuleConfig) {
        context.dataStore.edit { prefs ->
            prefs[Keys.XP_PER_MINUTE] = config.xpPerMinute
            prefs[Keys.LEVEL_BASE] = config.levelBase
            prefs[Keys.QUEST_THRESHOLD_MIN] = config.questThresholdMin
            prefs[Keys.QUEST_MODE] = config.questMode.name
            prefs[Keys.STREAK_GRACE_DAYS] = config.streakGraceDays
        }
    }
}
