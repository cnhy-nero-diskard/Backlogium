package com.example.backlogium.data.backup

import com.example.backlogium.data.local.dao.AchievementDao
import com.example.backlogium.data.local.dao.CollectionDao
import com.example.backlogium.data.local.dao.ExcludedSharedGameDao
import com.example.backlogium.data.local.dao.DailyProgressDao
import com.example.backlogium.data.local.dao.GameDao
import com.example.backlogium.data.local.dao.HltbDataDao
import com.example.backlogium.data.local.dao.PlayerProfileDao
import com.example.backlogium.data.local.dao.SessionDao
import com.example.backlogium.data.local.entity.Achievement
import com.example.backlogium.data.local.entity.Collection
import com.example.backlogium.data.local.entity.ExcludedSharedGame
import com.example.backlogium.data.local.entity.CollectionMember
import com.example.backlogium.data.local.entity.DailyProgress
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.HltbData
import com.example.backlogium.data.local.entity.HltbDataOrigin
import com.example.backlogium.data.local.entity.HltbMatchStatus
import com.example.backlogium.data.local.entity.Session
import com.example.backlogium.domain.CollectionAccent
import com.example.backlogium.domain.CollectionMode
import com.example.backlogium.domain.CollectionSort
import com.example.backlogium.domain.CollectionTimeBasis
import com.example.backlogium.domain.DerivedStateWriteCoordinator
import com.example.backlogium.domain.GamificationUpdater
import com.example.backlogium.domain.GameSource
import com.example.backlogium.domain.RecomputeSource
import com.example.backlogium.domain.SetSharedGamePlaytimeUseCase
import com.example.backlogium.domain.TimeProvider
import com.example.backlogium.domain.defaultSort
import com.example.backlogium.gamification.RuleConfig
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Merges an imported/restored [BackupFile] into the local database (design.md decision 2):
 * natural-key upsert per data type, never a blind table replace, never an additive sum.
 * Aggregates (`totalXp`, `level`, `currentStreak`) are always recomputed from the merged raw
 * data afterward via [GamificationUpdater] — this engine never writes them directly from the
 * file. `longestStreak` is protected as the max of the stored, imported, and recomputed values.
 *
 * Used by both a manually imported file and a restored automatic snapshot — there is only one
 * merge code path (tasks.md 2.3, design.md decision 4).
 *
 * The raw-data writes below run inside one [transaction] (auditfix-backup-integrity design.md
 * decision 2): every table commits together, or none does. The gamification recompute runs
 * strictly after that transaction commits, never inside it — [GamificationUpdater.persist]
 * suspends on `DataStore` and owns a non-reentrant coordinator, and nesting either inside a Room
 * transaction risks deadlock. [PlayerProfileDao.markPendingImportRecompute] is written as the
 * transaction's last step so a crash between the merge commit and the recompute is detected on
 * the next launch rather than left as a silent stale-aggregate state.
 */
@Singleton
class BackupMergeEngine @Inject constructor(
    private val gameDao: GameDao,
    private val sessionDao: SessionDao,
    private val dailyProgressDao: DailyProgressDao,
    private val hltbDataDao: HltbDataDao,
    private val achievementDao: AchievementDao,
    private val playerProfileDao: PlayerProfileDao,
    private val collectionDao: CollectionDao,
    private val excludedSharedGameDao: ExcludedSharedGameDao,
    private val gamificationUpdater: GamificationUpdater,
    private val time: TimeProvider,
    private val derivedStateWrites: DerivedStateWriteCoordinator = DerivedStateWriteCoordinator(),
    private val transaction: DatabaseTransactionScope = PassThroughTransactionScope,
) {
    /**
     * [config] is the app's currently active [RuleConfig] — never the file's own `ruleConfig`,
     * which is export-time-only (see [BackupFile.ruleConfig]'s doc). Passed in rather than read
     * internally so this engine stays a plain, JVM-testable class, mirroring [GamificationUpdater].
     */
    suspend fun merge(file: BackupFile, config: RuleConfig, configVersion: Long = 0L) =
        derivedStateWrites.withLock {
            mergeContents(file, config, configVersion)
        }

    /** Called by [BackupRepository] while it owns the shared rule/write coordinator. */
    internal suspend fun mergeWithLockHeld(
        file: BackupFile,
        config: RuleConfig,
        configVersion: Long,
    ) {
        mergeContents(file, config, configVersion)
    }

    private suspend fun mergeContents(
        file: BackupFile,
        config: RuleConfig,
        configVersion: Long,
    ) {
        val importedLongestStreak = file.playerProfile.longestStreak
        val importedBackfilled = file.playerProfile.playtimeBackfilled

        // Every raw-data write commits as one unit (design.md decision 2). No suspension besides
        // these DAO calls happens in here — no settings, no file access, nothing that hops
        // threads — so the transaction cannot deadlock or be left holding a connection open.
        transaction.run {
            file.excludedSharedGames.forEach { mergeExcludedSharedGame(it) }
            // Games first: Session/Achievement/HltbData all carry a FOREIGN KEY on games.appId, so
            // a fresh-install restore (no games synced yet) needs the skeleton row to exist first.
            file.games.forEach { mergeGame(it) }
            file.sessions.forEach { mergeSession(it) }
            file.dailyProgress.forEach { mergeDailyProgress(it) }
            file.hltbData.forEach { mergeHltbData(it) }
            file.achievements.forEach { mergeAchievement(it) }
            file.collections.forEach { mergeCollection(it) }
            file.collectionMembers.forEach { mergeCollectionMember(it) }

            // playtimeBackfilled is a historical fact ("has this account ever backfilled"), not a
            // derivation — folded in like longestStreak, as a one-way OR rather than a replace, so
            // an import can never un-flag an import that already happened locally.
            val storedProfile = playerProfileDao.get()
            if (storedProfile == null) playerProfileDao.insertIfMissing()
            if (importedBackfilled && storedProfile?.playtimeBackfilled != true) {
                playerProfileDao.updatePlaytimeBackfilled(true)
            }

            // Also a historical fact, and durable here rather than only in the recompute below:
            // the recompute cannot reconstruct a record the current rules no longer produce, so
            // a crash before it runs would otherwise lose the imported high-water mark for good.
            playerProfileDao.raiseLongestStreak(importedLongestStreak)

            // Last write in the transaction: commits atomically with the merged data, so a crash
            // before the recompute below is detectable on the next launch
            // (PendingImportRecomputeUseCase) instead of leaving aggregates silently stale.
            playerProfileDao.markPendingImportRecompute()
        }

        // Outside the transaction by construction: persist() suspends on DataStore and owns a
        // coordinator that a Room transaction must never wrap (design.md decision 2).
        val result = gamificationUpdater.compute(time.today(), config)
        gamificationUpdater.persist(
            result.copy(longestStreak = maxOf(result.longestStreak, importedLongestStreak)),
            RecomputeSource.RESTORE,
            configVersion,
        )
    }

    /**
     * Restore one game's app-owned state.
     *
     * **The insert path must not stamp an arrival**, and this is the sharpest edge in the recency
     * work: the natural implementation of "insert a game that isn't there" is exactly the thing
     * that must not happen here, because a restore of 300 games is not 300 acquisitions. What the
     * row carries is whatever the *backup* recorded — a value, or an explicit absence — and nothing
     * this method has access to could stand in for one.
     *
     * It likewise runs no dormancy evaluation. Restoring play history is not observing play, so
     * there is no observation to evaluate and no return to record. Nor can it produce an
     * acquisition announcement: that state is a poll's, and this engine has no dependency through
     * which to reach it — structural rather than remembered.
     */
    private suspend fun mergeGame(backupGame: BackupGame) {
        val existing = gameDao.getById(backupGame.appId)
        if (existing == null) {
            // Minimal skeleton row: the rest of the game's Steam-fetchable fields (icon,
            // playtime, ...) are re-populated by the next sync, not by this restore.
            gameDao.upsert(
                Game(
                    appId = backupGame.appId,
                    name = backupGame.name,
                    iconUrl = "",
                    playtimeForever = 0,
                    playtime2Weeks = 0,
                    lastPlaytime = 0,
                    isGoal = backupGame.isGoal,
                    backfillMinutes = backupGame.backfillMinutes,
                    source = backupGame.source.toGameSource(GameSource.STEAM_OWNED),
                    firstSeenAt = backupGame.firstSeenAt?.iso8601ToEpochMilli(),
                    lastPlayedAt = backupGame.lastPlayedAt?.iso8601ToEpochMilli(),
                    returnedToPlayAt = backupGame.returnedToPlayAt?.iso8601ToEpochMilli(),
                    // Defense in depth behind BackupValidator's preflight range check: an
                    // out-of-range value that reaches here is treated as no opinion (0 for a
                    // brand-new row), never persisted, mirroring the use case's reject.
                    manualSharedMinutes = backupGame.manualSharedMinutes
                        ?.takeIf { it in 0..SetSharedGamePlaytimeUseCase.MAX_MANUAL_SHARED_MINUTES }
                        ?: 0,
                ),
            )
        } else {
            backupGame.source?.let { source ->
                gameDao.upsert(existing.copy(source = source.toGameSource(existing.source)))
            }
            gameDao.setGoalFlag(backupGame.appId, backupGame.isGoal)
            gameDao.setBackfillMinutes(backupGame.appId, backupGame.backfillMinutes)
            // Null means an older backup predates this field, same as source/recency above -- the
            // locally set estimate is left alone rather than zeroed by a backup that has no
            // opinion on it. An out-of-range value is likewise left alone: the preflight
            // validator rejects it, and this keeps the write path from persisting an estimate
            // the use case itself would refuse.
            backupGame.manualSharedMinutes
                ?.takeIf { it in 0..SetSharedGamePlaytimeUseCase.MAX_MANUAL_SHARED_MINUTES }
                ?.let { gameDao.setManualSharedMinutes(backupGame.appId, it) }
            gameDao.setRecencyFromBackup(
                appId = backupGame.appId,
                firstSeenAt = backupGame.firstSeenAt?.iso8601ToEpochMilli(),
                lastPlayedAt = backupGame.lastPlayedAt?.iso8601ToEpochMilli(),
                returnedToPlayAt = backupGame.returnedToPlayAt?.iso8601ToEpochMilli(),
            )
        }
    }

    private suspend fun mergeExcludedSharedGame(row: BackupExcludedSharedGame) {
        excludedSharedGameDao.upsert(
            ExcludedSharedGame(
                appId = row.appId,
                name = row.name,
                excludedAt = row.excludedAt.iso8601ToEpochMilli(),
            ),
        )
    }

    private suspend fun mergeSession(backupSession: BackupSession) {
        val startAt = backupSession.startAt.iso8601ToEpochMilli()
        val endAt = backupSession.endAt?.iso8601ToEpochMilli()
        val existing = sessionDao.findByNaturalKey(backupSession.appId, startAt, endAt)
        if (existing != null) {
            sessionDao.update(existing.copy(minutes = backupSession.minutes))
        } else {
            sessionDao.insert(
                Session(
                    appId = backupSession.appId,
                    startAt = startAt,
                    endAt = endAt,
                    minutes = backupSession.minutes,
                    // A merged session is, by construction, a completed historical record —
                    // never the one currently-open session a live sync is still extending.
                    open = false,
                ),
            )
        }
    }

    private suspend fun mergeDailyProgress(backupDay: BackupDailyProgress) {
        dailyProgressDao.upsert(
            DailyProgress(
                date = backupDay.date,
                minutesPlayed = backupDay.minutesPlayed,
                goalMinutesPlayed = backupDay.goalMinutesPlayed,
                // Recomputed unconditionally right after the merge — see GamificationUpdater —
                // so whatever is written here is immediately superseded if it disagrees.
                questMet = backupDay.questMet,
            ),
        )
    }

    private suspend fun mergeHltbData(backupHltb: BackupHltbData) {
        hltbDataDao.upsert(
            HltbData(
                appId = backupHltb.appId,
                hltbId = backupHltb.hltbId,
                mainStoryMinutes = backupHltb.mainStoryMinutes,
                mainExtraMinutes = backupHltb.mainExtraMinutes,
                completionistMinutes = backupHltb.completionistMinutes,
                allStylesMinutes = backupHltb.allStylesMinutes,
                fetchedAt = backupHltb.fetchedAt,
                // Audited against tasks.md 3.6: forward-compatible tolerance for an enum name the
                // preflight validator does not check (2.2's categories are dates, timestamps,
                // appIds, references, and ranges — never enum-name spelling), so this stays a
                // fallback rather than a preflight bug surfacing.
                matchStatus = runCatching { HltbMatchStatus.valueOf(backupHltb.matchStatus) }
                    .getOrDefault(HltbMatchStatus.UNMATCHED),
                candidatesJson = null,
                origin = runCatching { HltbDataOrigin.valueOf(backupHltb.origin) }
                    .getOrDefault(HltbDataOrigin.AUTOMATIC),
            ),
        )
    }

    /**
     * Rarity-snapshot rule (backup-restore spec, "Achievement rarity snapshot is protected during
     * import"): once frozen locally, a snapshot is retained unless the import carries its own
     * snapshot for the same achievement that wins under [importedWins] — the earlier unlock is by
     * definition nearer the true first unlock, and comparing timestamps rather than trusting
     * whichever side merges first is what makes import order not matter.
     */
    private suspend fun mergeAchievement(backupAchievement: BackupAchievement) {
        val existing = achievementDao.getOne(backupAchievement.appId, backupAchievement.apiName)
        val importedUnlockedAt = backupAchievement.unlockedAt?.iso8601ToEpochMilli()

        if (existing?.snapshotPercent != null) {
            // Nothing to compare against without an imported snapshot of its own: the local
            // freeze stands untouched.
            val importedSnapshot = backupAchievement.snapshotPercent ?: return
            if (!importedWins(importedUnlockedAt, importedSnapshot, existing)) return
        }

        // Carry the stored row forward rather than rebuilding it: `description`, `hidden`, and
        // especially `retired` have no representation in the backup format, and defaulting them
        // would resurrect a retired achievement into the unlocked/XP queries (which filter on
        // `retired = 0`) until a reconciliation pass happened to repair it.
        val merged = existing?.copy(
            displayName = backupAchievement.displayName ?: existing.displayName,
            unlocked = true,
            unlockedAt = importedUnlockedAt,
            snapshotPercent = backupAchievement.snapshotPercent,
        ) ?: Achievement(
            appId = backupAchievement.appId,
            apiName = backupAchievement.apiName,
            displayName = backupAchievement.displayName,
            iconUrl = null,
            unlocked = true,
            unlockedAt = importedUnlockedAt,
            globalPercent = null,
            snapshotPercent = backupAchievement.snapshotPercent,
            fetchedAt = time.nowMillis(),
        )
        achievementDao.upsertAll(listOf(merged))
    }

    /**
     * Whether an imported snapshot displaces the locally frozen one.
     *
     * Primary rule is the earlier unlock. Equal unlock timestamps are a real case, not a
     * degenerate one — two devices can observe the same Steam `unlockedAt` yet freeze different
     * percentages, because each captures rarity when *it* first sees the unlock.
     *
     * The tie is broken on the lower percentage as a **canonical choice, not as evidence of which
     * observation came first**: global rarity is a ratio, so it can fall as the player population
     * grows even while more players unlock the achievement, and the two timestamps here are equal
     * by definition. What matters is that the rule is total and deterministic — that is what makes
     * the merge order-independent, which is the whole reason for earlier-unlock-wins.
     */
    private fun importedWins(
        importedUnlockedAt: Long?,
        importedSnapshot: Double,
        existing: Achievement,
    ): Boolean {
        val existingSnapshot = existing.snapshotPercent ?: return true
        // An import with no unlock time carries no evidence of being earlier.
        if (importedUnlockedAt == null) return false
        val existingUnlockedAt = existing.unlockedAt ?: return true
        return when {
            importedUnlockedAt < existingUnlockedAt -> true
            importedUnlockedAt > existingUnlockedAt -> false
            else -> importedSnapshot < existingSnapshot
        }
    }

    /**
     * Merge one collection by its id (PK upsert): a row with the same id is overwritten, a new
     * one is inserted — never a blind replace, never double-adding a member. Mode/sort/timeBasis
     * parse tolerantly, falling back to the parse-able mode's default sort — the same audited
     * exception as [mergeHltbData]'s `matchStatus`: forward-compatible enum tolerance, not a rule
     * the preflight validator duplicates.
     */
    private suspend fun mergeCollection(backupCollection: BackupCollection) {
        val mode = runCatching { CollectionMode.valueOf(backupCollection.mode) }
            .getOrDefault(CollectionMode.BASIC)
        val sort = runCatching { CollectionSort.valueOf(backupCollection.sort) }
            .getOrDefault(mode.defaultSort())
        val accent = CollectionAccent.parse(backupCollection.accent)
        val timeBasis = runCatching { CollectionTimeBasis.valueOf(backupCollection.timeBasis) }
            .getOrDefault(CollectionTimeBasis.COMPLETIONIST)
        val existing = collectionDao.getById(backupCollection.id)
        val displayOrder = backupCollection.displayOrder
            ?: existing?.displayOrder
            ?: (collectionDao.getAll().maxOfOrNull { it.displayOrder }?.plus(1) ?: 0)
        collectionDao.upsert(
            Collection(
                id = backupCollection.id,
                name = backupCollection.name,
                mode = mode,
                sort = sort,
                targetDate = backupCollection.targetDate,
                accent = accent,
                timeBasis = timeBasis,
                createdAt = backupCollection.createdAt,
                description = backupCollection.description,
                displayOrder = displayOrder,
            ),
        )
    }

    private suspend fun mergeCollectionMember(backupMember: BackupCollectionMember) {
        collectionDao.upsertMember(
            CollectionMember(
                collectionId = backupMember.collectionId,
                appId = backupMember.appId,
                orderIndex = backupMember.orderIndex,
                done = backupMember.done,
            ),
        )
    }
}
private fun String?.toGameSource(fallback: GameSource): GameSource =
    this?.let { runCatching { GameSource.valueOf(it) }.getOrDefault(fallback) } ?: fallback
