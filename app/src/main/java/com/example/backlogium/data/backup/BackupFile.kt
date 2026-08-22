package com.example.backlogium.data.backup

import kotlinx.serialization.Serializable

/**
 * The app's full export/import format (add-backup-restore): a single versioned JSON file
 * covering every piece of app-derived state that Steam's API cannot re-supply on a reinstall —
 * session history, streaks, XP/level, frozen achievement-rarity snapshots, HLTB matches, goal
 * tags, and the active gamification rule configuration — plus a minimal game/achievement
 * identity skeleton so the file is self-contained and legible without a live Steam sync.
 *
 * Timestamps are ISO-8601 strings, never epoch values, so the file reads without tooling.
 * [ruleConfig], [librarySortPrefs], and [computed] are captured for legibility/reproducibility
 * only — they are never written back into the app on import (see [computed]'s doc and
 * design.md's decision table, which omits them from the merge engine entirely).
 */
@Serializable
data class BackupFile(
    val formatVersion: Int = CURRENT_FORMAT_VERSION,
    val exportedAt: String,
    val identity: BackupIdentity,
    val ruleConfig: BackupRuleConfig,
    val games: List<BackupGame>,
    val achievements: List<BackupAchievement>,
    val sessions: List<BackupSession>,
    val dailyProgress: List<BackupDailyProgress>,
    val hltbData: List<BackupHltbData>,
    val librarySortPrefs: BackupLibrarySortPrefs,
    val playerProfile: BackupPlayerProfile,
    val computed: BackupComputed,
    /** Custom collections and their memberships (add-custom-collections) — app-owned state. */
    val collections: List<BackupCollection> = emptyList(),
    val collectionMembers: List<BackupCollectionMember> = emptyList(),
    /**
     * The hidden set (add-hidden-games) — app-owned state Steam cannot re-supply. Without it a
     * restore would silently unhide everything and re-apply XP the player deliberately removed.
     * Absent in files written before hiding existed, which correctly restores as nothing hidden.
     */
    val hiddenGames: List<BackupHiddenGame> = emptyList(),
) {
    companion object {
        const val CURRENT_FORMAT_VERSION = 1
    }
}

/** The public SteamID64 only — the Steam Web API key is never included in an export. */
@Serializable
data class BackupIdentity(val steamId64: String)

/** Mirrors [com.example.backlogium.gamification.RuleConfig], captured at export time. */
@Serializable
data class BackupRuleConfig(
    val xpPerMinute: Int,
    val levelBase: Int,
    val questThresholdMin: Int,
    val questMode: String,
    val streakGraceDays: Int,
    val commonAchievementXp: Int,
    val uncommonAchievementXp: Int,
    val rareAchievementXp: Int,
    val epicAchievementXp: Int,
    val legendaryAchievementXp: Int,
)

/** Identity skeleton (appId+name) plus the two app-derived fields a restore must recover. */
@Serializable
data class BackupGame(
    val appId: Long,
    val name: String,
    val isGoal: Boolean,
    val backfillMinutes: Int,
)

/** One unlocked achievement's frozen rarity snapshot, with its identity for legibility. */
@Serializable
data class BackupAchievement(
    val appId: Long,
    val apiName: String,
    val displayName: String?,
    val snapshotPercent: Double?,
    val unlockedAt: String?,
)

/** A synthesized play session. `open` is deliberately absent — a merged session is historical. */
@Serializable
data class BackupSession(
    val appId: Long,
    val startAt: String,
    val endAt: String?,
    val minutes: Int,
)

@Serializable
data class BackupDailyProgress(
    val date: String,
    val minutesPlayed: Int,
    val goalMinutesPlayed: Int,
    val questMet: Boolean,
)

@Serializable
data class BackupHltbData(
    val appId: Long,
    val hltbId: Long?,
    val mainStoryMinutes: Int?,
    val mainExtraMinutes: Int?,
    val completionistMinutes: Int?,
    val allStylesMinutes: Int?,
    val matchStatus: String,
)

/**
 * The two Library sort selections as exported.
 *
 * **Export-only, like [BackupFile.ruleConfig] and [BackupFile.computed]** — see [BackupFile]'s doc.
 * Nothing reads this block back into the app, so the directions recorded here document what the
 * library looked like when the file was written; they do not restore it.
 *
 * The direction fields are nullable purely so that an export written before directions existed
 * still deserializes. A null means "this file predates directions", which is a statement about the
 * file and not an instruction to the app.
 */
@Serializable
data class BackupLibrarySortPrefs(
    val focus: String,
    val library: String,
    val focusDirection: String? = null,
    val libraryDirection: String? = null,
)

/**
 * The player aggregates. `totalXp`/`level`/`currentStreak` are exported for legibility only —
 * the merge engine always recomputes them from raw data and never trusts these values (see
 * spec.md: "Import merge does not double-count or blindly overwrite"). `longestStreak` is the
 * one aggregate an import can raise (never lower).
 */
@Serializable
data class BackupPlayerProfile(
    val totalXp: Int,
    val level: Int,
    val currentStreak: Int,
    val longestStreak: Int,
    val playtimeBackfilled: Boolean,
)

/**
 * Export-time-only computed rollup: XP-per-game and cumulative XP-over-time, evaluated fresh
 * from the raw layer under [BackupFile.ruleConfig]. Never re-imported as authoritative input —
 * XP-per-game is `f(raw ingredients, RuleConfig)`, not a stored fact, so trusting it verbatim
 * would let a backup taken under an old rule configuration inject stale numbers after the user
 * has since changed their rules. Regenerated again after every import/merge so the app's own
 * displayed values always match current raw data + current rules.
 */
@Serializable
data class BackupComputed(
    val xpPerGame: List<BackupGameXp>,
    val xpTimeline: List<BackupDayXp>,
)

@Serializable
data class BackupGameXp(val appId: Long, val name: String, val xp: Int)

@Serializable
data class BackupDayXp(val date: String, val cumulativeXp: Int)

/**
 * One custom collection (add-custom-collections), carried in the backup so a restore keeps the
 * player's groups. [mode]/[sort] are stored as their enum names (matching the Room converters);
 * [targetDate] is the ISO deadline, null outside deadline mode.
 */
@Serializable
data class BackupCollection(
    val id: Long,
    val name: String,
    val mode: String,
    val sort: String,
    val targetDate: String?,
    val createdAt: Long,
    val accent: String? = null,
    val timeBasis: String = "COMPLETIONIST",
    val description: String? = null,
    val displayOrder: Int? = null,
)

/**
 * One hidden game. [hiddenAt] is ISO-8601 like every other timestamp in this file, and
 * [fromBulkAction] records that the hide came from the non-game review — a note the hidden list
 * shows, never something that changes how the hide behaves.
 */
@Serializable
data class BackupHiddenGame(
    val appId: Long,
    val hiddenAt: String,
    val fromBulkAction: Boolean = false,
)

/** One collection membership, keyed by collection id + app id with its sequence order. */
@Serializable
data class BackupCollectionMember(
    val collectionId: Long,
    val appId: Long,
    val orderIndex: Int,
    val done: Boolean = false,
)
