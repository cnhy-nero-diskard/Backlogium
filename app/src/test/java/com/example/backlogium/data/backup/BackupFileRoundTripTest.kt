package com.example.backlogium.data.backup

import com.example.backlogium.domain.LibrarySortDirection
import com.example.backlogium.domain.LibrarySortKey
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the export/import round trip at the serialization boundary (tasks.md 7.2): a populated
 * [BackupFile] must decode back to an identical value under the app's configured [Json]. Merge
 * fidelity — that the decoded value then lands correctly in the database — is covered exhaustively
 * by [BackupMergeEngineTest].
 */
class BackupFileRoundTripTest {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @Test
    fun populatedBackupFile_survivesEncodeDecodeUnchanged() {
        val original = BackupFile(
            exportedAt = "2026-07-01T00:00:00Z",
            identity = BackupIdentity(steamId64 = "76561198000000000"),
            ruleConfig = BackupRuleConfig(
                xpPerMinute = 1, levelBase = 100, questThresholdMin = 30, questMode = "ANY_GAME",
                streakGraceDays = 2, commonAchievementXp = 5, uncommonAchievementXp = 10,
                rareAchievementXp = 20, epicAchievementXp = 40, legendaryAchievementXp = 80,
            ),
            games = listOf(BackupGame(appId = 440L, name = "Team Fortress 2", isGoal = true, backfillMinutes = 120)),
            achievements = listOf(
                BackupAchievement(
                    appId = 440L, apiName = "ACH_WIN", displayName = "Win a match",
                    snapshotPercent = 13.75, unlockedAt = "2026-06-01T12:00:00Z",
                ),
            ),
            sessions = listOf(
                BackupSession(appId = 440L, startAt = "2026-06-01T10:00:00Z", endAt = "2026-06-01T11:30:00Z", minutes = 90),
            ),
            dailyProgress = listOf(BackupDailyProgress(date = "2026-06-01", minutesPlayed = 90, goalMinutesPlayed = 90, questMet = true)),
            hltbData = listOf(
                BackupHltbData(
                    appId = 440L, hltbId = 1L, mainStoryMinutes = 600, mainExtraMinutes = 900,
                    completionistMinutes = 1_200, allStylesMinutes = 900, matchStatus = "MATCHED",
                ),
            ),
            librarySortPrefs = BackupLibrarySortPrefs(focus = "NAME", library = "PLAYTIME"),
            playerProfile = BackupPlayerProfile(
                totalXp = 500, level = 3, currentStreak = 4, longestStreak = 10, playtimeBackfilled = true,
            ),
            computed = BackupComputed(
                xpPerGame = listOf(BackupGameXp(appId = 440L, name = "Team Fortress 2", xp = 500)),
                xpTimeline = listOf(BackupDayXp(date = "2026-06-01", cumulativeXp = 500)),
            ),
            collections = listOf(
                BackupCollection(
                    id = 1L, name = "Queue", mode = "ORDERED_QUEUE", sort = "MANUAL_SEQUENCE",
                    targetDate = null, createdAt = 5L, accent = "AMBER", timeBasis = "COMPLETIONIST",
                    description = "Play in sequence", displayOrder = 0,
                ),
            ),
            collectionMembers = listOf(BackupCollectionMember(collectionId = 1L, appId = 440L, orderIndex = 0, done = false)),
        )

        val encoded = json.encodeToString(BackupFile.serializer(), original)
        val decoded = json.decodeFromString(BackupFile.serializer(), encoded)

        assertEquals(original, decoded)
    }

    /**
     * An export written before sort directions existed carries no direction fields at all. It must
     * still decode, and each list must resolve to its *key's* default direction — the fixed
     * direction that older file actually meant — rather than to a global default that would
     * reverse one of the two lists on restore.
     */
    @Test
    fun sortBlockWithoutDirections_resolvesToEachKeysDefault() {
        val legacy = """{"focus":"NAME","library":"PLAYTIME"}"""

        val decoded = json.decodeFromString(BackupLibrarySortPrefs.serializer(), legacy)
        assertEquals(null, decoded.focusDirection)
        assertEquals(null, decoded.libraryDirection)

        val prefs = decoded.toDomain()
        assertEquals(LibrarySortKey.NAME, prefs.focus)
        assertEquals(LibrarySortKey.PLAYTIME, prefs.library)
        assertEquals(LibrarySortDirection.ASCENDING, prefs.focusDirection)
        assertEquals(LibrarySortDirection.DESCENDING, prefs.libraryDirection)
    }

    /** A direction a future build wrote and this one does not know is the key's default, not a crash. */
    @Test
    fun unrecognizedStoredDirection_fallsBackToTheKeysDefault() {
        val prefs = BackupLibrarySortPrefs(
            focus = "NAME",
            library = "PLAYTIME",
            focusDirection = "SIDEWAYS",
            libraryDirection = "ASCENDING",
        ).toDomain()

        assertEquals(LibrarySortDirection.ASCENDING, prefs.focusDirection)
        // A recognized value is honoured even when it is not the default.
        assertEquals(LibrarySortDirection.ASCENDING, prefs.libraryDirection)
    }
}
