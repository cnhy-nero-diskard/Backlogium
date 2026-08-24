package com.example.backlogium.ui.settings

import com.example.backlogium.data.repo.ManualSharedGameImportResult
import com.example.backlogium.data.repo.PlayerDataProbe
import org.junit.Assert.assertEquals
import org.junit.Test

class ManualSharedGameToastTest {
    @Test
    fun importedGameShowsAddedToast() {
        val feedback = manualImportFeedback(
            ManualSharedGameImportResult.Imported(
                620,
                "Portal 2",
                false,
                PlayerDataProbe.NoData,
            ),
        )

        assertEquals("Family Shared game added.", manualImportToast(feedback))
    }

    @Test
    fun alreadyTrackedGameShowsAlreadyTrackedToast() {
        val feedback = manualImportFeedback(
            ManualSharedGameImportResult.Imported(
                620,
                "Portal 2",
                true,
                PlayerDataProbe.NoData,
            ),
        )

        assertEquals("Family Shared game is already tracked.", manualImportToast(feedback))
    }

    @Test
    fun rejectedGameShowsNotAddedToast() {
        val feedback = manualImportFeedback(
            ManualSharedGameImportResult.NotAGame(620),
        )

        assertEquals("Family Shared game was not added.", manualImportToast(feedback))
    }
}
