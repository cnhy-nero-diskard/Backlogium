package com.example.backlogium.data.backup

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
            games = listOf(
                BackupGame(
                    appId = 440L, name = "Team Fortress 2", isGoal = true, backfillMinutes = 120,
                    firstSeenAt = "2026-05-20T09:00:00Z",
                    lastPlayedAt = "2026-06-28T21:15:00Z",
                    returnedToPlayAt = "2026-06-28T21:15:00Z",
                    source = "FAMILY_SHARED",
                ),
            ),
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
            excludedSharedGames = listOf(
                BackupExcludedSharedGame(appId = 620L, name = "Portal 2", excludedAt = "2026-06-20T12:00:00Z"),
            ),
        )

        val encoded = json.encodeToString(BackupFile.serializer(), original)
        val decoded = json.decodeFromString(BackupFile.serializer(), encoded)

        assertEquals(original, decoded)
    }

    @Test
    fun gameBlockWithoutRecencyTimes_stillDecodes() {
        // A backup written before recency existed carries none of the three. Absent must decode to
        // null — "was already here" — rather than failing the whole import over an optional.
        val legacy = """{"appId":440,"name":"Team Fortress 2","isGoal":false,"backfillMinutes":0}"""

        val decoded = json.decodeFromString(BackupGame.serializer(), legacy)

        assertEquals(null, decoded.firstSeenAt)
        assertEquals(null, decoded.lastPlayedAt)
        assertEquals(null, decoded.returnedToPlayAt)
    }

    /**
     * An export written before sort directions existed carries no direction fields at all, and must
     * still decode rather than failing the whole import over an absent optional.
     *
     * That is the entire guarantee here. This block is export-only — see [BackupFile]'s doc — so
     * there is deliberately no assertion about the app adopting these values on import: nothing
     * reads them back, and a test claiming otherwise would document behaviour that does not exist.
     */
    @Test
    fun sortBlockWithoutDirections_stillDecodes() {
        val legacy = """{"focus":"NAME","library":"PLAYTIME"}"""

        val decoded = json.decodeFromString(BackupLibrarySortPrefs.serializer(), legacy)

        assertEquals("NAME", decoded.focus)
        assertEquals("PLAYTIME", decoded.library)
        assertEquals(null, decoded.focusDirection)
        assertEquals(null, decoded.libraryDirection)
    }

    /** A direction present in the file survives the round trip verbatim, since it is a record. */
    @Test
    fun sortBlockWithDirections_roundTripsVerbatim() {
        val original = BackupLibrarySortPrefs(
            focus = "NAME",
            library = "PLAYTIME",
            focusDirection = "DESCENDING",
            libraryDirection = "ASCENDING",
        )

        val encoded = json.encodeToString(BackupLibrarySortPrefs.serializer(), original)
        val decoded = json.decodeFromString(BackupLibrarySortPrefs.serializer(), encoded)

        assertEquals(original, decoded)
    }
}
