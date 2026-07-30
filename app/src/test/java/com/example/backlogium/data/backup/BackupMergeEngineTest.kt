package com.example.backlogium.data.backup

import com.example.backlogium.data.local.dao.AchievementCounts
import com.example.backlogium.data.local.dao.AchievementDao
import com.example.backlogium.data.local.dao.AchievementFetchedAt
import com.example.backlogium.data.local.dao.AchievementRarity
import com.example.backlogium.data.local.dao.AchievementUnlock
import com.example.backlogium.data.local.dao.DailyProgressDao
import com.example.backlogium.data.local.dao.GameDao
import com.example.backlogium.data.local.dao.GameTrackedMinutes
import com.example.backlogium.data.local.dao.HltbDataDao
import com.example.backlogium.data.local.dao.PlayerProfileDao
import com.example.backlogium.data.local.dao.SessionDao
import com.example.backlogium.data.local.entity.Achievement
import com.example.backlogium.data.local.entity.DailyProgress
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.HltbData
import com.example.backlogium.data.local.entity.PlayerProfile
import com.example.backlogium.data.local.entity.Session
import com.example.backlogium.domain.GamificationUpdater
import com.example.backlogium.domain.TimeProvider
import com.example.backlogium.gamification.AchievementInput
import com.example.backlogium.gamification.Gamification
import com.example.backlogium.gamification.RuleConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * Covers the merge engine's core invariants (tasks.md 3.6): no double-counted/duplicated
 * sessions, backfill of non-overlapping data, the achievement rarity snapshot protection, the
 * longest-streak high-water mark, and that aggregates are always recomputed rather than trusted
 * from the imported file.
 */
class BackupMergeEngineTest {

    private data class Harness(
        val engine: BackupMergeEngine,
        val gameDao: FakeGameDao,
        val sessionDao: FakeSessionDao,
        val profileDao: FakePlayerProfileDao,
    )

    private fun newEngine(
        games: MutableMap<Long, Game> = mutableMapOf(),
        sessions: MutableList<Session> = mutableListOf(),
        days: MutableMap<String, DailyProgress> = mutableMapOf(),
        hltb: MutableMap<Long, HltbData> = mutableMapOf(),
        achievements: MutableList<Achievement> = mutableListOf(),
        profile: PlayerProfile? = null,
        today: LocalDate = LocalDate.parse("2026-07-17"),
        nowMillis: Long = 0L,
    ): Harness {
        val gameDao = FakeGameDao(games)
        val sessionDao = FakeSessionDao(sessions)
        val dailyProgressDao = FakeDailyProgressDao(days)
        val hltbDataDao = FakeHltbDataDao(hltb)
        val achievementDao = FakeAchievementDao(achievements)
        val profileDao = FakePlayerProfileDao(profile)
        val time = FixedTimeProvider(today, nowMillis)
        val gamificationUpdater = GamificationUpdater(
            sessionDao, dailyProgressDao, profileDao, hltbDataDao, achievementDao, gameDao,
        )
        val engine = BackupMergeEngine(
            gameDao, sessionDao, dailyProgressDao, hltbDataDao, achievementDao, profileDao,
            gamificationUpdater, time,
        )
        return Harness(engine, gameDao, sessionDao, profileDao)
    }

    private fun baseFile(
        sessions: List<BackupSession> = emptyList(),
        achievements: List<BackupAchievement> = emptyList(),
        games: List<BackupGame> = emptyList(),
        longestStreak: Int = 0,
        totalXp: Int = 999_999, // deliberately implausible, to prove it's never trusted
        currentStreak: Int = 999,
        playtimeBackfilled: Boolean = false,
    ) = BackupFile(
        exportedAt = "2026-07-01T00:00:00Z",
        identity = BackupIdentity(steamId64 = "1"),
        ruleConfig = RuleConfig().toBackupForTest(),
        games = games,
        achievements = achievements,
        sessions = sessions,
        dailyProgress = emptyList(),
        hltbData = emptyList(),
        librarySortPrefs = BackupLibrarySortPrefs(focus = "NAME", library = "PLAYTIME"),
        playerProfile = BackupPlayerProfile(
            totalXp = totalXp,
            level = 99,
            currentStreak = currentStreak,
            longestStreak = longestStreak,
            playtimeBackfilled = playtimeBackfilled,
        ),
        computed = BackupComputed(emptyList(), emptyList()),
    )

    @Test
    fun overlappingSession_replacesInPlace_noDuplicate() = runTest {
        val existing = Session(appId = 1L, startAt = 1_000L, endAt = 2_000L, minutes = 10, open = false)
        val harness = newEngine(
            games = mutableMapOf(1L to testGame(1L)),
            sessions = mutableListOf(existing),
        )
        val file = baseFile(
            sessions = listOf(
                BackupSession(appId = 1L, startAt = 1_000L.toIso8601(), endAt = 2_000L.toIso8601(), minutes = 25),
            ),
        )

        harness.engine.merge(file, RuleConfig())

        val all = harness.sessionDao.getAll()
        assertEquals(1, all.size)
        assertEquals(25, all.single().minutes)
    }

    @Test
    fun nonOverlappingSession_isAdded_existingUntouched() = runTest {
        val existing = Session(appId = 1L, startAt = 1_000L, endAt = 2_000L, minutes = 10, open = false)
        val harness = newEngine(
            games = mutableMapOf(1L to testGame(1L)),
            sessions = mutableListOf(existing),
        )
        val file = baseFile(
            sessions = listOf(
                BackupSession(appId = 1L, startAt = 5_000L.toIso8601(), endAt = 6_000L.toIso8601(), minutes = 15),
            ),
        )

        harness.engine.merge(file, RuleConfig())

        val all = harness.sessionDao.getAll()
        assertEquals(2, all.size)
        assertTrue(all.any { it.startAt == 1_000L && it.minutes == 10 })
        assertTrue(all.any { it.startAt == 5_000L && it.minutes == 15 })
    }

    @Test
    fun achievementSnapshot_localAlreadyFrozen_importDiscarded() = runTest {
        val local = Achievement(
            appId = 1L, apiName = "ACH", unlocked = true, unlockedAt = 500L,
            snapshotPercent = 2.0, fetchedAt = 0L,
        )
        val harness = newEngine(
            games = mutableMapOf(1L to testGame(1L)),
            achievements = mutableListOf(local),
        )
        val file = baseFile(
            achievements = listOf(
                BackupAchievement(
                    appId = 1L, apiName = "ACH", displayName = "Ach",
                    snapshotPercent = 99.0, unlockedAt = 100L.toIso8601(),
                ),
            ),
        )

        harness.engine.merge(file, RuleConfig())

        // The locally frozen 2.0% snapshot must drive the recomputed XP, not the imported 99.0%
        // — computed independently here via the real engine function, not a guessed tier cutoff.
        val expectedXp = Gamification.achievementXp(
            listOf(AchievementInput(id = "ACH", unlocked = true, globalUnlockPercent = 2.0)),
            RuleConfig(),
        )
        assertEquals(expectedXp, harness.profileDao.get()!!.totalXp)
    }

    @Test
    fun achievementSnapshot_noLocalValue_importedValueStored() = runTest {
        val harness = newEngine(games = mutableMapOf(1L to testGame(1L)))
        val file = baseFile(
            achievements = listOf(
                BackupAchievement(
                    appId = 1L, apiName = "ACH", displayName = "Ach",
                    snapshotPercent = 10.0, unlockedAt = 100L.toIso8601(),
                ),
            ),
        )

        harness.engine.merge(file, RuleConfig())

        // Rare-tier (10%) achievement XP (40, per default RuleConfig) proves the import's
        // snapshot was stored and fed into the recompute.
        assertEquals(40, harness.profileDao.get()!!.totalXp)
    }

    @Test
    fun longestStreak_importLower_neverLowersStored() = runTest {
        val harness = newEngine(profile = PlayerProfile(longestStreak = 10))
        val file = baseFile(longestStreak = 1)

        harness.engine.merge(file, RuleConfig())

        assertEquals(10, harness.profileDao.get()!!.longestStreak)
    }

    @Test
    fun longestStreak_importHigherThanStoredAndRecomputed_raisesStored() = runTest {
        val harness = newEngine(profile = PlayerProfile(longestStreak = 2))
        val file = baseFile(longestStreak = 50)

        harness.engine.merge(file, RuleConfig())

        assertEquals(50, harness.profileDao.get()!!.longestStreak)
    }

    @Test
    fun aggregates_neverTrustedFromFile_alwaysRecomputed() = runTest {
        val harness = newEngine(profile = PlayerProfile())
        // No sessions/achievements at all -> recompute must yield 0 XP, ignoring the file's
        // deliberately implausible totalXp/currentStreak (see baseFile's defaults).
        val file = baseFile()

        harness.engine.merge(file, RuleConfig())

        val profile = harness.profileDao.get()!!
        assertEquals(0, profile.totalXp)
        assertTrue(profile.currentStreak < 999)
    }

    @Test
    fun playtimeBackfilled_orMerged_neverUnset() = runTest {
        val harness = newEngine(profile = PlayerProfile(playtimeBackfilled = true))
        val file = baseFile(playtimeBackfilled = false)

        harness.engine.merge(file, RuleConfig())

        assertTrue(harness.profileDao.get()!!.playtimeBackfilled)
    }

    @Test
    fun game_missingLocally_insertedAsSkeleton() = runTest {
        val harness = newEngine()
        val file = baseFile(
            games = listOf(BackupGame(appId = 7L, name = "New Game", isGoal = true, backfillMinutes = 30)),
        )

        harness.engine.merge(file, RuleConfig())

        val created = harness.gameDao.getById(7L)
        assertEquals("New Game", created?.name)
        assertEquals(true, created?.isGoal)
        assertEquals(30, created?.backfillMinutes)
    }

    @Test
    fun game_existingLocally_onlyGoalAndBackfillFieldsChange() = runTest {
        val existing = testGame(1L).copy(
            name = "Existing Name", iconUrl = "icon.png", playtimeForever = 500,
        )
        val harness = newEngine(games = mutableMapOf(1L to existing))
        val file = baseFile(
            games = listOf(BackupGame(appId = 1L, name = "Imported Name", isGoal = true, backfillMinutes = 20)),
        )

        harness.engine.merge(file, RuleConfig())

        val merged = harness.gameDao.getById(1L)!!
        assertEquals(true, merged.isGoal)
        assertEquals(20, merged.backfillMinutes)
        // Live Steam-fetched fields must survive the merge untouched — only isGoal/backfillMinutes
        // are natural-key-upserted (design.md decision 2's table).
        assertEquals("Existing Name", merged.name)
        assertEquals("icon.png", merged.iconUrl)
        assertEquals(500, merged.playtimeForever)
    }
}

private fun RuleConfig.toBackupForTest() = BackupRuleConfig(
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

private fun testGame(appId: Long) = Game(
    appId = appId,
    name = "Game $appId",
    iconUrl = "",
    playtimeForever = 0,
    playtime2Weeks = 0,
    lastPlaytime = 0,
)

private class FixedTimeProvider(private val today: LocalDate, private val millis: Long) : TimeProvider {
    override fun nowMillis(): Long = millis
    override fun zone(): ZoneId = ZoneId.of("UTC")
    override fun today(): LocalDate = today
}

private class FakeGameDao(private val store: MutableMap<Long, Game>) : GameDao {
    override suspend fun upsertAll(games: List<Game>) {
        games.forEach { store[it.appId] = it }
    }

    override suspend fun upsert(game: Game) {
        store[game.appId] = game
    }

    override fun observeLibrary(): Flow<List<Game>> = flowOf(store.values.toList())
    override fun observeGoalGames(): Flow<List<Game>> = flowOf(emptyList())
    override fun observeBacklog(): Flow<List<Game>> = flowOf(emptyList())
    override suspend fun goalAppIds(): List<Long> = store.values.filter { it.isGoal }.map { it.appId }
    override suspend fun getAll(): List<Game> = store.values.toList()
    override suspend fun getById(appId: Long): Game? = store[appId]
    override suspend fun setGoal(appId: Long, isGoal: Boolean, targetMinutes: Int?) {
        store[appId]?.let { store[appId] = it.copy(isGoal = isGoal, targetMinutes = targetMinutes) }
    }

    override suspend fun setGoalFlag(appId: Long, isGoal: Boolean) {
        store[appId]?.let { store[appId] = it.copy(isGoal = isGoal) }
    }

    override suspend fun count(): Int = store.size
    override suspend fun setBackfillMinutes(appId: Long, minutes: Int) {
        store[appId]?.let { store[appId] = it.copy(backfillMinutes = minutes) }
    }
}

private class FakeSessionDao(private val store: MutableList<Session>) : SessionDao {
    private var nextId = (store.maxOfOrNull { it.id } ?: 0L) + 1

    override suspend fun insert(session: Session): Long {
        val withId = session.copy(id = nextId++)
        store += withId
        return withId.id
    }

    override suspend fun update(session: Session) {
        val index = store.indexOfFirst { it.id == session.id }
        if (index >= 0) store[index] = session
    }

    override suspend fun getOpenSession(appId: Long): Session? =
        store.firstOrNull { it.appId == appId && it.open }

    override fun observeSince(cutoff: Long): Flow<List<Session>> =
        flowOf(store.filter { it.startAt >= cutoff })
    override suspend fun getAll(): List<Session> = store.sortedBy { it.startAt }
    override suspend fun findByNaturalKey(appId: Long, startAt: Long, endAt: Long?): Session? =
        store.firstOrNull { it.appId == appId && it.startAt == startAt && it.endAt == endAt }

    override suspend fun trackedMinutesByGame(): List<GameTrackedMinutes> =
        store.groupBy { it.appId }.map { (appId, s) -> GameTrackedMinutes(appId, s.sumOf { it.minutes }) }

    override fun observeTrackedMinutesByGame(): Flow<List<GameTrackedMinutes>> = flowOf(emptyList())
}

private class FakeDailyProgressDao(private val store: MutableMap<String, DailyProgress>) : DailyProgressDao {
    override suspend fun upsert(day: DailyProgress) {
        store[day.date] = day
    }

    override suspend fun getByDate(date: String): DailyProgress? = store[date]
    override fun observeAll(): Flow<List<DailyProgress>> = flowOf(store.values.toList())
    override suspend fun getAllOrdered(): List<DailyProgress> = store.values.sortedBy { it.date }
}

private class FakeHltbDataDao(private val store: MutableMap<Long, HltbData>) : HltbDataDao {
    override suspend fun upsert(data: HltbData) {
        store[data.appId] = data
    }

    override suspend fun getByAppId(appId: Long): HltbData? = store[appId]
    override fun observeAll(): Flow<List<HltbData>> = flowOf(store.values.toList())
    override suspend fun getAll(): List<HltbData> = store.values.toList()
    override fun observeNeedsReview(): Flow<List<HltbData>> = flowOf(emptyList())
    override suspend fun appIdsStaleOrMissing(cutoff: Long): List<Long> = emptyList()
}

private class FakeAchievementDao(private val store: MutableList<Achievement>) : AchievementDao {
    override suspend fun upsertAll(achievements: List<Achievement>) {
        achievements.forEach { incoming ->
            val index = store.indexOfFirst { it.appId == incoming.appId && it.apiName == incoming.apiName }
            if (index >= 0) store[index] = incoming else store += incoming
        }
    }

    override fun observeForGame(appId: Long): Flow<List<Achievement>> = flowOf(emptyList())
    override suspend fun getForGame(appId: Long): List<Achievement> =
        store.filter { it.appId == appId }

    override suspend fun getOne(appId: Long, apiName: String): Achievement? =
        store.firstOrNull { it.appId == appId && it.apiName == apiName }

    override fun observeCounts(): Flow<List<AchievementCounts>> = flowOf(emptyList())
    override suspend fun fetchedAtByApp(): List<AchievementFetchedAt> = emptyList()
    override suspend fun deleteMarker(appId: Long) = Unit
    override suspend fun getAllUnlocked(): List<Achievement> = store.filter { it.unlocked }
    override fun observeUnlockedRarity(): Flow<List<AchievementRarity>> = flowOf(
        store.filter { it.unlocked }.map { AchievementRarity(it.appId, it.snapshotPercent) },
    )
    override fun observeUnlockedSince(cutoff: Long): Flow<List<AchievementUnlock>> = flowOf(
        store.filter { it.unlocked && (it.unlockedAt ?: 0L) >= cutoff }
            .map { AchievementUnlock(it.appId, it.iconUrl, it.unlockedAt ?: 0L) },
    )
}

private class FakePlayerProfileDao(initial: PlayerProfile?) : PlayerProfileDao {
    private var profile = initial

    override suspend fun upsert(profile: PlayerProfile) {
        this.profile = profile
    }

    override fun observe(): Flow<PlayerProfile?> = flowOf(profile)
    override suspend fun get(): PlayerProfile? = profile
}
