package com.example.backlogium.ui.settings

import com.example.backlogium.data.repo.ManualImportUnavailableAt
import com.example.backlogium.data.repo.ManualSharedGameImportResult
import com.example.backlogium.data.repo.PlayerDataProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualSharedGameImportMessageTest {
    @Test fun invalidInputExplainsAcceptedShape() {
        assertTrue(manualImportMessage(ManualSharedGameImportResult.InvalidInput).contains("Store URL"))
    }

    @Test fun ownedResultRefusesSharedImport() {
        val message = manualImportMessage(ManualSharedGameImportResult.Owned(620, "Portal 2"))
        assertTrue(message.contains("owned Steam library"))
        assertTrue(message.contains("no Family Shared import"))
    }

    @Test fun importedResultReportsReturnedAchievementsAndPlaytimeBoundary() {
        val message = manualImportMessage(
            ManualSharedGameImportResult.Imported(
                620,
                "Portal 2",
                false,
                PlayerDataProbe.Returned(total = 50, unlocked = 12),
            ),
        )
        assertTrue(message.contains("50 achievements"))
        assertTrue(message.contains("12 unlocked"))
        assertTrue(message.contains("not supplied by Steam"))
    }

    @Test fun importedResultDistinguishesNoDataFromUnavailable() {
        val noData = manualImportMessage(
            ManualSharedGameImportResult.Imported(1, "Game", false, PlayerDataProbe.NoData),
        )
        val unavailable = manualImportMessage(
            ManualSharedGameImportResult.Imported(1, "Game", true, PlayerDataProbe.Unavailable),
        )
        assertTrue(noData.contains("no usable"))
        assertTrue(unavailable.contains("temporarily unavailable"))
    }

    @Test fun unavailableResultNamesFailedSafetyCheck() {
        val message = manualImportMessage(
            ManualSharedGameImportResult.Unavailable(1, ManualImportUnavailableAt.OWNED_LIBRARY),
        )
        assertTrue(message.contains("ownership check"))
    }

    @Test fun importedGameUsesProminentFoundFeedback() {
        val feedback = manualImportFeedback(
            ManualSharedGameImportResult.Imported(
                620,
                "Portal 2",
                false,
                PlayerDataProbe.Returned(total = 50, unlocked = 12),
            ),
        )

        assertEquals(ManualImportFeedbackTone.SUCCESS, feedback.tone)
        assertEquals("Game found and imported", feedback.title)
    }

    @Test fun rejectedStoreAppUsesProminentNotFoundFeedback() {
        val feedback = manualImportFeedback(
            ManualSharedGameImportResult.NotAGame(620),
        )

        assertEquals(ManualImportFeedbackTone.ERROR, feedback.tone)
        assertEquals("Game not found", feedback.title)
    }
}
