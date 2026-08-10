package com.example.backlogium.domain

import com.example.backlogium.data.local.dao.AchievementCounts
import com.example.backlogium.data.local.dao.AchievementDao
import com.example.backlogium.data.local.dao.AchievementFetchedAt
import com.example.backlogium.data.local.dao.AchievementRarity
import com.example.backlogium.data.local.dao.AchievementUnlock
import com.example.backlogium.data.local.dao.DailyProgressDao
import com.example.backlogium.data.local.dao.GameDao
import com.example.backlogium.data.local.dao.GameSessionCounts
import com.example.backlogium.data.local.dao.GameTrackedMinutes
import com.example.backlogium.data.local.dao.HltbDataDao
import com.example.backlogium.data.local.dao.PlayerProfileDao
import com.example.backlogium.data.local.dao.SessionDao
import com.example.backlogium.data.local.entity.Achievement
import com.example.backlogium.data.local.entity.DailyProgress
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.HltbData
import com.example.backlogium.data.local.entity.HltbMatchStatus
import com.example.backlogium.data.local.entity.PlayerProfile
import com.example.backlogium.data.local.entity.Session
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * In-memory DAO stand-ins shared by the tests that drive [GamificationUpdater] — directly, or
 * through [UpdateRuleConfigUseCase]. Each implements only the surface the updater exercises;
 * the rest returns empty. The two stores the updater writes to count their upserts, so a
 * "computes without persisting" claim can be asserted rather than inferred.
 */

internal class FakeSessionDao(private val sessions: List<Session>) : SessionDao {
    override suspend fun insert(session: Session): Long = 0L
    override suspend fun update(session: Session) = Unit
    override suspend fun getOpenSession(appId: Long): Session? = null
    override fun observeSince(cutoff: Long): Flow<List<Session>> =
        flowOf(sessions.filter { it.startAt >= cutoff })

    override fun observeBetween(startInclusive: Long, endExclusive: Long): Flow<List<Session>> =
        flowOf(sessions.filter { it.startAt >= startInclusive && it.startAt < endExclusive })

    override fun observeClosedSince(cutoff: Long): Flow<List<Session>> =
        flowOf(sessions.filter { it.startAt >= cutoff && !it.open })
    override suspend fun getAll(): List<Session> = sessions
    override fun observeEarliestSessionStart(): Flow<Long?> = flowOf(sessions.minOfOrNull { it.startAt })
    override suspend fun findByNaturalKey(appId: Long, startAt: Long, endAt: Long?): Session? =
        sessions.firstOrNull { it.appId == appId && it.startAt == startAt && it.endAt == endAt }

    override suspend fun trackedMinutesByGame(): List<GameTrackedMinutes> =
        sessions.groupBy { it.appId }
            .map { (appId, group) -> GameTrackedMinutes(appId, group.sumOf { it.minutes }) }

    override fun observeTrackedMinutesByGame(): Flow<List<GameTrackedMinutes>> = flowOf(
        sessions.groupBy { it.appId }
            .map { (appId, group) -> GameTrackedMinutes(appId, group.sumOf { it.minutes }) },
    )

    override fun observeMinutesByGameSince(cutoff: Long): Flow<List<GameTrackedMinutes>> = flowOf(
        sessions.filter { it.startAt >= cutoff }
            .groupBy { it.appId }
            .map { (appId, group) -> GameTrackedMinutes(appId, group.sumOf { it.minutes }) },
    )

    override fun observeMinutesByGameBetween(
        startInclusive: Long,
        endExclusive: Long,
    ): Flow<List<GameTrackedMinutes>> = flowOf(
        sessions.filter { it.startAt >= startInclusive && it.startAt < endExclusive }
            .groupBy { it.appId }
            .map { (appId, group) -> GameTrackedMinutes(appId, group.sumOf { it.minutes }) },
    )

    override fun observeSessionCountsByGame(): Flow<List<GameSessionCounts>> = flowOf(
        sessions.groupBy { it.appId }
            .map { (appId, group) -> GameSessionCounts(appId, group.size) },
    )
}

/**
 * HLTB stand-in. With no configured rows every lookup returns null, exercising the engine's
 * flat-rate fallback; [completionistByAppId] supplies a resolved completionist length for
 * specific games so the diminishing-returns taper can be exercised.
 */
internal class FakeHltbDataDao(
    private val completionistByAppId: Map<Long, Int> = emptyMap(),
) : HltbDataDao {
    override suspend fun upsert(data: HltbData) = Unit
    override suspend fun getByAppId(appId: Long): HltbData? =
        completionistByAppId[appId]?.let { minutes ->
            HltbData(
                appId = appId,
                completionistMinutes = minutes,
                fetchedAt = 0L,
                matchStatus = HltbMatchStatus.RESOLVED,
            )
        }

    override fun observeAll(): Flow<List<HltbData>> = flowOf(emptyList())
    override suspend fun getAll(): List<HltbData> = emptyList()
    override fun observeNeedsReview(): Flow<List<HltbData>> = flowOf(emptyList())
    override suspend fun appIdsStaleOrMissing(cutoff: Long): List<Long> = emptyList()
}

/** Seeded game store; only [getAll] is exercised by the updater. */
internal class FakeGameDao(games: List<Game>) : GameDao {
    private val store = games.associateBy { it.appId }.toMutableMap()

    override suspend fun upsertAll(games: List<Game>) {
        games.forEach { store[it.appId] = it }
    }

    override suspend fun upsert(game: Game) {
        store[game.appId] = game
    }

    override fun observeLibrary(): Flow<List<Game>> = flowOf(store.values.toList())
    override fun observeGoalGames(): Flow<List<Game>> = flowOf(emptyList())
    override fun observeBacklog(): Flow<List<Game>> = flowOf(emptyList())
    override suspend fun allAppIds(): List<Long> = store.keys.toList()
    override suspend fun getAll(): List<Game> = store.values.toList()
    override suspend fun getById(appId: Long): Game? = store[appId]
    override suspend fun setGoal(appId: Long, isGoal: Boolean, targetMinutes: Int?) = Unit
    override suspend fun setGoalFlag(appId: Long, isGoal: Boolean) = Unit
    override suspend fun count(): Int = store.size
    override suspend fun setBackfillMinutes(appId: Long, minutes: Int) {
        store[appId]?.let { store[appId] = it.copy(backfillMinutes = minutes) }
    }
}

internal class FakeDailyProgressDao(initial: List<DailyProgress>) : DailyProgressDao {
    private val store = linkedMapOf<String, DailyProgress>()

    /** Counts writes, so `compute()` can be asserted to make none. */
    var upsertCount = 0
        private set

    init {
        initial.forEach { store[it.date] = it }
    }

    override suspend fun upsert(day: DailyProgress) {
        upsertCount++
        store[day.date] = day
    }

    override suspend fun getByDate(date: String): DailyProgress? = store[date]
    override fun observeAll(): Flow<List<DailyProgress>> =
        flowOf(store.values.sortedByDescending { it.date })

    override suspend fun getAllOrdered(): List<DailyProgress> = store.values.sortedBy { it.date }
}

internal class FakePlayerProfileDao(initial: PlayerProfile? = null) : PlayerProfileDao {
    private var profile: PlayerProfile? = initial

    /** Counts writes, so `compute()` can be asserted to make none. */
    var upsertCount = 0
        private set

    override suspend fun upsert(profile: PlayerProfile) {
        upsertCount++
        this.profile = profile
    }

    override fun observe(): Flow<PlayerProfile?> = flowOf(profile)
    override suspend fun get(): PlayerProfile? = profile
}

/** Seeded, read-only stand-in: only [getAllUnlocked] is exercised by the updater. */
internal class FakeAchievementDao(private val achievements: List<Achievement>) : AchievementDao {
    override suspend fun upsertAll(achievements: List<Achievement>) = Unit
    override fun observeForGame(appId: Long): Flow<List<Achievement>> = flowOf(emptyList())
    override suspend fun getForGame(appId: Long): List<Achievement> = emptyList()
    override suspend fun getOne(appId: Long, apiName: String): Achievement? =
        achievements.firstOrNull { it.appId == appId && it.apiName == apiName }
    override fun observeCounts(): Flow<List<AchievementCounts>> = flowOf(emptyList())
    override suspend fun fetchedAtByApp(): List<AchievementFetchedAt> = emptyList()
    override suspend fun deleteMarker(appId: Long) = Unit
    override suspend fun getAllUnlocked(): List<Achievement> = achievements.filter { it.unlocked }
    override fun observeUnlockedRarity(): Flow<List<AchievementRarity>> = flowOf(
        achievements.filter { it.unlocked }.map { AchievementRarity(it.appId, it.snapshotPercent) },
    )
    override fun observeUnlockedSince(cutoff: Long): Flow<List<AchievementUnlock>> = flowOf(
        achievements.filter { it.unlocked && (it.unlockedAt ?: 0L) >= cutoff }
            .map { AchievementUnlock(it.appId, it.iconUrl, it.unlockedAt ?: 0L) },
    )
}

internal fun testSession(minutes: Int, appId: Long = 1L) = Session(
    appId = appId,
    startAt = 0L,
    endAt = 0L,
    minutes = minutes,
    open = false,
)

internal fun testGame(appId: Long, backfillMinutes: Int) = Game(
    appId = appId,
    name = "Game $appId",
    iconUrl = "",
    playtimeForever = 0,
    playtime2Weeks = 0,
    lastPlaytime = 0,
    backfillMinutes = backfillMinutes,
)
