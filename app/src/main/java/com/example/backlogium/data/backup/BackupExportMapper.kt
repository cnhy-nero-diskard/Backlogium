package com.example.backlogium.data.backup

import com.example.backlogium.data.local.SettingsDataStore
import com.example.backlogium.data.local.dao.AchievementDao
import com.example.backlogium.data.local.dao.CollectionDao
import com.example.backlogium.data.local.dao.DailyProgressDao
import com.example.backlogium.data.local.dao.GameDao
import com.example.backlogium.data.local.dao.ExcludedSharedGameDao
import com.example.backlogium.data.local.dao.HiddenGameDao
import com.example.backlogium.data.local.dao.HltbDataDao
import com.example.backlogium.data.local.dao.PlayerProfileDao
import com.example.backlogium.data.local.dao.SessionDao
import com.example.backlogium.data.local.entity.Achievement
import com.example.backlogium.data.local.entity.Collection
import com.example.backlogium.data.local.entity.CollectionMember
import com.example.backlogium.data.local.entity.DailyProgress
import com.example.backlogium.data.local.entity.ExcludedSharedGame
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.HiddenGame
import com.example.backlogium.data.local.entity.PlayerProfile
import com.example.backlogium.data.local.entity.Session
import com.example.backlogium.data.repo.CredentialsProvider
import com.example.backlogium.domain.CollectionSort
import com.example.backlogium.domain.TimeProvider
import com.example.backlogium.domain.defaultSort
import com.example.backlogium.gamification.AchievementInput
import com.example.backlogium.gamification.GamePlaytimeInput
import com.example.backlogium.gamification.Gamification
import com.example.backlogium.gamification.RuleConfig
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** Everything read from Room for one export, captured inside a single [DatabaseTransactionScope.run]. */
private data class ExportSnapshot(
    val games: List<Game>,
    val excludedSharedGames: List<ExcludedSharedGame>,
    val achievements: List<Achievement>,
    val sessions: List<Session>,
    val days: List<DailyProgress>,
    val hltb: List<com.example.backlogium.data.local.entity.HltbData>,
    val profile: PlayerProfile,
    val collections: List<Collection>,
    val collectionMembers: List<CollectionMember>,
    val hiddenGames: List<HiddenGame>,
    /** The active account's recovery graph, read in the same transaction as everything else. */
    val recovery: BackupRecovery,
)

/**
 * Builds a [BackupFile] from the current local state: Room entities + DataStore values, plus an
 * export-time-only `computed` rollup produced by invoking the pure `:gamification` engine
 * (design.md decision 1). Read-only — never writes anything.
 *
 * The Room reads run inside one [transaction] (design.md decision 4), so a concurrent sync commit
 * cannot produce a file combining games from before it with sessions/aggregates from after — Room
 * gives every read the same consistent snapshot rather than serializing the export against the
 * sync. `settings`/`credentials` are read *before* opening it: they cannot join a Room transaction,
 * and they do not participate in the cross-table invariants a hybrid would violate.
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
    private val excludedSharedGameDao: ExcludedSharedGameDao,
    private val hiddenGameDao: HiddenGameDao,
    /**
     * The recovery graph's read seam. Defaulted to the no-op store so a plain JVM test that does
     * not exercise recovery stays constructible; production binds [DaoRecoveryBackupStore].
     */
    private val recoveryStore: RecoveryBackupStore = NoopRecoveryBackupStore,
    private val settings: SettingsDataStore,
    /**
     * Narrowed to the provider interface: the export reads the configured SteamID64 and nothing
     * else, and depending on the full repository would drag credential *writing* into a read-only
     * mapper (and out of reach of a plain JVM test).
     */
    private val credentials: CredentialsProvider,
    private val time: TimeProvider,
    private val transaction: DatabaseTransactionScope = PassThroughTransactionScope,
) {
    suspend fun buildExport(): BackupFile {
        val config = settings.ruleConfigFlow.first()
        val sortPrefs = settings.librarySortFlow.first()
        val steamId64FromCredentials = credentials.currentCredentials()?.steamId

        val snapshot = transaction.run {
            val profile = playerProfileDao.get() ?: PlayerProfile()
            val account = steamId64FromCredentials ?: profile.steamId
            ExportSnapshot(
                games = gameDao.getAll(),
                excludedSharedGames = excludedSharedGameDao.getAll(),
                achievements = achievementDao.getAllUnlocked(),
                sessions = sessionDao.getAll(),
                days = dailyProgressDao.getAllOrdered(),
                hltb = hltbDataDao.getAll(),
                profile = profile,
                collections = collectionDao.getAll(),
                collectionMembers = collectionDao.getAllMembers(),
                hiddenGames = hiddenGameDao.getAll(),
                recovery = recoveryStore.read(account),
            )
        }
        val (games, excludedSharedGames, achievements, sessions, days, hltb, profile, collections, collectionMembers) = snapshot

        val steamId64 = steamId64FromCredentials ?: profile.steamId

        return BackupFile(
            formatVersion = BackupFile.CURRENT_FORMAT_VERSION,
            exportedAt = time.nowMillis().toIso8601(),
            identity = BackupIdentity(steamId64 = steamId64),
            ruleConfig = config.toBackup(),
            games = games.map { it.toBackup() },
            excludedSharedGames = excludedSharedGames.map { it.toBackup() },
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
                    fetchedAt = data.fetchedAt,
                    origin = data.origin.name,
                )
            },
            librarySortPrefs = BackupLibrarySortPrefs(
                focus = sortPrefs.focus.name,
                library = sortPrefs.library.name,
                focusDirection = sortPrefs.focusDirection.name,
                libraryDirection = sortPrefs.libraryDirection.name,
            ),
            playerProfile = BackupPlayerProfile(
                totalXp = profile.totalXp,
                level = profile.level,
                currentStreak = profile.currentStreak,
                longestStreak = profile.longestStreak,
                playtimeBackfilled = profile.playtimeBackfilled,
            ),
            computed = buildComputed(games, achievements, sessions, days, hltb, config, snapshot.recovery),
            collections = collections.map { it.toBackup() },
            collectionMembers = collectionMembers.map { it.toBackup() },
            hiddenGames = snapshot.hiddenGames.map { it.toBackup() },
            recovery = snapshot.recovery,
        )
    }

    private fun buildComputed(
        games: List<Game>,
        unlockedAchievements: List<Achievement>,
        sessions: List<Session>,
        days: List<DailyProgress>,
        hltb: List<com.example.backlogium.data.local.entity.HltbData>,
        config: RuleConfig,
        recovery: BackupRecovery,
    ): BackupComputed {
        // Same ceiling GamificationUpdater recomputes under: a legacy above-ceiling value
        // bypasses Settings validation, so the raw persisted config cannot reach the engine here
        // (auditfix-session-ledger-integrity, #114). The exported ruleConfig above stays raw.
        val safeConfig = config.coercedToSafeCeilings()
        val hltbByAppId = hltb.associateBy { it.appId }
        // Canonical quantity is each funded contribution once plus only each session's unlinked
        // legacy remainder. Timed shares are not added to their host session a second time, and a
        // detached share still counts through its contribution (design.md §7).
        val trackedByGame = canonicalTrackedMinutesByGame(sessions, recovery)
        val trackedMinutes: (Long) -> Int = { appId ->
            (trackedByGame[appId] ?: 0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        val achievementsByGame = unlockedAchievements.groupBy { it.appId }
        val zone = time.zone()
        val datedTimeline = CanonicalDatedXpTimeline(sessions, recovery, zone)

        val xpPerGame = games.mapNotNull { game ->
            val minutes = game.backupXpMinutes(trackedMinutes(game.appId))
            val achievementXp = Gamification.achievementXp(
                achievementsByGame[game.appId].orEmpty().toAchievementInputs(),
                safeConfig,
            )
            val gameXp = Gamification.gameXp(
                minutes,
                hltbByAppId[game.appId]?.completionistMinutes,
                safeConfig,
            )
            val total = gameXp + achievementXp
            if (minutes <= 0 && achievementXp <= 0) null else BackupGameXp(game.appId, game.name, total)
        }

        val xpTimeline = days.map { day ->
            val cutoffDate = java.time.LocalDate.parse(day.date).plusDays(1)
            val cutoffMillis = cutoffDate.atStartOfDay(zone).toInstant().toEpochMilli()

            val cumulativeGames = games.mapNotNull { game ->
                val creditedSoFar = datedTimeline.minutesBefore(game.appId, cutoffDate)
                val minutes = game.backupXpMinutes(
                    creditedSoFar.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                )
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
            val xpState = Gamification.xp(cumulativeGames, cumulativeAchievements, safeConfig)
            BackupDayXp(date = day.date, cumulativeXp = xpState.totalXp)
        }

        return BackupComputed(xpPerGame = xpPerGame, xpTimeline = xpTimeline)
    }
}

/**
 * The playtime XP input for one game's export snapshot: frozen backfill offset (owned games) plus
 * manual shared-game estimate (mutually exclusive per game, gated by source) plus tracked minutes
 * as of [trackedMinutes] — the same cumulative figure [GamificationUpdater] feeds the engine, so a
 * restored backup's snapshotted XP matches what a live recompute would produce
 * (add-shared-game-playtime-and-filter). `internal` so it is directly unit-testable without
 * standing up this class's full DI graph.
 */
internal fun Game.backupXpMinutes(trackedMinutes: Int): Int =
    (backfillMinutes.toLong() + manualSharedMinutes.toLong() + trackedMinutes.toLong())
        .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

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
    // An explicit absence where unknown — a null field rather than an epoch-zero timestamp, which
    // would import as "arrived in 1970" and read as a genuine (if very old) recorded arrival.
    firstSeenAt = firstSeenAt?.toIso8601(),
    lastPlayedAt = lastPlayedAt?.toIso8601(),
    returnedToPlayAt = returnedToPlayAt?.toIso8601(),
    source = source.name,
    manualSharedMinutes = manualSharedMinutes,
)
private fun ExcludedSharedGame.toBackup() = BackupExcludedSharedGame(
    appId = appId,
    name = name,
    excludedAt = excludedAt.toIso8601(),
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
    cloudContribution = BackupCloudContribution(
        recoveredSharedPlay = BackupContributionState.valueOf(
            (recoveredSharedPlay?.name ?: BackupContributionState.UNKNOWN.name)),
        timingInformedSteamPlay = BackupContributionState.valueOf(
            (timingInformedSteamPlay?.name ?: BackupContributionState.UNKNOWN.name)),
    ),
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
    sort = (sort.takeUnless { it == CollectionSort.UNAVAILABLE } ?: mode.defaultSort()).name,
    targetDate = targetDate,
    createdAt = createdAt,
    accent = accent?.name,
    timeBasis = timeBasis.name,
    description = description,
    displayOrder = displayOrder,
)

private fun HiddenGame.toBackup() = BackupHiddenGame(
    appId = appId,
    hiddenAt = hiddenAt.toIso8601(),
    fromBulkAction = fromBulkAction,
)

private fun CollectionMember.toBackup() = BackupCollectionMember(
    collectionId = collectionId,
    appId = appId,
    orderIndex = orderIndex,
    done = done,
)

/** One dated allocation used only to place canonical recovery minutes on the XP timeline. */
/** A natural session key portable across export/import and autoincrement ids. */
private data class BackupSessionKey(val appId: Long, val startAt: Long, val endAt: Long?)

private fun Session.backupSessionKey() = BackupSessionKey(appId, startAt, endAt)

/** Canonical all-time quantity for computed rollups; does not depend on allocation materialization. */
private fun canonicalTrackedMinutesByGame(
    sessions: List<Session>,
    recovery: BackupRecovery,
): Map<Long, Long> {
    val rowsByKey = sessions.associateBy(Session::backupSessionKey)
    val fundedBySession = mutableMapOf<BackupSessionKey, Long>()
    recovery.timedPlacements.orEmpty().filterNot { it.superseded }.forEach { placement ->
        val start = placement.sessionStartAt?.iso8601ToEpochMilli() ?: return@forEach
        val key = BackupSessionKey(
            placement.appId,
            start,
            placement.sessionEndAt?.iso8601ToEpochMilli(),
        )
        require(rowsByKey.containsKey(key)) {
            "funded backup share ${placement.placementId} links no exported session"
        }
        fundedBySession[key] = Math.addExact(fundedBySession[key] ?: 0L, placement.minutes)
    }
    val legacyRemainder = mutableMapOf<Long, Long>()
    sessions.forEach { session ->
        val funded = fundedBySession[session.backupSessionKey()] ?: 0L
        require(funded <= session.minutes.toLong()) {
            "funded shares $funded exceed exported session ${session.id} minutes ${session.minutes}"
        }
        val remainder = session.minutes.toLong() - funded
        legacyRemainder[session.appId] = Math.addExact(legacyRemainder[session.appId] ?: 0L, remainder)
    }
    val contributions = recovery.contributions.orEmpty().distinctBy { it.contributionId }
        .groupBy { it.appId }
        .mapValues { (_, rows) -> rows.fold(0L) { sum, row -> Math.addExact(sum, row.authorizedMinutes) } }
    return (legacyRemainder.keys + contributions.keys).associateWith { appId ->
        Math.addExact(legacyRemainder[appId] ?: 0L, contributions[appId] ?: 0L)
    }
}
/**
 * Date-indexed canonical playtime for the export XP timeline. Each session, timed placement,
 * allocation, and recipe is processed once; each daily snapshot then uses a binary search and a
 * prefix sum instead of rescanning the full recovery graph for every game and date.
 */
internal class CanonicalDatedXpTimeline(
    sessions: List<Session>,
    recovery: BackupRecovery,
    zone: java.time.ZoneId,
) {
    private val activityByApp: Map<Long, DatedXpActivitySeries>
    private val recipesByApp: Map<Long, List<DatedXpRecipePrefix>>

    init {
        val activeRecipes = recovery.recipes.orEmpty().filterNot { it.pending }
        val recipeContributionIds = activeRecipes.groupBy { it.appId }
            .mapValues { (_, rows) -> rows.mapTo(hashSetOf()) { it.contributionId } }
        val datedActivity = mutableMapOf<Long, MutableMap<LocalDate, Long>>()
        fun add(appId: Long, date: LocalDate, minutes: Long) {
            val byDate = datedActivity.getOrPut(appId) { mutableMapOf() }
            byDate[date] = Math.addExact(byDate[date] ?: 0L, minutes)
        }

        val fundedBySession = mutableMapOf<BackupSessionKey, Long>()
        val activeTimed = recovery.timedPlacements.orEmpty().filterNot { it.superseded }
        activeTimed.forEach { placement ->
            val start = placement.sessionStartAt?.iso8601ToEpochMilli() ?: return@forEach
            val key = BackupSessionKey(
                placement.appId,
                start,
                placement.sessionEndAt?.iso8601ToEpochMilli(),
            )
            fundedBySession[key] = Math.addExact(fundedBySession[key] ?: 0L, placement.minutes)
        }
        sessions.forEach { session ->
            val remainder = session.minutes.toLong() - (fundedBySession[session.backupSessionKey()] ?: 0L)
            require(remainder >= 0L) { "funded timed shares exceed session ${session.id}" }
            add(
                session.appId,
                java.time.Instant.ofEpochMilli(session.startAt).atZone(zone).toLocalDate(),
                remainder,
            )
        }
        activeTimed.forEach { placement ->
            val date = placement.creditedDate.let(LocalDate::parse)
            add(placement.appId, date, placement.minutes)
        }
        recovery.unknownAllocations.orEmpty()
            .filter { !it.superseded && !it.pendingDate }
            .filter { it.contributionId !in recipeContributionIds[it.appId].orEmpty() }
            .forEach { allocation ->
                val date = allocation.date?.let(LocalDate::parse) ?: return@forEach
                add(allocation.appId, date, allocation.minutes)
            }

        activityByApp = datedActivity.mapValues { (_, byDate) -> DatedXpActivitySeries(byDate) }
        recipesByApp = activeRecipes.mapNotNull { recipe ->
            DatedXpRecipePrefix.from(recipe)?.let { recipe.appId to it }
        }.groupBy({ it.first }, { it.second })
    }

    fun minutesBefore(appId: Long, exclusiveDate: LocalDate): Long {
        var total = activityByApp[appId]?.minutesBefore(exclusiveDate) ?: 0L
        recipesByApp[appId].orEmpty().forEach { recipe ->
            total = Math.addExact(total, recipe.minutesBefore(exclusiveDate))
        }
        return total
    }
}

private class DatedXpActivitySeries(activity: Map<LocalDate, Long>) {
    private val dates: List<LocalDate> = activity.keys.sorted()
    private val prefixMinutes = LongArray(dates.size + 1).also { prefix ->
        dates.forEachIndexed { index, date ->
            prefix[index + 1] = Math.addExact(prefix[index], activity.getValue(date))
        }
    }

    fun minutesBefore(exclusiveDate: LocalDate): Long {
        val index = dates.binarySearch(exclusiveDate)
        val count = if (index >= 0) index else -index - 1
        return prefixMinutes[count]
    }
}

private class DatedXpRecipePrefix(
    private val start: LocalDate,
    private val end: LocalDate,
    private val quotient: Long,
    private val remainder: Long,
    private val excludedRanges: List<ClosedRange<LocalDate>>,
) {
    fun minutesBefore(exclusiveDate: LocalDate): Long {
        val last = minOf(end, exclusiveDate.minusDays(1))
        if (last.isBefore(start)) return 0L
        val eligible = java.time.temporal.ChronoUnit.DAYS.between(start, last) + 1L -
            excludedRanges.sumOf { range ->
                val first = maxOf(start, range.start)
                val final = minOf(last, range.endInclusive)
                if (final.isBefore(first)) 0L
                else java.time.temporal.ChronoUnit.DAYS.between(first, final) + 1L
            }
        if (eligible <= 0L) return 0L
        return Math.addExact(
            Math.multiplyExact(quotient, eligible),
            minOf(remainder, eligible),
        )
    }

    companion object {
        fun from(recipe: BackupAllocationRecipe): DatedXpRecipePrefix? {
            val start = recipe.dateStart?.let(LocalDate::parse) ?: return null
            val end = recipe.dateEnd?.let(LocalDate::parse) ?: return null
            val exclusions = recipe.excludedDatesCsv.orEmpty().split(',')
                .filter(String::isNotBlank).map { token ->
                    val separator = token.indexOf("..")
                    if (separator < 0) {
                        val date = LocalDate.parse(token)
                        date..date
                    } else {
                        LocalDate.parse(token.substring(0, separator))..
                            LocalDate.parse(token.substring(separator + 2))
                    }
                }
            return DatedXpRecipePrefix(
                start = start,
                end = end,
                quotient = recipe.quotient ?: 0L,
                remainder = recipe.remainderCount ?: 0L,
                excludedRanges = exclusions,
            )
        }
    }
}
