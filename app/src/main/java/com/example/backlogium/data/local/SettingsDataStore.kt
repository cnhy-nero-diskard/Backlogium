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
import com.example.backlogium.domain.SmartCollectionId
import com.example.backlogium.domain.SmartCollectionVisibility
import com.example.backlogium.domain.LibrarySortDirection
import com.example.backlogium.domain.LibrarySortPrefs
import com.example.backlogium.domain.PendingStreakBreak
import com.example.backlogium.domain.PendingTransition
import com.example.backlogium.domain.ProgressMarks
import com.example.backlogium.domain.RecomputeSource
import com.example.backlogium.domain.VersionedRuleConfig
import com.example.backlogium.data.local.entity.DailyProgress
import com.example.backlogium.data.local.entity.Session
import com.example.backlogium.data.local.entity.RecoveredSharedPlayState
import com.example.backlogium.data.local.entity.TimingInformedSteamPlayState
import com.example.backlogium.domain.librarySortDirectionOrNull
import com.example.backlogium.domain.librarySortKeyOrNull
import com.example.backlogium.data.repo.CloudPresenceRefilingBackup
import com.example.backlogium.data.repo.CloudRoutinePolicy
import com.example.backlogium.data.repo.CloudRoutineState
import com.example.backlogium.data.repo.CloudRoutineAdmission
import com.example.backlogium.data.repo.CloudReadSummary
import com.example.backlogium.data.repo.CloudReadSummaryOutcome
import com.example.backlogium.data.repo.CloudReadTrigger
import com.example.backlogium.data.repo.CloudReadFailure
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
        val SMART_COLLECTIONS_HIDDEN = stringSetPreferencesKey("smart_collections_hidden")
        val AUTO_SNAPSHOT_ENABLED = booleanPreferencesKey("auto_snapshot_enabled")
        val SNAPSHOT_RETENTION_COUNT = intPreferencesKey("snapshot_retention_count")
        val SNAPSHOT_INTERVAL_HOURS = intPreferencesKey("snapshot_interval_hours")
        val LIVE_SESSION_APP_ID = longPreferencesKey("live_session_app_id")
        val LIVE_SESSION_STARTED_AT = longPreferencesKey("live_session_started_at")
        val PENDING_SESSION_ENDS =
            stringSetPreferencesKey("pending_session_end_entries")
        val SHARED_CANDIDATE_APP_ID = longPreferencesKey("shared_candidate_app_id")
        val SHARED_CANDIDATE_FIRST_OBSERVED_AT =
            longPreferencesKey("shared_candidate_first_observed_at")
        val SHARED_GAME_NOT_A_GAME_APP_IDS =
            stringSetPreferencesKey("shared_game_not_a_game_app_ids")
        /**
         * One encoded entry per undismissed announcement (`appId|announcedAt|urlEncodedName`), so
         * an admission that arrives while an earlier one is still unseen queues rather than
         * overwriting it — the notifier contract requires every automatic admission to leave a
         * cue, not just the most recent one.
         */
        val SHARED_GAME_ANNOUNCEMENT_ENTRIES =
            stringSetPreferencesKey("shared_game_announcement_entries")
        val ACQUIRED_AT = longPreferencesKey("acquired_batch_at")
        val ACQUIRED_APP_IDS = stringSetPreferencesKey("acquired_batch_app_ids")
        val ACQUIRED_DISMISSED = booleanPreferencesKey("acquired_batch_dismissed")
        val NOTIFICATION_PERMISSION_REQUESTED =
            booleanPreferencesKey("notification_permission_requested")
        val LIVE_MONITOR_ENABLED = booleanPreferencesKey("live_monitor_enabled")
        val LIVE_MONITORING_AVAILABILITY = stringPreferencesKey("live_monitoring_availability")
        val CLOUD_READ_POSITION = stringPreferencesKey("cloud_read_position")
        val CLOUD_READER_GENERATION = longPreferencesKey("cloud_reader_generation")
        /**
         * Write-ahead marker for an endpoint replacement whose credential+generation promotion
         * has not finished. Absent means no promotion is in flight; the target generation is the
         * value the reader generation is set to when the promotion commits.
         */
        val CLOUD_READER_PROMOTION_TARGET = longPreferencesKey("cloud_reader_promotion_target")
        val CLOUD_ROUTINE_POLICY = stringPreferencesKey("cloud_routine_policy")
        val CLOUD_ROUTINE_LAST_ADMITTED_AT = longPreferencesKey("cloud_routine_last_admitted_at")
        val CLOUD_ROUTINE_LAST_OUTCOME = stringPreferencesKey("cloud_routine_last_outcome")
        val CLOUD_ROUTINE_ORDER = longPreferencesKey("cloud_routine_order")
        val CLOUD_ROUTINE_LAST_ADMISSION_ORDER = longPreferencesKey("cloud_routine_last_admission_order")
        val CLOUD_ROUTINE_OTHER_READ_ORDER = longPreferencesKey("cloud_routine_other_read_order")
        val CLOUD_ROUTINE_OTHER_READ_TERMINAL = booleanPreferencesKey("cloud_routine_other_read_terminal")
        val CLOUD_ROUTINE_CONSUMED_READ_ORDER = longPreferencesKey("cloud_routine_consumed_read_order")
        val CLOUD_SUMMARY_ATTEMPT_AT = longPreferencesKey("cloud_summary_attempt_at")
        val CLOUD_SUMMARY_TRIGGER = stringPreferencesKey("cloud_summary_trigger")
        val CLOUD_SUMMARY_OUTCOME = stringPreferencesKey("cloud_summary_outcome")
        val CLOUD_SUMMARY_FAILURE = stringPreferencesKey("cloud_summary_failure")
        val CLOUD_SUMMARY_SUCCESS_AT = longPreferencesKey("cloud_summary_success_at")
        val CLOUD_SUMMARY_OBSERVED_AT = longPreferencesKey("cloud_summary_observed_at")
        val CLOUD_SUMMARY_HAS_MORE = booleanPreferencesKey("cloud_summary_has_more")
        val CLOUD_SUMMARY_WINDOW_START = longPreferencesKey("cloud_summary_window_start")
        val CLOUD_SUMMARY_WINDOW_END = longPreferencesKey("cloud_summary_window_end")
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

        val CLOUD_INGEST_POSITION = stringPreferencesKey("cloud_ingest_position")
        val CLOUD_REFILE_APPLIED = booleanPreferencesKey("cloud_presence_refiling_applied")
        val CLOUD_REFILE_BACKUP = stringSetPreferencesKey("cloud_presence_refiling_backup")
        val CLOUD_REFILE_CREATED_IDS = stringSetPreferencesKey("cloud_presence_refiling_created_ids")
        val CLOUD_REFILE_DAILY_BACKUP = stringSetPreferencesKey("cloud_presence_refiling_daily_backup")
        val CLOUD_REFILE_DAILY_CREATED_DATES = stringSetPreferencesKey("cloud_presence_refiling_daily_created_dates")

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

    /** Hidden derived collections; absent or malformed ids default to visible. */
    val smartCollectionVisibilityFlow: Flow<SmartCollectionVisibility> = context.dataStore.data.map { prefs ->
        readHiddenSmartCollections(prefs)
    }

    suspend fun setSmartCollectionVisibility(visibility: SmartCollectionVisibility) {
        context.dataStore.edit { prefs ->
            writeHiddenSmartCollections(prefs, visibility.hidden)
        }
    }

    /**
     * Toggling one list is a read-modify-write of the shared hidden-id set, so both steps happen
     * inside a single edit: DataStore serializes concurrent edits, and a read-modify-write split
     * across calls would let the slower write silently discard a concurrent toggle.
     */
    suspend fun setSmartCollectionVisible(id: SmartCollectionId, visible: Boolean) {
        context.dataStore.edit { prefs ->
            val hidden = readHiddenSmartCollections(prefs).setVisible(id, visible).hidden
            writeHiddenSmartCollections(prefs, hidden)
        }
    }

    private fun readHiddenSmartCollections(prefs: Preferences): SmartCollectionVisibility =
        SmartCollectionVisibility(
            hidden = prefs[Keys.SMART_COLLECTIONS_HIDDEN].orEmpty()
                .mapNotNull { raw -> runCatching { SmartCollectionId.valueOf(raw) }.getOrNull() }
                .toSet(),
        )

    private fun writeHiddenSmartCollections(prefs: MutablePreferences, hidden: Set<SmartCollectionId>) {
        if (hidden.isEmpty()) {
            prefs.remove(Keys.SMART_COLLECTIONS_HIDDEN)
        } else {
            prefs[Keys.SMART_COLLECTIONS_HIDDEN] = hidden.mapTo(mutableSetOf()) { it.name }
        }
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

    /** Session ends recorded before the live session is cleared, oldest first. */
    val pendingSessionEndsFlow: Flow<List<PendingSessionEnd>> = context.dataStore.data.map { prefs ->
        prefs[Keys.PENDING_SESSION_ENDS]
            ?.mapNotNull(::decodePendingSessionEnd)
            ?.sortedWith(
                compareBy<PendingSessionEnd> { it.endedAt }
                    .thenBy { it.appId }
                    .thenBy { it.steamId },
            )
            ?: emptyList()
    }

    /** [appId] is nullable: Steam's running-game id can fail to parse while still in a game. */
    suspend fun setLiveSession(appId: Long?, startedAt: Long) {
        context.dataStore.edit { prefs ->
            writeLiveSession(prefs, LiveSessionState(appId, startedAt))
        }
    }

    suspend fun clearLiveSession() {
        context.dataStore.edit { prefs ->
            writeLiveSession(prefs, LiveSessionState())
        }

    }

    /** Durable cloud-reader watermark; account changes clear it before the new account is used. */
    val cloudReadPositionFlow: Flow<String?> =
        context.dataStore.data.map { prefs -> prefs[Keys.CLOUD_READ_POSITION] }

    suspend fun setCloudReadPosition(position: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.CLOUD_READ_POSITION] = position
        }
    }

    suspend fun clearCloudReadPosition() {
        context.dataStore.edit { prefs ->
            prefs.remove(Keys.CLOUD_READ_POSITION)
        }
    }

    val cloudReaderGenerationFlow: Flow<Long> =
        context.dataStore.data.map { prefs -> prefs[Keys.CLOUD_READER_GENERATION] ?: 0L }

    /**
     * Fence and abandon any staged replacement in one transaction. Removal and account-change
     * paths advance the reader generation and clear the promotion marker in the same edit, so a
     * process death between the two can never persist a newer generation behind a surviving
     * marker: recovery would otherwise read a marker the newer generation has already fenced
     * and either finish the fenced promotion (resurrecting the replacement) or write the older
     * marker back, decreasing the generation. When a promotion is marked, the generation is
     * advanced past the marked target as well, because the staged page is bound to that target
     * generation and landing exactly on it would let a marker-less restart inherit the
     * abandoned page as the active reader's own evidence.
     */
    suspend fun abandonCloudReaderPromotion(): Long {
        var next = 0L
        context.dataStore.edit { prefs ->
            next = maxOf(
                (prefs[Keys.CLOUD_READER_GENERATION] ?: 0L) + 1L,
                (prefs[Keys.CLOUD_READER_PROMOTION_TARGET] ?: 0L) + 1L,
            )
            prefs[Keys.CLOUD_READER_GENERATION] = next
            prefs.remove(Keys.CLOUD_READER_PROMOTION_TARGET)
        }
        return next
    }

    val cloudReaderPromotionTargetFlow: Flow<Long?> =
        context.dataStore.data.map { prefs -> prefs[Keys.CLOUD_READER_PROMOTION_TARGET] }

    suspend fun markCloudReaderPromotion(target: Long) {
        context.dataStore.edit { prefs ->
            prefs[Keys.CLOUD_READER_PROMOTION_TARGET] = target
        }
    }

    /**
     * Commit the staged replacement's generation: set the persisted reader generation to the
     * staged target and leave the marker in place. Returns the target, or null when no promotion
     * was marked, so recovery can resume the same step idempotently after process death. The
     * marker deliberately survives this edit because the post-commit cleanup (retiring the old
     * generation's evidence and clearing the old reader's watermark and summary) runs after it:
     * clearing the marker here would let a process death between the two leave the durable
     * generation promoted while the old reader's state survives with no recovery signal left.
     * Callers remove the marker with [clearCloudReaderPromotion] once every cleanup step has
     * completed; re-running this when the generation already equals the target changes nothing
     * and still returns the target.
     */
    suspend fun finishCloudReaderPromotion(): Long? {
        var target: Long? = null
        context.dataStore.edit { prefs ->
            val marked = prefs[Keys.CLOUD_READER_PROMOTION_TARGET]
            if (marked != null) {
                prefs[Keys.CLOUD_READER_GENERATION] = marked
                target = marked
            }
        }
        return target
    }

    suspend fun clearCloudReaderPromotion() {
        context.dataStore.edit { prefs ->
            prefs.remove(Keys.CLOUD_READER_PROMOTION_TARGET)
        }
    }

    val cloudRoutineStateFlow: Flow<CloudRoutineState> = context.dataStore.data.map(::cloudRoutineState)

    /** One transaction makes reconciliation safe against simultaneous startup and verification. */
    suspend fun initializeCloudRoutinePolicy(): CloudRoutineState {
        lateinit var result: CloudRoutineState
        context.dataStore.edit { prefs ->
            if (prefs[Keys.CLOUD_ROUTINE_POLICY] == null) {
                prefs[Keys.CLOUD_ROUTINE_POLICY] = CloudRoutinePolicy.AUTOMATIC.name
                // Seed the comparison order even if the existing reader has never run a routine job.
                val order = (prefs[Keys.CLOUD_ROUTINE_ORDER] ?: 0L) + 1L
                prefs[Keys.CLOUD_ROUTINE_ORDER] = order
                prefs[Keys.CLOUD_ROUTINE_LAST_ADMISSION_ORDER] = order
            }
            result = cloudRoutineState(prefs)
        }
        return result
    }

    suspend fun setCloudRoutinePolicy(policy: CloudRoutinePolicy) {
        context.dataStore.edit { prefs ->
            // Only a verified reader initializes the policy; changing it never resets cooldown.
            if (prefs[Keys.CLOUD_ROUTINE_POLICY] != null) prefs[Keys.CLOUD_ROUTINE_POLICY] = policy.name
        }
    }

    suspend fun clearCloudRoutinePolicy() {
        context.dataStore.edit { prefs ->
            prefs.remove(Keys.CLOUD_ROUTINE_POLICY)
            prefs.remove(Keys.CLOUD_ROUTINE_LAST_ADMITTED_AT)
            prefs.remove(Keys.CLOUD_ROUTINE_LAST_OUTCOME)
            prefs.remove(Keys.CLOUD_ROUTINE_ORDER)
            prefs.remove(Keys.CLOUD_ROUTINE_LAST_ADMISSION_ORDER)
            prefs.remove(Keys.CLOUD_ROUTINE_OTHER_READ_ORDER)
            prefs.remove(Keys.CLOUD_ROUTINE_OTHER_READ_TERMINAL)
            prefs.remove(Keys.CLOUD_ROUTINE_CONSUMED_READ_ORDER)
        }
    }

    suspend fun recordCloudRoutineAdmission(at: Long): CloudRoutineState {
        lateinit var result: CloudRoutineState
        context.dataStore.edit { prefs ->
            check(prefs[Keys.CLOUD_ROUTINE_POLICY] != null) { "Cloud reader has no routine policy" }
            val order = (prefs[Keys.CLOUD_ROUTINE_ORDER] ?: 0L) + 1L
            prefs[Keys.CLOUD_ROUTINE_LAST_ADMITTED_AT] = at
            prefs.remove(Keys.CLOUD_ROUTINE_LAST_OUTCOME)
            prefs[Keys.CLOUD_ROUTINE_ORDER] = order
            prefs[Keys.CLOUD_ROUTINE_LAST_ADMISSION_ORDER] = order
            result = cloudRoutineState(prefs)
        }
        return result
    }

    suspend fun recordCloudOtherRead(terminal: Boolean) {
        context.dataStore.edit { prefs ->
            if (prefs[Keys.CLOUD_ROUTINE_POLICY] == null) return@edit
            val order = (prefs[Keys.CLOUD_ROUTINE_ORDER] ?: 0L) + 1L
            prefs[Keys.CLOUD_ROUTINE_ORDER] = order
            prefs[Keys.CLOUD_ROUTINE_OTHER_READ_ORDER] = order
            prefs[Keys.CLOUD_ROUTINE_OTHER_READ_TERMINAL] = terminal
        }
    }

    suspend fun admitCloudRoutine(at: Long): CloudRoutineAdmission {
        var result = CloudRoutineAdmission.UNAVAILABLE
        context.dataStore.edit { prefs ->
            val state = cloudRoutineState(prefs)
            val policy = state.policy ?: return@edit
            val previous = state.lastAdmittedAt
            if (previous != null && at - previous < policy.minimumGapHours * 3_600_000L) {
                result = CloudRoutineAdmission.COOLDOWN
                return@edit
            }
            if (state.latestOtherReadTerminal &&
                state.latestOtherReadWatermark > state.lastAdmissionWatermark &&
                state.latestOtherReadWatermark > state.consumedOtherReadWatermark
            ) {
                prefs[Keys.CLOUD_ROUTINE_CONSUMED_READ_ORDER] = state.latestOtherReadWatermark
                result = CloudRoutineAdmission.SATISFIED_BY_READ
                return@edit
            }
            val order = state.orderingWatermark + 1L
            prefs[Keys.CLOUD_ROUTINE_ORDER] = order
            prefs[Keys.CLOUD_ROUTINE_LAST_ADMISSION_ORDER] = order
            prefs[Keys.CLOUD_ROUTINE_LAST_ADMITTED_AT] = at
            prefs.remove(Keys.CLOUD_ROUTINE_LAST_OUTCOME)
            result = CloudRoutineAdmission.ADMITTED
        }
        return result
    }

    private fun cloudRoutineState(prefs: Preferences): CloudRoutineState = CloudRoutineState(
        policy = prefs[Keys.CLOUD_ROUTINE_POLICY]?.let { CloudRoutinePolicy.valueOf(it) },
        lastAdmittedAt = prefs[Keys.CLOUD_ROUTINE_LAST_ADMITTED_AT],
        lastOutcome = prefs[Keys.CLOUD_ROUTINE_LAST_OUTCOME]?.let { CloudReadSummaryOutcome.valueOf(it) },
        orderingWatermark = prefs[Keys.CLOUD_ROUTINE_ORDER] ?: 0L,
        lastAdmissionWatermark = prefs[Keys.CLOUD_ROUTINE_LAST_ADMISSION_ORDER] ?: 0L,
        latestOtherReadWatermark = prefs[Keys.CLOUD_ROUTINE_OTHER_READ_ORDER] ?: 0L,
        latestOtherReadTerminal = prefs[Keys.CLOUD_ROUTINE_OTHER_READ_TERMINAL] ?: false,
        consumedOtherReadWatermark = prefs[Keys.CLOUD_ROUTINE_CONSUMED_READ_ORDER] ?: 0L,
    )

    suspend fun recordCloudRoutineOutcome(admittedAt: Long, outcome: CloudReadSummaryOutcome) {
        context.dataStore.edit { prefs ->
            if (prefs[Keys.CLOUD_ROUTINE_POLICY] != null &&
                prefs[Keys.CLOUD_ROUTINE_LAST_ADMITTED_AT] == admittedAt
            ) prefs[Keys.CLOUD_ROUTINE_LAST_OUTCOME] = outcome.name
        }
    }

    val cloudReadSummaryFlow: Flow<CloudReadSummary> = context.dataStore.data.map { prefs ->
        CloudReadSummary(
            lastAttemptAt = prefs[Keys.CLOUD_SUMMARY_ATTEMPT_AT],
            lastTrigger = prefs[Keys.CLOUD_SUMMARY_TRIGGER]?.let(CloudReadTrigger::valueOf),
            lastOutcome = prefs[Keys.CLOUD_SUMMARY_OUTCOME]?.let(CloudReadSummaryOutcome::valueOf),
            lastFailure = prefs[Keys.CLOUD_SUMMARY_FAILURE]?.let(CloudReadFailure::valueOf),
            lastSuccessAt = prefs[Keys.CLOUD_SUMMARY_SUCCESS_AT],
            latestObservationAt = prefs[Keys.CLOUD_SUMMARY_OBSERVED_AT],
            lastSuccessHasMore = prefs[Keys.CLOUD_SUMMARY_HAS_MORE],
            lastSuccessWindowStart = prefs[Keys.CLOUD_SUMMARY_WINDOW_START],
            lastSuccessWindowEnd = prefs[Keys.CLOUD_SUMMARY_WINDOW_END],
        )
    }

    suspend fun recordCloudReadSummary(
        at: Long, trigger: CloudReadTrigger, outcome: CloudReadSummaryOutcome,
        failure: CloudReadFailure?, observedAt: Long?, hasMore: Boolean?,
        windowStart: Long?, windowEnd: Long?,
    ) {
        context.dataStore.edit { prefs ->
            prefs[Keys.CLOUD_SUMMARY_ATTEMPT_AT] = at
            prefs[Keys.CLOUD_SUMMARY_TRIGGER] = trigger.name
            prefs[Keys.CLOUD_SUMMARY_OUTCOME] = outcome.name
            if (failure == null) prefs.remove(Keys.CLOUD_SUMMARY_FAILURE)
            else prefs[Keys.CLOUD_SUMMARY_FAILURE] = failure.name
            if (outcome != CloudReadSummaryOutcome.FAILED) {
                prefs[Keys.CLOUD_SUMMARY_SUCCESS_AT] = at
                // A successful read with no observation does not mean there was no play, and
                // must not erase the last known observation watermark.
                if (observedAt != null) prefs[Keys.CLOUD_SUMMARY_OBSERVED_AT] =
                    maxOf(observedAt, prefs[Keys.CLOUD_SUMMARY_OBSERVED_AT] ?: observedAt)
                if (hasMore != null) prefs[Keys.CLOUD_SUMMARY_HAS_MORE] = hasMore
                if (windowStart != null) prefs[Keys.CLOUD_SUMMARY_WINDOW_START] = windowStart
                if (windowEnd != null) prefs[Keys.CLOUD_SUMMARY_WINDOW_END] = windowEnd
            }
        }
    }

    suspend fun clearCloudReadSummary() {
        context.dataStore.edit { prefs ->
            prefs.remove(Keys.CLOUD_SUMMARY_ATTEMPT_AT)
            prefs.remove(Keys.CLOUD_SUMMARY_TRIGGER)
            prefs.remove(Keys.CLOUD_SUMMARY_OUTCOME)
            prefs.remove(Keys.CLOUD_SUMMARY_FAILURE)
            prefs.remove(Keys.CLOUD_SUMMARY_SUCCESS_AT)
            prefs.remove(Keys.CLOUD_SUMMARY_OBSERVED_AT)
            prefs.remove(Keys.CLOUD_SUMMARY_HAS_MORE)
            prefs.remove(Keys.CLOUD_SUMMARY_WINDOW_START)
            prefs.remove(Keys.CLOUD_SUMMARY_WINDOW_END)
        }
    }

    /** Durable cloud-session ingest watermark; account changes clear it with the read watermark. */
    val cloudIngestPositionFlow: Flow<String?> =
        context.dataStore.data.map { prefs -> prefs[Keys.CLOUD_INGEST_POSITION] }

    suspend fun setCloudIngestPosition(position: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.CLOUD_INGEST_POSITION] = position
        }
    }

    suspend fun clearCloudIngestPosition() {
        context.dataStore.edit { prefs ->
            prefs.remove(Keys.CLOUD_INGEST_POSITION)
        }
    }

    /** Whether the one-time cloud-presence historical refile has completed. */
    val cloudPresenceRefilingAppliedFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.CLOUD_REFILE_APPLIED] ?: false
    }

    suspend fun setCloudPresenceRefilingApplied(applied: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.CLOUD_REFILE_APPLIED] = applied
        }
    }

    suspend fun cloudPresenceRefilingBackup(): CloudPresenceRefilingBackup? {
        val prefs = context.dataStore.data.first()
        val encoded = prefs[Keys.CLOUD_REFILE_BACKUP].orEmpty()
        val createdIds = prefs[Keys.CLOUD_REFILE_CREATED_IDS].orEmpty()
            .mapNotNull(String::toLongOrNull)
            .toSet()
        val dailyEncoded = prefs[Keys.CLOUD_REFILE_DAILY_BACKUP].orEmpty()
        val createdDates = prefs[Keys.CLOUD_REFILE_DAILY_CREATED_DATES].orEmpty()
        if (encoded.isEmpty() && createdIds.isEmpty() && dailyEncoded.isEmpty() && createdDates.isEmpty()) {
            return null
        }
        return CloudPresenceRefilingBackup(
            sessions = encoded.mapNotNull(::decodeCloudPresenceRefilingSession),
            createdSessionIds = createdIds,
            dailyProgress = dailyEncoded.mapNotNull(::decodeCloudPresenceRefilingDailyProgress),
            createdDailyProgressDates = createdDates,
        )
    }

    suspend fun setCloudPresenceRefilingBackup(backup: CloudPresenceRefilingBackup) {
        context.dataStore.edit { prefs ->
            if (backup.sessions.isEmpty()) {
                prefs.remove(Keys.CLOUD_REFILE_BACKUP)
            } else {
                prefs[Keys.CLOUD_REFILE_BACKUP] =
                    backup.sessions.mapTo(mutableSetOf(), ::encodeCloudPresenceRefilingSession)
            }
            if (backup.createdSessionIds.isEmpty()) {
                prefs.remove(Keys.CLOUD_REFILE_CREATED_IDS)
            } else {
                prefs[Keys.CLOUD_REFILE_CREATED_IDS] =
                    backup.createdSessionIds.mapTo(mutableSetOf(), Long::toString)
            }
            if (backup.dailyProgress.isEmpty()) {
                prefs.remove(Keys.CLOUD_REFILE_DAILY_BACKUP)
            } else {
                prefs[Keys.CLOUD_REFILE_DAILY_BACKUP] =
                    backup.dailyProgress.mapTo(mutableSetOf(), ::encodeCloudPresenceRefilingDailyProgress)
            }
            if (backup.createdDailyProgressDates.isEmpty()) {
                prefs.remove(Keys.CLOUD_REFILE_DAILY_CREATED_DATES)
            } else {
                prefs[Keys.CLOUD_REFILE_DAILY_CREATED_DATES] = backup.createdDailyProgressDates
            }
        }
    }

    suspend fun clearCloudPresenceRefiling() {
        context.dataStore.edit { prefs ->
            prefs.remove(Keys.CLOUD_REFILE_APPLIED)
            prefs.remove(Keys.CLOUD_REFILE_BACKUP)
            prefs.remove(Keys.CLOUD_REFILE_CREATED_IDS)
            prefs.remove(Keys.CLOUD_REFILE_DAILY_BACKUP)
            prefs.remove(Keys.CLOUD_REFILE_DAILY_CREATED_DATES)
        }
    }

    /**
     * Record the session end and advance the live-session state in the same DataStore edit. A
     * process death cannot leave the caller with a cleared session and no handoff to WorkManager.
     */
    suspend fun recordSessionEnd(
        sessionEnd: PendingSessionEnd,
        nextLiveSession: LiveSessionState,
    ) {
        context.dataStore.edit { prefs ->
            val entries = (prefs[Keys.PENDING_SESSION_ENDS] ?: emptySet()).toMutableSet()
            entries += encodePendingSessionEnd(sessionEnd)
            prefs[Keys.PENDING_SESSION_ENDS] = entries
            writeLiveSession(prefs, nextLiveSession)
        }
    }

    /** Remove one successfully handed-off session end, preserving any other pending entries. */
    suspend fun acknowledgeSessionEnd(sessionEnd: PendingSessionEnd) {
        context.dataStore.edit { prefs ->
            val entries = prefs[Keys.PENDING_SESSION_ENDS] ?: return@edit
            val remaining = entries - encodePendingSessionEnd(sessionEnd)
            if (remaining.isEmpty()) {
                prefs.remove(Keys.PENDING_SESSION_ENDS)
            } else {
                prefs[Keys.PENDING_SESSION_ENDS] = remaining
            }
        }
    }

    private fun writeLiveSession(
        prefs: MutablePreferences,
        session: LiveSessionState,
    ) {
        if (session.startedAt == null) {
            prefs.remove(Keys.LIVE_SESSION_APP_ID)
            prefs.remove(Keys.LIVE_SESSION_STARTED_AT)
            return
        }
        if (session.appId != null) {
            prefs[Keys.LIVE_SESSION_APP_ID] = session.appId
        } else {
            prefs.remove(Keys.LIVE_SESSION_APP_ID)
        }
        prefs[Keys.LIVE_SESSION_STARTED_AT] = session.startedAt
    }

    /**
     * The unrecognised app id currently under consideration for admission as family-shared, and
     * when it was first observed (add-family-shared-games).
     *
     * Persisted rather than held in memory because the admission rule spans a sync: an app id is
     * only considered once a *successful sync has completed since it was first observed*, which is
     * what tells a genuinely borrowed game apart from an owned one the app has simply not synced
     * yet. Holding the first-observation time in memory would reset that clock on every process
     * death and could leave a borrowed game never admitted.
     *
     * One candidate, not a set: Steam reports one running game at a time, and a candidate the
     * player has moved on from is worth nothing — it is reconsidered from scratch the next time it
     * is observed.
     */
    val sharedGameCandidateFlow: Flow<SharedGameCandidate?> = context.dataStore.data.map { prefs ->
        val appId = prefs[Keys.SHARED_CANDIDATE_APP_ID]
        val firstObservedAt = prefs[Keys.SHARED_CANDIDATE_FIRST_OBSERVED_AT]
        if (appId != null && firstObservedAt != null) {
            SharedGameCandidate(appId, firstObservedAt)
        } else {
            null
        }
    }

    suspend fun setSharedGameCandidate(appId: Long, firstObservedAt: Long) {
        context.dataStore.edit { prefs ->
            prefs[Keys.SHARED_CANDIDATE_APP_ID] = appId
            prefs[Keys.SHARED_CANDIDATE_FIRST_OBSERVED_AT] = firstObservedAt
        }
    }

    suspend fun clearSharedGameCandidate() {
        context.dataStore.edit { prefs ->
            prefs.remove(Keys.SHARED_CANDIDATE_APP_ID)
            prefs.remove(Keys.SHARED_CANDIDATE_FIRST_OBSERVED_AT)
        }
    }

    /** Store-confirmed non-games are permanent and distinct from player-controlled exclusions. */
    suspend fun isSharedGameNotAGame(appId: Long): Boolean =
        appId.toString() in context.dataStore.data.first()[Keys.SHARED_GAME_NOT_A_GAME_APP_IDS].orEmpty()

    suspend fun markSharedGameNotAGame(appId: Long) {
        context.dataStore.edit { prefs ->
            prefs[Keys.SHARED_GAME_NOT_A_GAME_APP_IDS] =
                prefs[Keys.SHARED_GAME_NOT_A_GAME_APP_IDS].orEmpty() + appId.toString()
        }
    }

    /**
     * Every undismissed durable cue, oldest first — the queue [sharedGameAnnouncementFlow] and
     * [clearSharedGameAnnouncement] present one entry at a time.
     */
    val sharedGameAnnouncementsFlow: Flow<List<SharedGameAnnouncement>> = context.dataStore.data.map { prefs ->
        prefs[Keys.SHARED_GAME_ANNOUNCEMENT_ENTRIES].orEmpty()
            .mapNotNull(::decodeSharedGameAnnouncement)
            .sortedBy { it.announcedAt }
    }

    /** Durable in-app cue used when automatic admission cannot post a notification. */
    val sharedGameAnnouncementFlow: Flow<SharedGameAnnouncement?> =
        sharedGameAnnouncementsFlow.map { it.firstOrNull() }

    /** Queue a cue for [appId], replacing any earlier undismissed entry for the same game. */
    suspend fun setSharedGameAnnouncement(appId: Long, name: String, announcedAt: Long) {
        context.dataStore.edit { prefs ->
            val remaining = prefs[Keys.SHARED_GAME_ANNOUNCEMENT_ENTRIES].orEmpty()
                .mapNotNull(::decodeSharedGameAnnouncement)
                .filterNot { it.appId == appId }
            prefs[Keys.SHARED_GAME_ANNOUNCEMENT_ENTRIES] =
                (remaining + SharedGameAnnouncement(appId, name, announcedAt))
                    .mapTo(mutableSetOf(), ::encodeSharedGameAnnouncement)
        }
    }

    /** Dismiss only [appId]'s cue; any other queued admission's cue is untouched. */
    suspend fun clearSharedGameAnnouncement(appId: Long) {
        context.dataStore.edit { prefs ->
            val remaining = prefs[Keys.SHARED_GAME_ANNOUNCEMENT_ENTRIES].orEmpty()
                .mapNotNull(::decodeSharedGameAnnouncement)
                .filterNot { it.appId == appId }
            if (remaining.isEmpty()) {
                prefs.remove(Keys.SHARED_GAME_ANNOUNCEMENT_ENTRIES)
            } else {
                prefs[Keys.SHARED_GAME_ANNOUNCEMENT_ENTRIES] =
                    remaining.mapTo(mutableSetOf(), ::encodeSharedGameAnnouncement)
            }
        }
    }

    /**
     * The most recent acquiring poll's announcement batch. Absent by default, so a fresh install
     * and an install that has never acquired anything both read as "nothing to announce".
     *
     * Deliberately not exported in a backup: the banner belongs to a poll that observed previously
     * unknown games on this device, so a restore must not re-announce another device's purchase.
     */
    val acquiredGamesFlow: Flow<AcquiredGamesAnnouncement> = context.dataStore.data.map { prefs ->
        AcquiredGamesAnnouncement(
            appIds = prefs[Keys.ACQUIRED_APP_IDS].orEmpty().mapNotNull(String::toLongOrNull).toSet(),
            acquiredAt = prefs[Keys.ACQUIRED_AT] ?: 0L,
            dismissed = prefs[Keys.ACQUIRED_DISMISSED] ?: false,
        )
    }

    /** Replace the announcement with a later poll's arrivals, clearing dismissal. */
    suspend fun setAcquiredGames(appIds: Set<Long>, acquiredAt: Long) {
        context.dataStore.edit { prefs ->
            prefs[Keys.ACQUIRED_APP_IDS] = appIds.mapTo(mutableSetOf(), Long::toString)
            prefs[Keys.ACQUIRED_AT] = acquiredAt
            prefs[Keys.ACQUIRED_DISMISSED] = false
        }
    }

    /** Dismiss the current batch. A later acquisition clears it again. */
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
            prefs.remove(Keys.CLOUD_READ_POSITION)
            prefs.remove(Keys.CLOUD_INGEST_POSITION)
            prefs.remove(Keys.CLOUD_READER_PROMOTION_TARGET)
            prefs.remove(Keys.CLOUD_ROUTINE_POLICY)
            prefs.remove(Keys.CLOUD_ROUTINE_LAST_ADMITTED_AT)
            prefs.remove(Keys.CLOUD_ROUTINE_LAST_OUTCOME)
            prefs.remove(Keys.CLOUD_ROUTINE_ORDER)
            prefs.remove(Keys.CLOUD_ROUTINE_LAST_ADMISSION_ORDER)
            prefs.remove(Keys.CLOUD_ROUTINE_OTHER_READ_ORDER)
            prefs.remove(Keys.CLOUD_ROUTINE_OTHER_READ_TERMINAL)
            prefs.remove(Keys.CLOUD_ROUTINE_CONSUMED_READ_ORDER)
            prefs.remove(Keys.CLOUD_SUMMARY_ATTEMPT_AT)
            prefs.remove(Keys.CLOUD_SUMMARY_TRIGGER)
            prefs.remove(Keys.CLOUD_SUMMARY_OUTCOME)
            prefs.remove(Keys.CLOUD_SUMMARY_FAILURE)
            prefs.remove(Keys.CLOUD_SUMMARY_SUCCESS_AT)
            prefs.remove(Keys.CLOUD_SUMMARY_OBSERVED_AT)
            prefs.remove(Keys.CLOUD_SUMMARY_HAS_MORE)
            prefs.remove(Keys.CLOUD_SUMMARY_WINDOW_START)
            prefs.remove(Keys.CLOUD_SUMMARY_WINDOW_END)
            prefs.remove(Keys.CLOUD_REFILE_APPLIED)
            prefs.remove(Keys.CLOUD_REFILE_BACKUP)
            prefs.remove(Keys.CLOUD_REFILE_CREATED_IDS)
            prefs.remove(Keys.CLOUD_REFILE_DAILY_BACKUP)
            prefs.remove(Keys.CLOUD_REFILE_DAILY_CREATED_DATES)
            prefs.remove(Keys.PENDING_SESSION_ENDS)
            prefs.remove(Keys.SHARED_CANDIDATE_APP_ID)
            prefs.remove(Keys.SHARED_CANDIDATE_FIRST_OBSERVED_AT)
            prefs.remove(Keys.SHARED_GAME_NOT_A_GAME_APP_IDS)
            prefs.remove(Keys.SHARED_GAME_ANNOUNCEMENT_ENTRIES)
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

/** The newly-acquired-games announcement stored for the most recent successful poll. */
data class AcquiredGamesAnnouncement(
    val appIds: Set<Long> = emptySet(),
    val acquiredAt: Long = 0L,
    val dismissed: Boolean = false,
) {
    fun isLive(now: Long): Boolean =
        appIds.isNotEmpty() && !dismissed && now - acquiredAt < LIFETIME_MILLIS

    companion object {
        const val LIFETIME_MILLIS: Long = 24L * 60 * 60 * 1_000
    }
}
/** A durable foreground cue for a family-shared admission when notifications were unavailable. */
data class SharedGameAnnouncement(
    val appId: Long,
    val name: String,
    val announcedAt: Long,
)

/** `appId|announcedAt|urlEncodedName` — a single [SharedGameAnnouncement] as a set element. */
private fun encodeSharedGameAnnouncement(announcement: SharedGameAnnouncement): String {
    val encodedName = java.net.URLEncoder.encode(announcement.name, "UTF-8")
    return "${announcement.appId}|${announcement.announcedAt}|$encodedName"
}

private fun decodeSharedGameAnnouncement(raw: String): SharedGameAnnouncement? {
    val parts = raw.split("|", limit = 3)
    if (parts.size != 3) return null
    val appId = parts[0].toLongOrNull() ?: return null
    val announcedAt = parts[1].toLongOrNull() ?: return null
    val name = runCatching { java.net.URLDecoder.decode(parts[2], "UTF-8") }.getOrNull() ?: return null
    return SharedGameAnnouncement(appId, name, announcedAt)
}


/**
 * The persisted live now-playing session: which game (Steam appId, possibly unresolved) and when
 * it was first observed running. Both null means no session is currently tracked.
 */
/** An unrecognised app id awaiting the sync that will confirm it is genuinely not owned. */
data class SharedGameCandidate(
    val appId: Long,
    val firstObservedAt: Long,
)

data class LiveSessionState(
    val appId: Long? = null,
    val startedAt: Long? = null,
)

/** One session end held durably until its post-play schedule has been enqueued. */
data class PendingSessionEnd(
    val appId: Long,
    val endedAt: Long,
    val steamId: String,
)

/** Legacy six-column backups decode as unknown; new backups preserve both facts verbatim. */
private fun encodeCloudPresenceRefilingSession(session: Session): String = listOf(
    session.id.toString(),
    session.appId.toString(),
    session.startAt.toString(),
    session.endAt?.toString() ?: "-",
    session.minutes.toString(),
    session.open.toString(),
    session.recoveredSharedPlay?.name ?: "-",
    session.timingInformedSteamPlay?.name ?: "-",
).joinToString("|")

private fun decodeCloudPresenceRefilingSession(raw: String): Session? {
    val parts = raw.split('|', limit = 8)
    if (parts.size != 6 && parts.size != 8) return null
    val id = parts[0].toLongOrNull() ?: return null
    val appId = parts[1].toLongOrNull() ?: return null
    val startAt = parts[2].toLongOrNull() ?: return null
    val endAt = if (parts[3] == "-") null else parts[3].toLongOrNull() ?: return null
    val minutes = parts[4].toIntOrNull() ?: return null
    val open = when (parts[5]) {
        "true" -> true
        "false" -> false
        else -> return null
    }
    val recovered = if (parts.size == 6 || parts[6] == "-") null else {
        enumValueOrNull<RecoveredSharedPlayState>(parts[6]) ?: return null
    }
    val timing = if (parts.size == 6 || parts[7] == "-") null else {
        enumValueOrNull<TimingInformedSteamPlayState>(parts[7]) ?: return null
    }
    return Session(id, appId, startAt, endAt, minutes, open, recovered, timing)
}

private inline fun <reified T : Enum<T>> enumValueOrNull(value: String): T? =
    enumValues<T>().firstOrNull { it.name == value }

/** `date|minutesPlayed|goalMinutesPlayed|questMet` for exact daily-progress reversal. */
private fun encodeCloudPresenceRefilingDailyProgress(day: DailyProgress): String =
    listOf(day.date, day.minutesPlayed, day.goalMinutesPlayed, day.questMet).joinToString("|")

private fun decodeCloudPresenceRefilingDailyProgress(raw: String): DailyProgress? {
    val parts = raw.split('|', limit = 4)
    if (parts.size != 4) return null
    val minutesPlayed = parts[1].toIntOrNull() ?: return null
    val goalMinutesPlayed = parts[2].toIntOrNull() ?: return null
    val questMet = when (parts[3]) {
        "true" -> true
        "false" -> false
        else -> return null
    }
    return DailyProgress(parts[0], minutesPlayed, goalMinutesPlayed, questMet)
}

/** appId|endedAt|urlEncodedSteamId - one durable session-end handoff. */
private fun encodePendingSessionEnd(sessionEnd: PendingSessionEnd): String {
    val encodedSteamId = java.net.URLEncoder.encode(sessionEnd.steamId, "UTF-8")
    return "${sessionEnd.appId}|${sessionEnd.endedAt}|$encodedSteamId"
}

private fun decodePendingSessionEnd(raw: String): PendingSessionEnd? {
    val parts = raw.split("|", limit = 3)
    if (parts.size != 3) return null
    val appId = parts[0].toLongOrNull() ?: return null
    val endedAt = parts[1].toLongOrNull() ?: return null
    val steamId = runCatching { java.net.URLDecoder.decode(parts[2], "UTF-8") }.getOrNull()
        ?: return null
    return PendingSessionEnd(appId, endedAt, steamId)
}

/** Why the opt-in live monitor is not currently available, if it is not available. */
enum class PresenceMonitoringAvailability {
    AVAILABLE,
    FOREGROUND_REQUIRED,
    RUNTIME_BUDGET_EXHAUSTED,
    START_REFUSED,
    START_FAILED,
}
