package com.example.backlogium.data.local

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.backlogium.domain.GameListDensity
import com.example.backlogium.domain.LibrarySortKey
import com.example.backlogium.domain.LibrarySortDirection
import com.example.backlogium.domain.LibrarySortPrefs
import com.example.backlogium.domain.PendingStreakBreak
import com.example.backlogium.domain.PendingTransition
import com.example.backlogium.domain.ProgressMarks
import com.example.backlogium.domain.RecomputeSource
import com.example.backlogium.domain.VersionedRuleConfig
import com.example.backlogium.domain.librarySortDirectionOrNull
import com.example.backlogium.domain.librarySortKeyOrNull
import com.example.backlogium.gamification.QuestMode
import com.example.backlogium.gamification.RuleConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
        val LIBRARY_FOCUS_SORT_DIRECTION = stringPreferencesKey("library_focus_sort_direction")
        val LIBRARY_ALL_SORT_DIRECTION = stringPreferencesKey("library_all_sort_direction")
        val LIBRARY_DENSITY = stringPreferencesKey("library_density")
        val COLLECTION_DENSITY = stringPreferencesKey("collection_density")
        val AUTO_SNAPSHOT_ENABLED = booleanPreferencesKey("auto_snapshot_enabled")
        val SNAPSHOT_RETENTION_COUNT = intPreferencesKey("snapshot_retention_count")
        val SNAPSHOT_INTERVAL_HOURS = intPreferencesKey("snapshot_interval_hours")
        val LIVE_SESSION_APP_ID = longPreferencesKey("live_session_app_id")
        val LIVE_SESSION_STARTED_AT = longPreferencesKey("live_session_started_at")
        val NOTIFICATION_PERMISSION_REQUESTED =
            booleanPreferencesKey("notification_permission_requested")
        val LIVE_MONITOR_ENABLED = booleanPreferencesKey("live_monitor_enabled")
        val LIVE_MONITORING_AVAILABILITY = stringPreferencesKey("live_monitoring_availability")
        val RULE_CONFIG_VERSION = longPreferencesKey("rule_config_version")

        /**
         * Guard for the one-time correction of daily totals recorded under poll-time attribution
         * (auditfix-day-attribution Decision 7). In DataStore rather than on the profile row so the
         * correction needs no schema migration; absent means "not yet applied", which is also the
         * right answer for a fresh install with nothing to correct.
         */
        val DAILY_PROGRESS_BACKFILLED = booleanPreferencesKey("daily_progress_backfilled")

        /** One-time guard for removing request identifiers written before endpoint normalization. */
        val DIAGNOSTIC_IDENTIFIERS_NORMALIZED =
            booleanPreferencesKey("diagnostic_identifiers_normalized")

        // Progress-event presentation state, not user-editable settings. These marks are the
        // durable acknowledgement baseline and intentionally live in DataStore, not Room.
        val LAST_CELEBRATED_LEVEL = intPreferencesKey("last_celebrated_level")
        val LAST_CELEBRATED_STREAK_MILESTONE =
            intPreferencesKey("last_celebrated_streak_milestone")
        val LAST_QUEST_CELEBRATED_DATE = stringPreferencesKey("last_quest_celebrated_date")
        val LAST_STREAK_BROKEN_DATE = stringPreferencesKey("last_streak_broken_date")

        // Quest dates an earned recompute actually earned and no consumer has acknowledged. A set
        // rather than a single date because several days can be owed at once, and durable rather
        // than re-derived because a stored `questMet` row is not evidence a quest was ever earned.
        val PENDING_QUEST_DATES = stringSetPreferencesKey("pending_quest_dates")

        // Write-ahead record of an in-flight persist() call, written before its Room write and
        // cleared after its marks are finalized. Presence of PENDING_TRANSITION_SOURCE is what
        // marks recovery needs to resolve after a crash between the two.
        val PENDING_TRANSITION_SOURCE = stringPreferencesKey("pending_transition_source")
        val PENDING_TRANSITION_LEVEL = intPreferencesKey("pending_transition_level")
        val PENDING_TRANSITION_STREAK = intPreferencesKey("pending_transition_streak")
        val PENDING_TRANSITION_QUEST_MET = booleanPreferencesKey("pending_transition_quest_met")
        val PENDING_TRANSITION_DATE = stringPreferencesKey("pending_transition_date")

        // The most recent acquiring poll's announcement (add-library-recency-signals). One batch,
        // replaced rather than accumulated: buying more games is new information, and a dismissal
        // twenty hours ago was about different games. In DataStore rather than as a progress event
        // because `progress-events` is restricted to earned progress, and buying a game is not
        // earned progress.
        val ACQUIRED_AT = longPreferencesKey("acquired_batch_at")
        val ACQUIRED_APP_IDS = stringSetPreferencesKey("acquired_batch_app_ids")
        val ACQUIRED_DISMISSED = booleanPreferencesKey("acquired_batch_dismissed")
    }

    val ruleConfigWithVersionFlow: Flow<VersionedRuleConfig> = context.dataStore.data.map { prefs ->
        val defaults = RuleConfig()
        VersionedRuleConfig(
            config = RuleConfig(
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
            ),
            version = prefs[Keys.RULE_CONFIG_VERSION] ?: 0L,
        )
    }

    val ruleConfigFlow: Flow<RuleConfig> = ruleConfigWithVersionFlow.map { it.config }

    suspend fun setRuleConfig(config: RuleConfig) {
        setRuleConfigAndGetVersion(config)
    }

    /** Atomically writes the rules and advances their monotonic provenance version. */
    suspend fun setRuleConfigAndGetVersion(config: RuleConfig): VersionedRuleConfig {
        lateinit var result: VersionedRuleConfig
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
            val version = (prefs[Keys.RULE_CONFIG_VERSION] ?: 0L) + 1L
            prefs[Keys.RULE_CONFIG_VERSION] = version
            result = VersionedRuleConfig(config, version)
        }
        return result
    }

    /** Durable progress-event marks. Unset level/streak keys mean no baseline has been seeded yet. */
    val progressMarksFlow: Flow<ProgressMarks> = context.dataStore.data.map(::decodeProgressMarks)

    suspend fun readProgressMarks(): ProgressMarks = progressMarksFlow.first()

    suspend fun writeProgressMarks(marks: ProgressMarks) {
        context.dataStore.edit { prefs -> encodeProgressMarks(prefs, marks) }
    }

    /**
     * Atomically replace the stored marks with `transform(current)` inside a single DataStore
     * `edit {}` transaction, so a concurrent [updateProgressMarks]/[writeProgressMarks] call can
     * never be lost to a stale read-modify-write race — DataStore serializes `edit {}` calls
     * against the same file, each seeing the latest committed state.
     */
    suspend fun updateProgressMarks(
        transform: (ProgressMarks) -> ProgressMarks,
    ): ProgressMarks {
        lateinit var result: ProgressMarks
        context.dataStore.edit { prefs ->
            result = transform(decodeProgressMarks(prefs))
            encodeProgressMarks(prefs, result)
        }
        return result
    }

    private fun decodeProgressMarks(prefs: Preferences): ProgressMarks {
        val rawBreak = prefs[Keys.LAST_STREAK_BROKEN_DATE]
        val pendingBreak = parsePendingBreak(rawBreak)
        return ProgressMarks(
            lastCelebratedLevel = prefs[Keys.LAST_CELEBRATED_LEVEL] ?: 0,
            lastCelebratedStreakMilestone = prefs[Keys.LAST_CELEBRATED_STREAK_MILESTONE] ?: 0,
            lastQuestCelebratedDate = parseDate(prefs[Keys.LAST_QUEST_CELEBRATED_DATE]),
            lastStreakBrokenDate = if (pendingBreak == null) parseDate(rawBreak) else null,
            initialized = prefs.contains(Keys.LAST_CELEBRATED_LEVEL) ||
                prefs.contains(Keys.LAST_CELEBRATED_STREAK_MILESTONE),
            pendingStreakBreak = pendingBreak,
            pendingTransition = parsePendingTransition(prefs),
            pendingQuestDates = parsePendingQuestDates(prefs),
        )
    }

    /**
     * Read back oldest-first so delivery order is a property of the stored value rather than of the
     * consumer that happens to iterate it. Unparseable entries are dropped: a corrupt date can only
     * ever produce an undeliverable event.
     */
    private fun parsePendingQuestDates(prefs: Preferences): Set<LocalDate> =
        prefs[Keys.PENDING_QUEST_DATES]
            ?.mapNotNull(::parseDate)
            ?.sorted()
            ?.toCollection(LinkedHashSet())
            ?: emptySet()

    private fun encodeProgressMarks(
        prefs: MutablePreferences,
        marks: ProgressMarks,
    ) {
        prefs[Keys.LAST_CELEBRATED_LEVEL] = marks.lastCelebratedLevel
        prefs[Keys.LAST_CELEBRATED_STREAK_MILESTONE] = marks.lastCelebratedStreakMilestone
        writeNullableString(
            prefs,
            Keys.LAST_QUEST_CELEBRATED_DATE,
            marks.lastQuestCelebratedDate?.toString(),
        )
        val breakValue = marks.pendingStreakBreak?.let {
            "$PENDING_BREAK_PREFIX${it.date}|${it.previousLength}"
        } ?: marks.lastStreakBrokenDate?.toString()
        writeNullableString(prefs, Keys.LAST_STREAK_BROKEN_DATE, breakValue)

        if (marks.pendingQuestDates.isEmpty()) {
            prefs.remove(Keys.PENDING_QUEST_DATES)
        } else {
            prefs[Keys.PENDING_QUEST_DATES] =
                marks.pendingQuestDates.map(LocalDate::toString).toSet()
        }

        val pending = marks.pendingTransition
        if (pending == null) {
            prefs.remove(Keys.PENDING_TRANSITION_SOURCE)
            prefs.remove(Keys.PENDING_TRANSITION_LEVEL)
            prefs.remove(Keys.PENDING_TRANSITION_STREAK)
            prefs.remove(Keys.PENDING_TRANSITION_QUEST_MET)
            prefs.remove(Keys.PENDING_TRANSITION_DATE)
        } else {
            prefs[Keys.PENDING_TRANSITION_SOURCE] = pending.source.name
            prefs[Keys.PENDING_TRANSITION_LEVEL] = pending.previousLevel
            prefs[Keys.PENDING_TRANSITION_STREAK] = pending.previousStreak
            prefs[Keys.PENDING_TRANSITION_QUEST_MET] = pending.previousTodayQuestMet
            prefs[Keys.PENDING_TRANSITION_DATE] = pending.evaluationDate.toString()
        }
    }

    private fun parsePendingTransition(
        prefs: Preferences,
    ): PendingTransition? {
        val source = prefs[Keys.PENDING_TRANSITION_SOURCE]
            ?.let { runCatching { RecomputeSource.valueOf(it) }.getOrNull() } ?: return null
        val date = parseDate(prefs[Keys.PENDING_TRANSITION_DATE]) ?: return null
        return PendingTransition(
            source = source,
            previousLevel = prefs[Keys.PENDING_TRANSITION_LEVEL] ?: 0,
            previousStreak = prefs[Keys.PENDING_TRANSITION_STREAK] ?: 0,
            previousTodayQuestMet = prefs[Keys.PENDING_TRANSITION_QUEST_MET] ?: false,
            evaluationDate = date,
        )
    }

    /**
     * The two Library sort selections, key and direction each. Unset keys resolve to
     * [LibrarySortPrefs]'s defaults, which reproduce the DAO's own ordering — so an upgrade renders
     * exactly as before. An absent *direction* resolves to whichever key is in effect, not to a
     * stored one, so changing the key of a list the user never reversed keeps it on that key's
     * natural end.
     */
    val librarySortFlow: Flow<LibrarySortPrefs> = context.dataStore.data.map { prefs ->
        val defaults = LibrarySortPrefs()
        val focus = librarySortKeyOrNull(prefs[Keys.LIBRARY_FOCUS_SORT]) ?: defaults.focus
        val library = librarySortKeyOrNull(prefs[Keys.LIBRARY_ALL_SORT]) ?: defaults.library
        LibrarySortPrefs(
            focus = focus,
            library = library,
            focusDirection = librarySortDirectionOrNull(prefs[Keys.LIBRARY_FOCUS_SORT_DIRECTION])
                ?: focus.defaultDirection,
            libraryDirection = librarySortDirectionOrNull(prefs[Keys.LIBRARY_ALL_SORT_DIRECTION])
                ?: library.defaultDirection,
        )
    }

    suspend fun setFocusSort(key: LibrarySortKey) {
        context.dataStore.edit { it[Keys.LIBRARY_FOCUS_SORT] = key.name }
    }

    suspend fun setLibrarySort(key: LibrarySortKey) {
        context.dataStore.edit { it[Keys.LIBRARY_ALL_SORT] = key.name }
    }

    suspend fun setFocusSortDirection(direction: LibrarySortDirection) {
        context.dataStore.edit { it[Keys.LIBRARY_FOCUS_SORT_DIRECTION] = direction.name }
    }

    suspend fun setLibrarySortDirection(direction: LibrarySortDirection) {
        context.dataStore.edit { it[Keys.LIBRARY_ALL_SORT_DIRECTION] = direction.name }
    }

    /** Each surface owns its presentation preference; an unset or stale value is the old list. */
    val libraryDensityFlow: Flow<GameListDensity> = context.dataStore.data.map { prefs ->
        GameListDensity.fromStored(prefs[Keys.LIBRARY_DENSITY])
    }

    val collectionDensityFlow: Flow<GameListDensity> = context.dataStore.data.map { prefs ->
        GameListDensity.fromStored(prefs[Keys.COLLECTION_DENSITY])
    }

    suspend fun setLibraryDensity(density: GameListDensity) {
        context.dataStore.edit { it[Keys.LIBRARY_DENSITY] = density.name }
    }

    suspend fun setCollectionDensity(density: GameListDensity) {
        context.dataStore.edit { it[Keys.COLLECTION_DENSITY] = density.name }
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

    /**
     * The live now-playing session's (appId, startedAt) pair (enhance-now-playing) — the one
     * exception to `live-status`'s no-persistence rule, since an elapsed-time display must survive
     * app restart. Absent by default, so a fresh install (or a player not currently in a game)
     * behaves exactly as before this existed.
     */
    val liveSessionFlow: Flow<LiveSessionState> = context.dataStore.data.map { prefs ->
        LiveSessionState(
            appId = prefs[Keys.LIVE_SESSION_APP_ID],
            startedAt = prefs[Keys.LIVE_SESSION_STARTED_AT],
        )
    }

    /** [appId] is nullable: Steam's running-game id can fail to parse while still in a game. */
    suspend fun setLiveSession(appId: Long?, startedAt: Long) {
        context.dataStore.edit { prefs ->
            if (appId != null) prefs[Keys.LIVE_SESSION_APP_ID] = appId else prefs.remove(Keys.LIVE_SESSION_APP_ID)
            prefs[Keys.LIVE_SESSION_STARTED_AT] = startedAt
        }
    }

    suspend fun clearLiveSession() {
        context.dataStore.edit { prefs ->
            prefs.remove(Keys.LIVE_SESSION_APP_ID)
            prefs.remove(Keys.LIVE_SESSION_STARTED_AT)
        }
    }

    /**
     * The most recent acquiring poll's announcement batch. Absent by default, so a fresh install
     * and an install that has never acquired anything both read as "nothing to announce".
     *
     * Deliberately not exported in a backup: the banner belongs to a poll that observed previously
     * unknown games on *this* device, so a restore must not re-announce another device's purchase
     * or last week's.
     */
    val acquiredGamesFlow: Flow<AcquiredGamesAnnouncement> = context.dataStore.data.map { prefs ->
        AcquiredGamesAnnouncement(
            appIds = prefs[Keys.ACQUIRED_APP_IDS].orEmpty().mapNotNull(String::toLongOrNull).toSet(),
            acquiredAt = prefs[Keys.ACQUIRED_AT] ?: 0L,
            dismissed = prefs[Keys.ACQUIRED_DISMISSED] ?: false,
        )
    }

    /**
     * Replace the announcement with a later poll's arrivals, clearing the dismissal along with it.
     *
     * Callers must not invoke this for a poll that stamped no arrivals: an empty batch would
     * overwrite a live announcement the player has not seen yet with nothing to show.
     */
    suspend fun setAcquiredGames(appIds: Set<Long>, acquiredAt: Long) {
        context.dataStore.edit { prefs ->
            prefs[Keys.ACQUIRED_APP_IDS] = appIds.mapTo(mutableSetOf(), Long::toString)
            prefs[Keys.ACQUIRED_AT] = acquiredAt
            prefs[Keys.ACQUIRED_DISMISSED] = false
        }
    }

    /** Dismiss the current batch. The flag is per-batch: a later acquisition clears it again. */
    suspend fun setAcquiredGamesDismissed() {
        context.dataStore.edit { it[Keys.ACQUIRED_DISMISSED] = true }
    }

    /**
     * Clear account-derived DataStore state while retaining rules, UI preferences, backup
     * settings, and one-time installation migrations. The Room half of an account reset is
     * protected by the same durable account-change marker, so repeating this operation is safe.
     */
    suspend fun clearAccountDerivedState() {
        context.dataStore.edit { prefs ->
            prefs.remove(Keys.LIVE_SESSION_APP_ID)
            prefs.remove(Keys.LIVE_SESSION_STARTED_AT)
            prefs.remove(Keys.LAST_CELEBRATED_LEVEL)
            prefs.remove(Keys.LAST_CELEBRATED_STREAK_MILESTONE)
            prefs.remove(Keys.LAST_QUEST_CELEBRATED_DATE)
            prefs.remove(Keys.LAST_STREAK_BROKEN_DATE)
            prefs.remove(Keys.PENDING_QUEST_DATES)
            prefs.remove(Keys.PENDING_TRANSITION_SOURCE)
            prefs.remove(Keys.PENDING_TRANSITION_LEVEL)
            prefs.remove(Keys.PENDING_TRANSITION_STREAK)
            prefs.remove(Keys.PENDING_TRANSITION_QUEST_MET)
            prefs.remove(Keys.PENDING_TRANSITION_DATE)
            prefs.remove(Keys.ACQUIRED_APP_IDS)
            prefs.remove(Keys.ACQUIRED_AT)
            prefs.remove(Keys.ACQUIRED_DISMISSED)
        }
    }

    /**
     * Whether the runtime notification permission has already been put to the user. Recorded rather
     * than inferred: a plain "not granted" check can't tell never-asked from declined, and Android
     * only stops showing the dialog after the *second* refusal — so without this the user gets
     * prompted twice before the system takes the hint.
     */
    val notificationPermissionRequestedFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.NOTIFICATION_PERMISSION_REQUESTED] ?: false
    }

    suspend fun setNotificationPermissionRequested() {
        context.dataStore.edit { it[Keys.NOTIFICATION_PERMISSION_REQUESTED] = true }
    }

    /**
     * Explicit opt-in for the foreground service to poll while no game is running. Off by default:
     * this mode has an ongoing notification and consumes network/battery while armed.
     */
    val liveMonitorEnabledFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.LIVE_MONITOR_ENABLED] ?: false
    }

    suspend fun setLiveMonitorEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.LIVE_MONITOR_ENABLED] = enabled
            if (!enabled) prefs.remove(Keys.LIVE_MONITORING_AVAILABILITY)
        }
    }

    /**
     * Durable state for a monitor that could not remain available in the background. The absence
     * of a value is the normal/available state, so old installs and fresh installs stay quiet.
     */
    val liveMonitoringAvailabilityFlow: Flow<PresenceMonitoringAvailability> =
        context.dataStore.data.map { prefs ->
            prefs[Keys.LIVE_MONITORING_AVAILABILITY]
                ?.let { raw -> runCatching { PresenceMonitoringAvailability.valueOf(raw) }.getOrNull() }
                ?: PresenceMonitoringAvailability.AVAILABLE
        }

    suspend fun setLiveMonitoringAvailability(availability: PresenceMonitoringAvailability) {
        context.dataStore.edit { prefs ->
            if (availability == PresenceMonitoringAvailability.AVAILABLE) {
                prefs.remove(Keys.LIVE_MONITORING_AVAILABILITY)
            } else {
                prefs[Keys.LIVE_MONITORING_AVAILABILITY] = availability.name
            }
        }
    }

    /** Whether the one-time daily-totals correction has already been applied. */
    suspend fun dailyProgressBackfilled(): Boolean =
        context.dataStore.data.map { it[Keys.DAILY_PROGRESS_BACKFILLED] ?: false }.first()

    suspend fun setDailyProgressBackfilled(applied: Boolean) {
        context.dataStore.edit { it[Keys.DAILY_PROGRESS_BACKFILLED] = applied }
    }

    /** Whether old diagnostic request identifiers have been purged after the redaction upgrade. */
    suspend fun diagnosticIdentifiersNormalized(): Boolean =
        context.dataStore.data.map { it[Keys.DIAGNOSTIC_IDENTIFIERS_NORMALIZED] ?: false }.first()

    suspend fun markDiagnosticIdentifiersNormalized() {
        context.dataStore.edit { it[Keys.DIAGNOSTIC_IDENTIFIERS_NORMALIZED] = true }
    }

    private fun parseDate(value: String?): LocalDate? =
        value?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    private fun parsePendingBreak(value: String?): PendingStreakBreak? {
        if (value == null || !value.startsWith(PENDING_BREAK_PREFIX)) return null
        val parts = value.removePrefix(PENDING_BREAK_PREFIX).split('|', limit = 2)
        if (parts.size != 2) return null
        val date = parseDate(parts[0]) ?: return null
        val previousLength = parts[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
        return PendingStreakBreak(date, previousLength)
    }

    private fun writeNullableString(
        prefs: MutablePreferences,
        key: Preferences.Key<String>,
        value: String?,
    ) {
        if (value == null) prefs.remove(key) else prefs[key] = value
    }

    private companion object {
        const val PENDING_BREAK_PREFIX = "pending|"
    }
}

/** Auto-snapshot toggle, retention count, and minimum interval between writes (in hours). */
data class AutoSnapshotSettings(
    val enabled: Boolean = true,
    val retentionCount: Int = 7,
    val intervalHours: Int = 24,
)

/**
 * The newly-acquired-games announcement: the app ids the most recent acquiring poll stamped as
 * arrivals, when that poll ran, and whether the player has dismissed it.
 *
 * Expiry is [isLive] — arithmetic against a supplied instant, with no worker and no scheduled
 * alarm — so the announcement is correctly absent after any period of the app being closed rather
 * than reappearing because nothing ran to retire it.
 */
data class AcquiredGamesAnnouncement(
    val appIds: Set<Long> = emptySet(),
    val acquiredAt: Long = 0L,
    val dismissed: Boolean = false,
) {
    /** Whether this announcement should still be presented at [now]. */
    fun isLive(now: Long): Boolean =
        appIds.isNotEmpty() && !dismissed && now - acquiredAt < LIFETIME_MILLIS

    companion object {
        /** How long an announcement lasts: a purchase stops being news within a day. */
        const val LIFETIME_MILLIS: Long = 24L * 60 * 60 * 1_000
    }
}

/**
 * The persisted live now-playing session: which game (Steam appId, possibly unresolved) and when
 * it was first observed running. Both null means no session is currently tracked.
 */
data class LiveSessionState(
    val appId: Long? = null,
    val startedAt: Long? = null,
)

/** Why the opt-in live monitor is not currently available, if it is not available. */
enum class PresenceMonitoringAvailability {
    AVAILABLE,
    FOREGROUND_REQUIRED,
    RUNTIME_BUDGET_EXHAUSTED,
    START_REFUSED,
    START_FAILED,
}
