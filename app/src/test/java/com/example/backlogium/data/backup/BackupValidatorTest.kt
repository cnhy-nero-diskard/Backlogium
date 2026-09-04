package com.example.backlogium.data.backup

import com.example.backlogium.domain.SetSharedGamePlaytimeUseCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers every category the preflight validator checks (tasks.md 2.2/2.6) — the merge itself is
 * never invoked here, so a rejection provably touches no database: [BackupValidator.validate] has
 * no database access at all (design.md decision 1).
 */
class BackupValidatorTest {

    @Test
    fun validFile_passesWithNoProblems() {
        val result = BackupValidator.validate(validFile())
        assertTrue(result is BackupValidationResult.Valid)
    }

    @Test
    fun malformedAppId_rejected() {
        val file = validFile(games = listOf(BackupGame(appId = 0L, name = "Bad", isGoal = false, backfillMinutes = 0)))
        val problems = file.assertRejected()
        assertTrue(problems.single().recordType == "game")
    }

    @Test
    fun unparseableSessionStart_rejected() {
        val file = validFile(
            sessions = listOf(BackupSession(appId = 1L, startAt = "not-a-date", endAt = null, minutes = 5)),
        )
        val problems = file.assertRejected()
        assertEquals("session", problems.single().recordType)
    }

    @Test
    fun negativeSessionStart_rejected() {
        val file = validFile(
            sessions = listOf(
                BackupSession(appId = 1L, startAt = (-1_000L).toIso8601(), endAt = null, minutes = 5),
            ),
        )
        file.assertRejected()
    }

    @Test
    fun endBeforeStart_rejected() {
        // Both timestamps are in the supported range, so the ordering rule is what rejects this
        // rather than the plausibility bound.
        val file = validFile(
            sessions = listOf(
                BackupSession(
                    appId = 1L,
                    startAt = "2026-06-01T11:00:00Z",
                    endAt = "2026-06-01T10:00:00Z",
                    minutes = 5,
                ),
            ),
        )
        val problems = file.assertRejected()
        assertEquals("endAt precedes startAt", problems.single().detail)
    }

    @Test
    fun wellFormedSession_accepted() {
        val file = validFile(
            sessions = listOf(
                BackupSession(
                    appId = 1L,
                    startAt = "2026-06-01T10:00:00Z",
                    endAt = "2026-06-01T11:00:00Z",
                    minutes = 60,
                ),
            ),
        )
        assertTrue(BackupValidator.validate(file) is BackupValidationResult.Valid)
    }

    @Test
    fun unparseableDailyProgressDate_rejected() {
        val file = validFile(
            dailyProgress = listOf(BackupDailyProgress("not-a-date", 10, 5, false)),
        )
        val problems = file.assertRejected()
        assertEquals("dailyProgress", problems.single().recordType)
    }

    @Test
    fun parseableButAbsurdlyEarlyDailyProgressDate_rejected() {
        // Parses fine, but would make GamificationUpdater.compute() expand a calendar spanning
        // ~740,000 days — and the pending-recompute recovery would retry it every launch.
        val file = validFile(dailyProgress = listOf(BackupDailyProgress("0001-01-01", 10, 5, false)))
        val problems = file.assertRejected()
        assertEquals("dailyProgress", problems.single().recordType)
        assertTrue(problems.single().detail.contains("outside the supported range"))
    }

    @Test
    fun parseableButFarFutureDailyProgressDate_rejected() {
        val file = validFile(dailyProgress = listOf(BackupDailyProgress("9999-12-31", 10, 5, false)))
        file.assertRejected()
    }

    @Test
    fun dateWithinSupportedRange_accepted() {
        val file = validFile(dailyProgress = listOf(BackupDailyProgress("2026-06-01", 10, 5, false)))
        assertTrue(BackupValidator.validate(file) is BackupValidationResult.Valid)
    }

    @Test
    fun parseableButAbsurdSessionStart_rejected() {
        val file = validFile(
            sessions = listOf(
                BackupSession(appId = 1L, startAt = "0001-01-01T00:00:00Z", endAt = null, minutes = 5),
            ),
        )
        val problems = file.assertRejected()
        assertEquals("session", problems.single().recordType)
    }

    @Test
    fun achievementReferencingAbsentGame_rejected() {
        val file = validFile(
            achievements = listOf(
                BackupAchievement(appId = 999L, apiName = "ACH", displayName = null, snapshotPercent = 5.0, unlockedAt = VALID_UNLOCKED_AT),
            ),
        )
        val problems = file.assertRejected()
        assertEquals("achievement", problems.single().recordType)
    }

    @Test
    fun impossibleAchievementUnlockTimestamp_rejectedBeforeMerge() {
        // Parses fine, but BackupMergeEngine.importedWins() would read 1970 as "earlier" than any
        // real local unlock and permanently overwrite a protected rarity snapshot — which ordinary
        // syncs never repair, since they deliberately do not refresh a frozen snapshot.
        val file = validFile(
            achievements = listOf(
                BackupAchievement(
                    appId = 1L, apiName = "ACH", displayName = null,
                    snapshotPercent = 5.0, unlockedAt = 0L.toIso8601(),
                ),
            ),
        )
        val problems = file.assertRejected()
        assertEquals("achievement", problems.single().recordType)
        assertTrue(problems.single().detail.contains("outside the supported range"))
    }

    @Test
    fun achievementUnlockTimestampWithinRange_accepted() {
        val file = validFile(
            achievements = listOf(
                BackupAchievement(
                    appId = 1L, apiName = "ACH", displayName = null,
                    snapshotPercent = 5.0, unlockedAt = "2026-06-01T12:00:00Z",
                ),
            ),
        )
        assertTrue(BackupValidator.validate(file) is BackupValidationResult.Valid)
    }

    @Test
    fun sessionEndingFarInTheFuture_rejectedEvenWhenStartIsValid() {
        val file = validFile(
            sessions = listOf(
                BackupSession(
                    appId = 1L,
                    startAt = "2026-06-01T10:00:00Z",
                    endAt = "9999-01-01T00:00:00Z",
                    minutes = 60,
                ),
            ),
        )
        val problems = file.assertRejected()
        assertEquals("session", problems.single().recordType)
        assertTrue(problems.single().detail.contains("endAt"))
    }

    @Test
    fun snapshotPercentOutOfRange_rejected() {
        val file = validFile(
            achievements = listOf(
                BackupAchievement(appId = 1L, apiName = "ACH", displayName = null, snapshotPercent = 150.0, unlockedAt = VALID_UNLOCKED_AT),
            ),
        )
        file.assertRejected()
    }

    @Test
    fun collectionMemberReferencingAbsentCollection_rejected() {
        val file = validFile(
            collectionMembers = listOf(BackupCollectionMember(collectionId = 999L, appId = 1L, orderIndex = 0)),
        )
        val problems = file.assertRejected()
        assertEquals("collectionMember", problems.single().recordType)
    }

    @Test
    fun duplicateNaturalKeyWithinCollection_rejected() {
        val file = validFile(
            collections = listOf(
                BackupCollection(id = 1L, name = "Queue", mode = "ORDERED_QUEUE", sort = "MANUAL_SEQUENCE", targetDate = null, createdAt = 1L),
            ),
            collectionMembers = listOf(
                BackupCollectionMember(collectionId = 1L, appId = 10L, orderIndex = 0),
                BackupCollectionMember(collectionId = 1L, appId = 10L, orderIndex = 1),
            ),
        )
        val problems = file.assertRejected()
        assertEquals("collectionMember", problems.single().recordType)
    }

    @Test
    fun negativeManualSharedMinutes_rejected() {
        val file = validFile(
            games = listOf(BackupGame(appId = 1L, name = "Game", isGoal = false, backfillMinutes = 0, manualSharedMinutes = -600)),
        )
        val problems = file.assertRejected()
        assertEquals("game", problems.single().recordType)
        assertTrue(problems.single().detail.contains("manualSharedMinutes"))
    }

    @Test
    fun oversizedManualSharedMinutes_rejected() {
        val file = validFile(
            games = listOf(
                BackupGame(
                    appId = 1L, name = "Game", isGoal = false, backfillMinutes = 0,
                    manualSharedMinutes = SetSharedGamePlaytimeUseCase.MAX_MANUAL_SHARED_MINUTES + 1,
                ),
            ),
        )
        val problems = file.assertRejected()
        assertEquals("game", problems.single().recordType)
        assertTrue(problems.single().detail.contains("manualSharedMinutes"))
    }

    @Test
    fun ownedSourceWithNonzeroManualSharedMinutes_rejected() {
        val file = validFile(
            games = listOf(
                BackupGame(
                    appId = 1L, name = "Game", isGoal = false, backfillMinutes = 0,
                    source = "STEAM_OWNED", manualSharedMinutes = 90,
                ),
            ),
        )
        val problems = file.assertRejected()
        assertEquals("game", problems.single().recordType)
        assertTrue(problems.single().detail.contains("manualSharedMinutes"))
    }

    @Test
    fun ownedSourceWithZeroOrAbsentManualSharedMinutes_accepted() {
        listOf(null, 0).forEach { manual ->
            val file = validFile(
                games = listOf(
                    BackupGame(
                        appId = 1L, name = "Game", isGoal = false, backfillMinutes = 0,
                        source = "STEAM_OWNED", manualSharedMinutes = manual,
                    ),
                ),
            )
            assertTrue(BackupValidator.validate(file) is BackupValidationResult.Valid)
        }
    }

    @Test
    fun legacyNullSourceWithNonzeroManualSharedMinutes_accepted() {
        // A null source predates the field and carries no consistency claim; the merge
        // normalizes whatever it resolves to owned down to 0.
        val file = validFile(
            games = listOf(
                BackupGame(
                    appId = 1L, name = "Game", isGoal = false, backfillMinutes = 0,
                    source = null, manualSharedMinutes = 90,
                ),
            ),
        )
        assertTrue(BackupValidator.validate(file) is BackupValidationResult.Valid)
    }

    @Test
    fun manualSharedMinutesBounds_accepted() {
        listOf(null, 0, 90, SetSharedGamePlaytimeUseCase.MAX_MANUAL_SHARED_MINUTES).forEach { manual ->
            val file = validFile(
                games = listOf(
                    BackupGame(appId = 1L, name = "Game", isGoal = false, backfillMinutes = 0, manualSharedMinutes = manual),
                ),
            )
            assertTrue(BackupValidator.validate(file) is BackupValidationResult.Valid)
        }
    }

    @Test
    fun multipleProblems_allReported() {
        val file = validFile(
            games = listOf(BackupGame(appId = -1L, name = "Bad", isGoal = false, backfillMinutes = 0)),
            dailyProgress = listOf(BackupDailyProgress("garbage", 0, 0, false)),
        )
        val problems = file.assertRejected()
        assertEquals(2, problems.size)
    }

    private fun BackupFile.assertRejected(): List<BackupValidationProblem> {
        val result = BackupValidator.validate(this)
        assertTrue(result is BackupValidationResult.Invalid)
        return (result as BackupValidationResult.Invalid).problems
    }

    private fun validFile(
        games: List<BackupGame> = emptyList(),
        sessions: List<BackupSession> = emptyList(),
        dailyProgress: List<BackupDailyProgress> = emptyList(),
        achievements: List<BackupAchievement> = emptyList(),
        collections: List<BackupCollection> = emptyList(),
        collectionMembers: List<BackupCollectionMember> = emptyList(),
    ) = BackupFile(
        exportedAt = "2026-07-01T00:00:00Z",
        identity = BackupIdentity(steamId64 = "1"),
        ruleConfig = BackupRuleConfig(
            xpPerMinute = 1,
            levelBase = 100,
            questThresholdMin = 30,
            questMode = "ANY_GAME",
            streakGraceDays = 0,
            commonAchievementXp = 5,
            uncommonAchievementXp = 10,
            rareAchievementXp = 20,
            epicAchievementXp = 40,
            legendaryAchievementXp = 80,
        ),
        games = games.ifEmpty { listOf(BackupGame(appId = 1L, name = "Game", isGoal = false, backfillMinutes = 0)) },
        achievements = achievements,
        sessions = sessions,
        dailyProgress = dailyProgress,
        hltbData = emptyList(),
        librarySortPrefs = BackupLibrarySortPrefs(focus = "NAME", library = "PLAYTIME"),
        playerProfile = BackupPlayerProfile(
            totalXp = 0, level = 1, currentStreak = 0, longestStreak = 0, playtimeBackfilled = false,
        ),
        computed = BackupComputed(emptyList(), emptyList()),
        collections = collections,
        collectionMembers = collectionMembers,
    )

    private companion object {
        /**
         * An in-range unlock time for tests whose subject is something *other* than the timestamp
         * bound, so they keep asserting exactly one problem.
         */
        const val VALID_UNLOCKED_AT = "2026-06-01T12:00:00Z"
    }
}
