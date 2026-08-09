package com.example.backlogium.data.backup

import com.example.backlogium.data.local.dao.AchievementDao
import com.example.backlogium.data.local.dao.CollectionDao
import com.example.backlogium.data.local.dao.DailyProgressDao
import com.example.backlogium.data.local.dao.GameDao
import com.example.backlogium.data.local.dao.HltbDataDao
import com.example.backlogium.data.local.dao.PlayerProfileDao
import com.example.backlogium.data.local.dao.SessionDao
import com.example.backlogium.data.local.entity.Achievement
import com.example.backlogium.data.local.entity.Collection
import com.example.backlogium.data.local.entity.CollectionMember
import com.example.backlogium.data.local.entity.DailyProgress
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.HltbData
import com.example.backlogium.data.local.entity.HltbMatchStatus
import com.example.backlogium.data.local.entity.PlayerProfile
import com.example.backlogium.data.local.entity.Session
import com.example.backlogium.domain.CollectionAccent
import com.example.backlogium.domain.CollectionMode
import com.example.backlogium.domain.CollectionSort
import com.example.backlogium.domain.CollectionTimeBasis
import com.example.backlogium.domain.GamificationUpdater
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
    private val gamificationUpdater: GamificationUpdater,
    private val time: TimeProvider,
) {
    /**
     * [config] is the app's currently active [RuleConfig] — never the file's own `ruleConfig`,
     * which is export-time-only (see [BackupFile.ruleConfig]'s doc). Passed in rather than read
     * internally so this engine stays a plain, JVM-testable class, mirroring [GamificationUpdater].
     */
    suspend fun merge(file: BackupFile, config: RuleConfig) {
        // Games first: Session/Achievement/HltbData all carry a FOREIGN KEY on games.appId, so a
        // fresh-install restore (no games synced yet) needs the skeleton row to exist first.
        file.games.forEach { mergeGame(it) }
        file.sessions.forEach { mergeSession(it) }
        file.dailyProgress.forEach { mergeDailyProgress(it) }
        file.hltbData.forEach { mergeHltbData(it) }
        file.achievements.forEach { mergeAchievement(it) }
        file.collections.forEach { mergeCollection(it) }
        file.collectionMembers.forEach { mergeCollectionMember(it) }

        val importedLongestStreak = file.playerProfile.longestStreak
        val importedBackfilled = file.playerProfile.playtimeBackfilled

        val result = gamificationUpdater.compute(time.today(), config)
        gamificationUpdater.persist(
            result.copy(longestStreak = maxOf(result.longestStreak, importedLongestStreak)),
        )

        // playtimeBackfilled is a historical fact ("has this account ever backfilled"), not a
        // derivation — folded in like longestStreak, as a one-way OR rather than a replace, so
        // an import can never un-flag an import that already happened locally.
        val profile = playerProfileDao.get() ?: PlayerProfile()
        if (importedBackfilled && !profile.playtimeBackfilled) {
            playerProfileDao.upsert(profile.copy(playtimeBackfilled = true))
        }
    }

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
                ),
            )
        } else {
            gameDao.setGoalFlag(backupGame.appId, backupGame.isGoal)
            gameDao.setBackfillMinutes(backupGame.appId, backupGame.backfillMinutes)
        }
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
                fetchedAt = time.nowMillis(),
                matchStatus = runCatching { HltbMatchStatus.valueOf(backupHltb.matchStatus) }
                    .getOrDefault(HltbMatchStatus.UNMATCHED),
                candidatesJson = null,
            ),
        )
    }

    private suspend fun mergeAchievement(backupAchievement: BackupAchievement) {
        val existing = achievementDao.getOne(backupAchievement.appId, backupAchievement.apiName)
        // Once frozen locally, snapshotPercent is never overwritten by an import — the same
        // invariant the entity already enforces for ordinary syncs (Achievement.kt doc comment).
        if (existing != null && existing.snapshotPercent != null) return

        val unlockedAt = backupAchievement.unlockedAt?.iso8601ToEpochMilli()
        achievementDao.upsertAll(
            listOf(
                Achievement(
                    appId = backupAchievement.appId,
                    apiName = backupAchievement.apiName,
                    displayName = backupAchievement.displayName ?: existing?.displayName,
                    iconUrl = existing?.iconUrl,
                    unlocked = true,
                    unlockedAt = unlockedAt,
                    globalPercent = existing?.globalPercent,
                    snapshotPercent = backupAchievement.snapshotPercent,
                    fetchedAt = existing?.fetchedAt ?: time.nowMillis(),
                ),
            ),
        )
    }

    /**
     * Merge one collection by its id (PK upsert): a row with the same id is overwritten, a new
     * one is inserted — never a blind replace, never double-adding a member. Mode/sort parse
     * tolerantly, falling back to the parse-able mode's default sort.
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
