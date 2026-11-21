package com.example.backlogium.data.repo

import com.example.backlogium.data.local.entity.PlayerProfile
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryImportReadinessTest {
    private val credentials = CredentialsState.Configured("fixture-key", "76561198000000001")

    @Test fun aSuccessfulSnapshotRatherThanLibrarySizeAuthorizesImport() {
        assertEquals(HistoryImportReadiness.NEEDS_SYNC, historyImportReadinessFor(null, credentials))
        val profile = PlayerProfile(steamId = credentials.steamId)
        assertEquals(HistoryImportReadiness.NEEDS_SYNC, historyImportReadinessFor(profile, credentials))
        assertEquals(HistoryImportReadiness.NEEDS_SYNC, historyImportReadinessFor(
            profile.copy(lastSyncError = "Private library"), credentials))
        assertEquals(HistoryImportReadiness.READY, historyImportReadinessFor(
            profile.copy(lastSyncAt = 1L), credentials))
    }

    @Test fun completionIsAccountScopedAndPendingDerivedWorkRemainsRecoverable() {
        val imported = PlayerProfile(steamId = credentials.steamId, lastSyncAt = 1L, playtimeBackfilled = true)
        assertEquals(HistoryImportReadiness.IMPORTED, historyImportReadinessFor(imported, credentials))
        assertEquals(HistoryImportReadiness.READY, historyImportReadinessFor(
            imported.copy(pendingImportRecompute = true), credentials))
        assertEquals(HistoryImportReadiness.NEEDS_SYNC, historyImportReadinessFor(
            imported.copy(steamId = "76561198000000002"), credentials))
        assertEquals(HistoryImportReadiness.NEEDS_SYNC, historyImportReadinessFor(imported, CredentialsState.Unconfigured))
    }
}
