package com.example.backlogium.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.backlogium.domain.LibrarySortKey
import com.example.backlogium.domain.LibrarySortPrefs
import com.example.backlogium.domain.librarySortKeyOrNull
import com.example.backlogium.gamification.QuestMode
import com.example.backlogium.gamification.RuleConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "settings")

/**
 * App settings backed by Preferences DataStore: the tunable gamification [RuleConfig], plus the
 * two per-list Library sort selections ([LibrarySortPrefs]). Both fall back to their type's
 * defaults when unset, so a fresh install already has sensible rules and the Library's original
 * ordering. (Steam credentials moved to the encrypted credential store / `CredentialsRepository`.)
 *
 * Every field the Settings screen exposes needs a key here — an unkeyed field would silently
 * revert to its default on the next read, so the round-trip is what makes it editable at all.
 * The two HowLongToBeat taper constants are deliberately absent: they are not exposed.
 */
@Singleton
class SettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val XP_PER_MINUTE = intPreferencesKey("xp_per_minute")
        val LEVEL_BASE = intPreferencesKey("level_base")
        val QUEST_THRESHOLD_MIN = intPreferencesKey("quest_threshold_min")
        val QUEST_MODE = stringPreferencesKey("quest_mode")
        val STREAK_GRACE_DAYS = intPreferencesKey("streak_grace_days")
        val COMMON_ACHIEVEMENT_XP = intPreferencesKey("common_achievement_xp")
        val UNCOMMON_ACHIEVEMENT_XP = intPreferencesKey("uncommon_achievement_xp")
        val RARE_ACHIEVEMENT_XP = intPreferencesKey("rare_achievement_xp")
        val EPIC_ACHIEVEMENT_XP = intPreferencesKey("epic_achievement_xp")
        val LEGENDARY_ACHIEVEMENT_XP = intPreferencesKey("legendary_achievement_xp")
        val LIBRARY_FOCUS_SORT = stringPreferencesKey("library_focus_sort")
        val LIBRARY_ALL_SORT = stringPreferencesKey("library_all_sort")
        val AUTO_SNAPSHOT_ENABLED = booleanPreferencesKey("auto_snapshot_enabled")
        val SNAPSHOT_RETENTION_COUNT = intPreferencesKey("snapshot_retention_count")
        val SNAPSHOT_INTERVAL_HOURS = intPreferencesKey("snapshot_interval_hours")
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
            commonAchievementXp = prefs[Keys.COMMON_ACHIEVEMENT_XP] ?: defaults.commonAchievementXp,
            uncommonAchievementXp = prefs[Keys.UNCOMMON_ACHIEVEMENT_XP]
                ?: defaults.uncommonAchievementXp,
            rareAchievementXp = prefs[Keys.RARE_ACHIEVEMENT_XP] ?: defaults.rareAchievementXp,
            epicAchievementXp = prefs[Keys.EPIC_ACHIEVEMENT_XP] ?: defaults.epicAchievementXp,
            legendaryAchievementXp = prefs[Keys.LEGENDARY_ACHIEVEMENT_XP]
                ?: defaults.legendaryAchievementXp,
        )
    }

    suspend fun setRuleConfig(config: RuleConfig) {
        context.dataStore.edit { prefs ->
            prefs[Keys.XP_PER_MINUTE] = config.xpPerMinute
            prefs[Keys.LEVEL_BASE] = config.levelBase
            prefs[Keys.QUEST_THRESHOLD_MIN] = config.questThresholdMin
            prefs[Keys.QUEST_MODE] = config.questMode.name
            prefs[Keys.STREAK_GRACE_DAYS] = config.streakGraceDays
            prefs[Keys.COMMON_ACHIEVEMENT_XP] = config.commonAchievementXp
            prefs[Keys.UNCOMMON_ACHIEVEMENT_XP] = config.uncommonAchievementXp
            prefs[Keys.RARE_ACHIEVEMENT_XP] = config.rareAchievementXp
            prefs[Keys.EPIC_ACHIEVEMENT_XP] = config.epicAchievementXp
            prefs[Keys.LEGENDARY_ACHIEVEMENT_XP] = config.legendaryAchievementXp
        }
    }

    /**
     * The two Library sort selections. Unset keys resolve to [LibrarySortPrefs]'s defaults, which
     * reproduce the DAO's own ordering — so an upgrade renders exactly as before.
     */
    val librarySortFlow: Flow<LibrarySortPrefs> = context.dataStore.data.map { prefs ->
        val defaults = LibrarySortPrefs()
        LibrarySortPrefs(
            focus = librarySortKeyOrNull(prefs[Keys.LIBRARY_FOCUS_SORT]) ?: defaults.focus,
            library = librarySortKeyOrNull(prefs[Keys.LIBRARY_ALL_SORT]) ?: defaults.library,
        )
    }

    suspend fun setFocusSort(key: LibrarySortKey) {
        context.dataStore.edit { it[Keys.LIBRARY_FOCUS_SORT] = key.name }
    }

    suspend fun setLibrarySort(key: LibrarySortKey) {
        context.dataStore.edit { it[Keys.LIBRARY_ALL_SORT] = key.name }
    }

    /**
     * Automatic rolling snapshot configuration (add-backup-restore): on by default, retaining 7
     * snapshots at a minimum 24-hour interval between writes.
     */
    val autoSnapshotSettingsFlow: Flow<AutoSnapshotSettings> = context.dataStore.data.map { prefs ->
        AutoSnapshotSettings(
            enabled = prefs[Keys.AUTO_SNAPSHOT_ENABLED] ?: true,
            retentionCount = prefs[Keys.SNAPSHOT_RETENTION_COUNT] ?: 7,
            intervalHours = prefs[Keys.SNAPSHOT_INTERVAL_HOURS] ?: 24,
        )
    }

    suspend fun setAutoSnapshotEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_SNAPSHOT_ENABLED] = enabled }
    }

    suspend fun setSnapshotRetentionCount(count: Int) {
        context.dataStore.edit { it[Keys.SNAPSHOT_RETENTION_COUNT] = count }
    }

    suspend fun setSnapshotIntervalHours(hours: Int) {
        context.dataStore.edit { it[Keys.SNAPSHOT_INTERVAL_HOURS] = hours }
    }
}

/** Auto-snapshot toggle, retention count, and minimum interval between writes (in hours). */
data class AutoSnapshotSettings(
    val enabled: Boolean = true,
    val retentionCount: Int = 7,
    val intervalHours: Int = 24,
)
