package com.example.backlogium.domain

import com.example.backlogium.data.local.dao.DailyProgressDao
import com.example.backlogium.data.local.dao.PlayerProfileDao
import com.example.backlogium.data.local.entity.PlayerProfile
import com.example.backlogium.data.repo.ProgressEventRepository
import com.example.backlogium.gamification.QuestResult
import com.example.backlogium.gamification.XpState
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow

/**
 * Shared scaffolding for the progress-event pipeline tests.
 *
 * The interesting failures in this pipeline are *interleavings*, not values: a recovery pass landing
 * between a `persist()`'s write-ahead record and its Room write, two persists overlapping, a
 * consumer deriving events from a knowingly half-committed pair. Reproducing those needs the ability
 * to suspend a persist at a named phase and run something else while it is stopped there — which is
 * what [GatedProgressMarksStore] and [GatedPlayerProfileDao] provide.
 */

/**
 * A [ProgressMarksStore] that runs [onUpdate] after each atomic update has been applied, with the
 * values from either side of it. Suspending inside the hook stops the caller at that exact phase
 * with the write already durable — the shape of a process that is about to die, or of one that is
 * simply slow.
 */
internal class GatedProgressMarksStore(
    initial: ProgressMarks = ProgressMarks(),
    private val onUpdate: suspend (before: ProgressMarks, after: ProgressMarks) -> Unit = { _, _ -> },
) : ProgressMarksStore {
    private val delegate = InMemoryProgressMarksStore(initial)

    override val marks: Flow<ProgressMarks> = delegate.marks

    override suspend fun read(): ProgressMarks = delegate.read()

    override suspend fun write(marks: ProgressMarks) = delegate.write(marks)

    override suspend fun update(transform: (ProgressMarks) -> ProgressMarks): ProgressMarks {
        val before = delegate.read()
        val after = delegate.update(transform)
        onUpdate(before, after)
        return after
    }
}

/** A profile store that runs [onAfterUpsert] once the Room-side write is visible. */
internal class GatedPlayerProfileDao(
    private val delegate: FakePlayerProfileDao,
    private val onAfterUpsert: suspend () -> Unit = {},
) : PlayerProfileDao {
    override suspend fun upsert(profile: PlayerProfile) {
        delegate.upsert(profile)
        onAfterUpsert()
    }

    override suspend fun insertIfMissing() = delegate.insertIfMissing()

    override fun observe(): Flow<PlayerProfile?> = delegate.observe()

    override suspend fun get(): PlayerProfile? = delegate.get()

    override suspend fun resetForAccountChange(steamId: String) =
        delegate.resetForAccountChange(steamId)

    override suspend fun updateSyncStatus(lastSyncAt: Long, lastSyncError: String?) =
        delegate.updateSyncStatus(lastSyncAt, lastSyncError)

    override suspend fun updateSteamIdentity(steamId: String, steamLevel: Int, personaName: String?, avatarUrl: String?) =
        delegate.updateSteamIdentity(steamId, steamLevel, personaName, avatarUrl)

    override suspend fun updateHeaderIdentity(personaName: String?, avatarUrl: String?) =
        delegate.updateHeaderIdentity(personaName, avatarUrl)

    override suspend fun updateGamification(totalXp: Int, level: Int, currentStreak: Int, longestStreak: Int, gamificationConfigVersion: Long) =
        delegate.updateGamification(totalXp, level, currentStreak, longestStreak, gamificationConfigVersion).also {
            onAfterUpsert()
        }

    override suspend fun updatePlaytimeBackfilled(playtimeBackfilled: Boolean) =
        delegate.updatePlaytimeBackfilled(playtimeBackfilled)

    override suspend fun updateLastSyncError(message: String) =
        delegate.updateLastSyncError(message)

    override suspend fun markPendingImportRecompute() = delegate.markPendingImportRecompute()

    override suspend fun raiseLongestStreak(longestStreak: Int) =
        delegate.raiseLongestStreak(longestStreak)
}

/**
 * A one-shot gate that fires the first time a persist writes its pending-transition record, leaving
 * that persist suspended inside the protocol until [release] is completed.
 */
internal class WriteAheadGate {
    val reached = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    private var fired = false

    suspend fun onUpdate(before: ProgressMarks, after: ProgressMarks) {
        val isWriteAhead = before.pendingTransition == null && after.pendingTransition != null
        if (fired || !isWriteAhead) return
        fired = true
        reached.complete(Unit)
        release.await()
    }
}

/** A [GamificationUpdater] wired to the given store/coordinator, with everything else empty. */
internal fun testUpdater(
    marksStore: ProgressMarksStore,
    coordinator: ProgressTransitionCoordinator,
    profileDao: PlayerProfileDao,
    dailyProgressDao: DailyProgressDao = FakeDailyProgressDao(emptyList()),
): GamificationUpdater = GamificationUpdater(
    sessionDao = FakeSessionDao(emptyList()),
    dailyProgressDao = dailyProgressDao,
    playerProfileDao = profileDao,
    hltbDataDao = FakeHltbDataDao(),
    achievementDao = FakeAchievementDao(emptyList()),
    gameDao = FakeGameDao(emptyList()),
    hiddenGameDao = FakeHiddenGameDao(),
    progressMarksStore = marksStore,
    transitionCoordinator = coordinator,
)

/** A repository sharing [coordinator] with the updater under test, as production wiring does. */
internal fun testRepository(
    marksStore: ProgressMarksStore,
    coordinator: ProgressTransitionCoordinator,
    profileDao: PlayerProfileDao,
    dailyProgressDao: DailyProgressDao = FakeDailyProgressDao(emptyList()),
): ProgressEventRepository = ProgressEventRepository(
    marksStore = marksStore,
    profileDao = profileDao,
    dailyProgressDao = dailyProgressDao,
    transitionCoordinator = coordinator,
)

/**
 * A [GamificationResult] stating the derived values directly, so a test can name the transition it
 * wants to persist without going through a full [GamificationUpdater.compute].
 */
internal fun progressResult(
    date: LocalDate,
    level: Int,
    streak: Int = 0,
    questMet: Boolean = false,
    changedDays: List<QuestStatusUpdate> = emptyList(),
): GamificationResult = GamificationResult(
    xpState = XpState(totalXp = 0, level = level, xpIntoLevel = 0, xpForNext = 100),
    questResults = listOf(QuestResult(date, questMet)),
    currentStreak = streak,
    longestStreak = streak,
    changedDays = changedDays,
    evaluationDate = date,
)
