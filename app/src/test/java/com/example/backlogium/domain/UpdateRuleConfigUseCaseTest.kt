package com.example.backlogium.domain

import com.example.backlogium.data.local.AutoSnapshotSettings
import com.example.backlogium.data.local.LiveSessionState
import com.example.backlogium.data.local.entity.DailyProgress
import com.example.backlogium.data.local.entity.PlayerProfile
import com.example.backlogium.data.repo.SettingsRepository
import com.example.backlogium.gamification.QuestMode
import com.example.backlogium.gamification.RuleConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * Covers the two halves of a rule change: [UpdateRuleConfigUseCase.preview] must be able to
 * state the concrete consequence without causing it, and [UpdateRuleConfigUseCase.apply] must
 * leave stored config and derived progress consistent with each other in one operation.
 */
class UpdateRuleConfigUseCaseTest {

    private val today = LocalDate.parse("2026-07-17")

    @Test
    fun apply_persistsConfigAndRecomputesUnderIt() = runTest {
        // A 4-day run at 40 min/day: met under the default 30-min goal, unmet under a 60-min one.
        val fixture = fixture(daysAt40Minutes())
        fixture.useCase.apply(RuleConfig(questThresholdMin = 60))

        assertEquals(60, fixture.settings.stored.questThresholdMin)
        val profile = fixture.profileDao.get()!!
        assertEquals(0, profile.currentStreak)
        // Every stored day was re-evaluated under the new rule, not just future ones.
        assertEquals(false, fixture.dailyDao.getByDate("2026-07-17")!!.questMet)
        // XP is unaffected by a quest-rule change, but must still be consistent with the config
        // the recompute ran under: 300 tracked minutes at 1 XP/min -> level 3 on a base of 50.
        assertEquals(300, profile.totalXp)
        assertEquals(3, profile.level)
    }

    @Test
    fun apply_advancedChange_recomputesXpAndLevel() = runTest {
        // Doubling the XP rate is retroactive over the whole library: 300 min -> 600 XP, which
        // is level 4 on the default curve (xpAt(4) = 50*3*4 = 600).
        val fixture = fixture(daysAt40Minutes())
        fixture.useCase.apply(RuleConfig(xpPerMinute = 2))

        val profile = fixture.profileDao.get()!!
        assertEquals(2, fixture.settings.stored.xpPerMinute)
        assertEquals(600, profile.totalXp)
        assertEquals(4, profile.level)
    }

    @Test
    fun preview_mutatesNothing() = runTest {
        val fixture = fixture(daysAt40Minutes())
        // Seed the state a real install would have: the config and the progress it produced.
        fixture.useCase.apply(RuleConfig())
        val configBefore = fixture.settings.stored
        val profileBefore = fixture.profileDao.get()
        val daysBefore = fixture.dailyDao.getAllOrdered()
        val writesBefore = fixture.profileDao.upsertCount to fixture.dailyDao.upsertCount

        fixture.useCase.preview(RuleConfig(questThresholdMin = 60, questMode = QuestMode.GOAL_ONLY))

        assertEquals(configBefore, fixture.settings.stored)
        assertEquals(profileBefore, fixture.profileDao.get())
        assertEquals(daysBefore, fixture.dailyDao.getAllOrdered())
        assertEquals(writesBefore, fixture.profileDao.upsertCount to fixture.dailyDao.upsertCount)
    }

    @Test
    fun preview_reportsProtectedLongestStreak_notTheRawDrop() = runTest {
        val fixture = fixture(daysAt40Minutes())
        fixture.useCase.apply(RuleConfig())
        assertEquals(4, fixture.profileDao.get()!!.longestStreak)

        // Under a 60-min goal none of those days qualify, so the raw per-day computation yields
        // a longest streak of 0. The preview must report 4 — the value `persist` will actually
        // write — rather than warning about a record loss that is not going to happen.
        val result = fixture.useCase.preview(RuleConfig(questThresholdMin = 60))

        assertEquals(0, result.currentStreak)
        assertEquals(4, result.longestStreak)
    }

    @Test
    fun preview_figuresMatchWhatApplyWrites() = runTest {
        val fixture = fixture(daysAt40Minutes())
        fixture.useCase.apply(RuleConfig())

        val candidate = RuleConfig(questThresholdMin = 60, xpPerMinute = 2)
        val previewed = fixture.useCase.preview(candidate)
        fixture.useCase.apply(candidate)

        val profile = fixture.profileDao.get()!!
        assertEquals(previewed.xpState.totalXp, profile.totalXp)
        assertEquals(previewed.xpState.level, profile.level)
        assertEquals(previewed.currentStreak, profile.currentStreak)
        assertEquals(previewed.longestStreak, profile.longestStreak)
    }

    // --- Fixture -------------------------------------------------------------

    private fun daysAt40Minutes() = listOf(
        DailyProgress("2026-07-14", minutesPlayed = 40),
        DailyProgress("2026-07-15", minutesPlayed = 40),
        DailyProgress("2026-07-16", minutesPlayed = 40),
        DailyProgress("2026-07-17", minutesPlayed = 40),
    )

    private class Fixture(
        val useCase: UpdateRuleConfigUseCase,
        val settings: FakeSettingsRepository,
        val profileDao: FakePlayerProfileDao,
        val dailyDao: FakeDailyProgressDao,
    )

    private fun fixture(days: List<DailyProgress>): Fixture {
        val settings = FakeSettingsRepository()
        val profileDao = FakePlayerProfileDao(PlayerProfile())
        val dailyDao = FakeDailyProgressDao(days)
        val updater = GamificationUpdater(
            FakeSessionDao(listOf(testSession(minutes = 300))),
            dailyDao,
            profileDao,
            FakeHltbDataDao(),
            FakeAchievementDao(emptyList()),
            FakeGameDao(listOf(testGame(appId = 1L, backfillMinutes = 0))),
        )
        return Fixture(
            useCase = UpdateRuleConfigUseCase(settings, updater, FixedTimeProvider(today)),
            settings = settings,
            profileDao = profileDao,
            dailyDao = dailyDao,
        )
    }

    /** In-memory stand-in for the DataStore-backed implementation. */
    private class FakeSettingsRepository : SettingsRepository {
        private val state = MutableStateFlow(RuleConfig())
        val stored: RuleConfig get() = state.value
        override val ruleConfig: Flow<RuleConfig> = state
        override suspend fun setRuleConfig(config: RuleConfig) {
            state.value = config
        }

        // The Library sort selections share this store but are irrelevant to a rule change.
        private val sort = MutableStateFlow(LibrarySortPrefs())
        override val librarySort: Flow<LibrarySortPrefs> = sort
        override suspend fun setFocusSort(key: LibrarySortKey) {
            sort.value = sort.value.copy(focus = key)
        }

        override suspend fun setLibrarySort(key: LibrarySortKey) {
            sort.value = sort.value.copy(library = key)
        }

        // Auto-snapshot configuration (add-backup-restore) is irrelevant to a rule change.
        private val autoSnapshot = MutableStateFlow(AutoSnapshotSettings())
        override val autoSnapshotSettings: Flow<AutoSnapshotSettings> = autoSnapshot
        override suspend fun setAutoSnapshotEnabled(enabled: Boolean) {
            autoSnapshot.value = autoSnapshot.value.copy(enabled = enabled)
        }

        override suspend fun setSnapshotRetentionCount(count: Int) {
            autoSnapshot.value = autoSnapshot.value.copy(retentionCount = count)
        }

        override suspend fun setSnapshotIntervalHours(hours: Int) {
            autoSnapshot.value = autoSnapshot.value.copy(intervalHours = hours)
        }

        // Live now-playing session (enhance-now-playing) is irrelevant to a rule change.
        private val session = MutableStateFlow(LiveSessionState())
        override val liveSession: Flow<LiveSessionState> = session
        override suspend fun setLiveSession(appId: Long?, startedAt: Long) {
            session.value = LiveSessionState(appId, startedAt)
        }

        override val notificationPermissionRequested: Flow<Boolean> = MutableStateFlow(true)
        override suspend fun setNotificationPermissionRequested() = Unit

        private val liveMonitor = MutableStateFlow(false)
        override val liveMonitorEnabled: Flow<Boolean> = liveMonitor
        override suspend fun setLiveMonitorEnabled(enabled: Boolean) {
            liveMonitor.value = enabled
        }

        override suspend fun clearLiveSession() {
            session.value = LiveSessionState()
        }
    }

    private class FixedTimeProvider(private val date: LocalDate) : TimeProvider {
        override fun nowMillis(): Long = 0L
        override fun zone(): ZoneId = ZoneId.of("UTC")
        override fun today(): LocalDate = date
    }
}
