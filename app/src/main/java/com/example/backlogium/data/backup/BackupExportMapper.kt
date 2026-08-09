package com.example.backlogium.data.backup

import com.example.backlogium.data.local.SettingsDataStore
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
import com.example.backlogium.data.local.entity.PlayerProfile
import com.example.backlogium.data.local.entity.Session
import com.example.backlogium.data.repo.CredentialsRepository
import com.example.backlogium.data.repo.CredentialsState
import com.example.backlogium.domain.TimeProvider
import com.example.backlogium.gamification.AchievementInput
import com.example.backlogium.gamification.GamePlaytimeInput
import com.example.backlogium.gamification.Gamification
import com.example.backlogium.gamification.RuleConfig
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds a [BackupFile] from the current local state: Room entities + DataStore values, plus an
 * export-time-only `computed` rollup produced by invoking the pure `:gamification` engine
 * (design.md decision 1). Read-only — never writes anything.
 */
@Singleton
class BackupExportMapper @Inject constructor(
    private val gameDao: GameDao,
    private val achievementDao: AchievementDao,
    private val sessionDao: SessionDao,
    private val dailyProgressDao: DailyProgressDao,
    private val hltbDataDao: HltbDataDao,
    private val playerProfileDao: PlayerProfileDao,
    private val collectionDao: CollectionDao,
    private val settings: SettingsDataStore,
    private val credentials: CredentialsRepository,
    private val time: TimeProvider,
) {
    suspend fun buildExport(): BackupFile {
        val config = settings.ruleConfigFlow.first()
        val sortPrefs = settings.librarySortFlow.first()
        val games = gameDao.getAll()
        val achievements = achievementDao.getAllUnlocked()
        val sessions = sessionDao.getAll()
        val days = dailyProgressDao.getAllOrdered()
        val hltb = hltbDataDao.getAll()
        val profile = playerProfileDao.get() ?: PlayerProfile()
        val collections = collectionDao.getAll()
        val collectionMembers = collectionDao.getAllMembers()

        val steamId64 = (credentials.currentCredentials() as? CredentialsState.Configured)?.steamId
            ?: profile.steamId

        return BackupFile(
            exportedAt = time.nowMillis().toIso8601(),
            identity = BackupIdentity(steamId64 = steamId64),
            ruleConfig = config.toBackup(),
            games = games.map { it.toBackup() },
            achievements = achievements.map { it.toBackup() },
            sessions = sessions.map { it.toBackup() },
            dailyProgress = days.map { it.toBackup() },
            hltbData = hltb.map { data ->
                BackupHltbData(
                    appId = data.appId,
                    hltbId = data.hltbId,
                    mainStoryMinutes = data.mainStoryMinutes,
                    mainExtraMinutes = data.mainExtraMinutes,
                    completionistMinutes = data.completionistMinutes,
                    allStylesMinutes = data.allStylesMinutes,
                    matchStatus = data.matchStatus.name,
                )
            },
            librarySortPrefs = BackupLibrarySortPrefs(
                focus = sortPrefs.focus.name,
                library = sortPrefs.library.name,
            ),
            playerProfile = BackupPlayerProfile(
                totalXp = profile.totalXp,
                level = profile.level,
                currentStreak = profile.currentStreak,
                longestStreak = profile.longestStreak,
                playtimeBackfilled = profile.playtimeBackfilled,
            ),
            computed = buildComputed(games, achievements, sessions, days, hltb, config),
            collections = collections.map { it.toBackup() },
            collectionMembers = collectionMembers.map { it.toBackup() },
        )
    }

    private fun buildComputed(
        games: List<Game>,
        unlockedAchievements: List<Achievement>,
        sessions: List<Session>,
        days: List<DailyProgress>,
        hltb: List<com.example.backlogium.data.local.entity.HltbData>,
        config: RuleConfig,
    ): BackupComputed {
        val hltbByAppId = hltb.associateBy { it.appId }
        val trackedByGame = sessions.groupBy { it.appId }.mapValues { (_, s) -> s.sumOf { it.minutes } }
        val achievementsByGame = unlockedAchievements.groupBy { it.appId }

        val xpPerGame = games.mapNotNull { game ->
            val minutes = game.backfillMinutes + (trackedByGame[game.appId] ?: 0)
            val achievementXp = Gamification.achievementXp(
                achievementsByGame[game.appId].orEmpty().toAchievementInputs(),
                config,
            )
            val gameXp = Gamification.gameXp(
                minutes,
                hltbByAppId[game.appId]?.completionistMinutes,
                config,
            )
            val total = gameXp + achievementXp
            if (minutes <= 0 && achievementXp <= 0) null else BackupGameXp(game.appId, game.name, total)
        }

        val zone = time.zone()
        val xpTimeline = days.map { day ->
            val cutoffMillis = java.time.LocalDate.parse(day.date)
                .plusDays(1)
                .atStartOfDay(zone)
                .toInstant()
                .toEpochMilli()

            val cumulativeGames = games.mapNotNull { game ->
                val trackedSoFar = sessions
                    .filter { it.appId == game.appId && it.startAt < cutoffMillis }
                    .sumOf { it.minutes }
                val minutes = game.backfillMinutes + trackedSoFar
                if (minutes <= 0) null else GamePlaytimeInput(
                    gameId = game.appId.toString(),
                    minutesPlayed = minutes,
                    completionistAverageMinutes = hltbByAppId[game.appId]?.completionistMinutes,
                )
            }
            val cumulativeAchievements = unlockedAchievements
                .filter { it.unlockedAt != null && it.unlockedAt < cutoffMillis }
                .mapIndexed { index, a ->
                    AchievementInput(id = "${a.appId}#$index", unlocked = true, globalUnlockPercent = a.snapshotPercent)
                }
            val xpState = Gamification.xp(cumulativeGames, cumulativeAchievements, config)
            BackupDayXp(date = day.date, cumulativeXp = xpState.totalXp)
        }

        return BackupComputed(xpPerGame = xpPerGame, xpTimeline = xpTimeline)
    }
}

private fun List<Achievement>.toAchievementInputs(): List<AchievementInput> =
    mapIndexed { index, a -> AchievementInput(id = "${a.appId}#$index", unlocked = true, globalUnlockPercent = a.snapshotPercent) }

private fun RuleConfig.toBackup() = BackupRuleConfig(
    xpPerMinute = xpPerMinute,
    levelBase = levelBase,
    questThresholdMin = questThresholdMin,
    questMode = questMode.name,
    streakGraceDays = streakGraceDays,
    commonAchievementXp = commonAchievementXp,
    uncommonAchievementXp = uncommonAchievementXp,
    rareAchievementXp = rareAchievementXp,
    epicAchievementXp = epicAchievementXp,
    legendaryAchievementXp = legendaryAchievementXp,
)

private fun Game.toBackup() = BackupGame(
    appId = appId,
    name = name,
    isGoal = isGoal,
    backfillMinutes = backfillMinutes,
)

private fun Achievement.toBackup() = BackupAchievement(
    appId = appId,
    apiName = apiName,
    displayName = displayName,
    snapshotPercent = snapshotPercent,
    unlockedAt = unlockedAt?.toIso8601(),
)

private fun Session.toBackup() = BackupSession(
    appId = appId,
    startAt = startAt.toIso8601(),
    endAt = endAt?.toIso8601(),
    minutes = minutes,
)

private fun DailyProgress.toBackup() = BackupDailyProgress(
    date = date,
    minutesPlayed = minutesPlayed,
    goalMinutesPlayed = goalMinutesPlayed,
    questMet = questMet,
)

private fun Collection.toBackup() = BackupCollection(
    id = id,
    name = name,
    mode = mode.name,
    sort = sort.name,
    targetDate = targetDate,
    createdAt = createdAt,
    accent = accent?.name,
    timeBasis = timeBasis.name,
    description = description,
    displayOrder = displayOrder,
)

private fun CollectionMember.toBackup() = BackupCollectionMember(
    collectionId = collectionId,
    appId = appId,
    orderIndex = orderIndex,
    done = done,
)
