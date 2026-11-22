package com.example.backlogium.data.repo

import com.example.backlogium.data.local.entity.PlayerProfile
import com.example.backlogium.data.setup.SetupOwner
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryImportReadinessTest {
    private val owner = SetupOwner("fixture-account", 3)

    @Test fun legacySyncTimestampIsUnconfirmedAndConfirmedEmptyIsReady() {
        assertEquals(HistoryImportReadiness.NEEDS_SYNC, historyImportReadinessFor(null, owner, false))
        val profile = PlayerProfile(steamId = owner.steamId, lastSyncAt = 1L, lastSyncError = "Private library")
        assertEquals(HistoryImportReadiness.NEEDS_SYNC, historyImportReadinessFor(profile, owner, false))
        assertEquals(HistoryImportReadiness.READY, historyImportReadinessFor(profile, owner, true))
    }

    @Test fun completionAndRecoverableConsentAreAccountScoped() {
        val profile = PlayerProfile(steamId = owner.steamId)
        val consented = profile.copy(historyImportRequestId = "request", historyImportGeneration = owner.generation)
        val imported = profile.copy(playtimeBackfilled = true)
        assertEquals(HistoryImportReadiness.IMPORTED, historyImportReadinessFor(imported, owner, false))
        assertEquals(HistoryImportReadiness.PENDING, historyImportReadinessFor(consented, owner, true))
        assertEquals(HistoryImportReadiness.PENDING, historyImportReadinessFor(
            imported.copy(pendingImportRecompute = true), owner, false,
        ))
        assertEquals(HistoryImportReadiness.NEEDS_SYNC, historyImportReadinessFor(
            consented, owner.copy(generation = 4), false,
        ))
        assertEquals(HistoryImportReadiness.NEEDS_SYNC, historyImportReadinessFor(imported, null, true))
    }
}
