package com.example.backlogium.data.steamassets

import org.junit.Assert.assertEquals
import org.junit.Test

class SteamAssetModelsTest {
    @Test
    fun runCounts_trackEachTerminalOutcomeIndependently() {
        val counts = SteamAssetRunCounts()
            .plus(SteamAssetOutcome.STORED)
            .plus(SteamAssetOutcome.ALREADY_PRESENT)
            .plus(SteamAssetOutcome.UNAVAILABLE)
            .plus(SteamAssetOutcome.FAILED)

        assertEquals(1, counts.stored)
        assertEquals(1, counts.alreadyPresent)
        assertEquals(1, counts.unavailable)
        assertEquals(1, counts.failed)
    }
}
