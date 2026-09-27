package com.example.backlogium.data.backup

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class BackupVersionedDecoderTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val legacy = BackupFile(
        exportedAt = "2026-07-01T00:00:00Z", identity = BackupIdentity("76561198000000000"),
        ruleConfig = BackupRuleConfig(1, 100, 30, "ANY_GAME", 2, 5, 10, 20, 40, 80),
        games = listOf(BackupGame(440, "Game", false, 0)), achievements = emptyList(),
        sessions = listOf(BackupSession(440, "2026-07-01T00:00:00Z", "2026-07-01T01:00:00Z", 60)),
        dailyProgress = emptyList(), hltbData = emptyList(),
        librarySortPrefs = BackupLibrarySortPrefs("NAME", "PLAYTIME"),
        playerProfile = BackupPlayerProfile(0, 1, 0, 0, false),
        computed = BackupComputed(emptyList(), emptyList()),
    )

    private fun decode(text: String): BackupFile? {
        val file = File.createTempFile("versioned-backup", ".json",
            RuntimeEnvironment.getApplication().cacheDir)
        return try {
            file.writeText(text)
            BackupVersionedDecoder.decode(json, file)
        } finally { file.delete() }
    }

    @Test fun pickedAndRetainedLegacySnapshotsAcceptExplicitAndOmittedV1() {
        val explicit = json.encodeToString(BackupFile.serializer(), legacy)
        assertEquals(1, decode(explicit)?.formatVersion)
        val omitted = explicit.replace("\"formatVersion\":1,", "")
        assertFalse(omitted.contains("\"formatVersion\""))
        assertEquals(1, decode(omitted)?.formatVersion)
        val context = RuntimeEnvironment.getApplication()
        val dir = File(context.noBackupFilesDir, "backup_snapshots").apply { mkdirs() }
        val snapshot = File(dir, "987654321.json")
        try {
            snapshot.writeText(omitted)
            assertEquals(legacy, SnapshotStore(context, json).read(snapshot.name))
        } finally { snapshot.delete() }
    }

    @Test fun v2RequiresExplicitVersionAndBothNonNullValidFactsOnEverySession() {
        val v2 = json.encodeToString(BackupFile.serializer(), legacy.copy(
            formatVersion = 2,
            sessions = legacy.sessions.map { it.copy(cloudContribution = BackupCloudContribution(
                BackupContributionState.UNKNOWN, BackupContributionState.FULL)) },
        ))
        assertNotNull(decode(v2))
        val absentObject = v2.replace(Regex(",?\"cloudContribution\":\\{[^}]*}"), "")
        assertNull(decode(absentObject))
        assertNull(decode(v2.replace("\"recoveredSharedPlay\":\"UNKNOWN\",", "")))
        assertNull(decode(v2.replace("\"timingInformedSteamPlay\":\"FULL\"", "\"timingInformedSteamPlay\":null")))
        assertNull(decode(v2.replace("\"recoveredSharedPlay\":\"UNKNOWN\"", "\"recoveredSharedPlay\":\"MAYBE\"")))
        assertNull(decode(v2.replace("\"formatVersion\":2,", "")))
        assertNull(decode(v2.replace("\"formatVersion\":2", "\"formatVersion\":1")))
        assertNull(decode(v2.replace("\"formatVersion\":2", "\"formatVersion\":3")))
        assertNull(decode(v2.replace(Regex("\"cloudContribution\":\\{[^}]*}"), "\"cloudContribution\":null")))
        val mixed = json.encodeToString(BackupFile.serializer(), legacy.copy(
            formatVersion = 2,
            sessions = listOf(
                legacy.sessions.single().copy(cloudContribution = BackupCloudContribution(
                    BackupContributionState.NONE, BackupContributionState.PARTIAL)),
                legacy.sessions.single().copy(startAt = "2026-07-02T00:00:00Z"),
            ),
        ))
        assertNull(decode(mixed))
    }
}
