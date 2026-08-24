package com.example.backlogium.ui.settings

import com.example.backlogium.data.repo.ManualImportUnavailableAt
import com.example.backlogium.data.repo.ManualSharedGameImportResult
import com.example.backlogium.data.repo.PlayerDataProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun invalidInputShowsCheckTheLinkFeedback() {
        val feedback = manualImportFeedback(ManualSharedGameImportResult.InvalidInput)

        assertEquals(ManualImportFeedbackTone.ERROR, feedback.tone)
        assertEquals("Check the link", feedback.title)
        assertEquals("Family Shared game was not added.", manualImportToast(feedback))
    }

    @Test
    fun ownedGameShowsAlreadyInLibraryFeedback() {
        val feedback = manualImportFeedback(ManualSharedGameImportResult.Owned(440, "Team Fortress 2"))

        assertEquals(ManualImportFeedbackTone.INFO, feedback.tone)
        assertEquals("Already in your library", feedback.title)
        assertEquals(
            "Team Fortress 2 is in your owned Steam library; no Family Shared import was made.",
            feedback.message,
        )
        assertEquals("Family Shared game was not added.", manualImportToast(feedback))
    }

    @Test
    fun excludedGameShowsRemovedFeedback() {
        val feedback = manualImportFeedback(ManualSharedGameImportResult.Excluded(42))

        assertEquals(ManualImportFeedbackTone.ERROR, feedback.tone)
        assertEquals("Game is removed", feedback.title)
        assertEquals("Family Shared game was not added.", manualImportToast(feedback))
    }

    @Test
    fun unavailableOwnedLibraryShowsCouldntCheckSteamFeedback() {
        val feedback = manualImportFeedback(
            ManualSharedGameImportResult.Unavailable(100, ManualImportUnavailableAt.OWNED_LIBRARY),
        )

        assertEquals(ManualImportFeedbackTone.ERROR, feedback.tone)
        assertEquals("Steam ownership check is unavailable. Try again.", feedback.message)
        assertEquals("Family Shared game was not added.", manualImportToast(feedback))
    }

    @Test
    fun unavailableStoreShowsCouldntCheckSteamFeedback() {
        val feedback = manualImportFeedback(
            ManualSharedGameImportResult.Unavailable(200, ManualImportUnavailableAt.STORE),
        )

        assertEquals(ManualImportFeedbackTone.ERROR, feedback.tone)
        assertEquals("Steam Store verification is unavailable. Try again.", feedback.message)
        assertEquals("Family Shared game was not added.", manualImportToast(feedback))
    }

    @Test
    fun importedGameWithAchievementDataMentionsUnlockCount() {
        val feedback = manualImportFeedback(
            ManualSharedGameImportResult.Imported(
                300,
                "Borrowed Game",
                false,
                PlayerDataProbe.Returned(total = 12, unlocked = 4),
            ),
        )

        assertEquals(ManualImportFeedbackTone.SUCCESS, feedback.tone)
        assertTrue(feedback.message.contains("Steam returned 12 achievements; 4 unlocked."))
        assertEquals("Family Shared game added.", manualImportToast(feedback))
    }

    @Test
    fun importedGameWithZeroAchievementsMentionsNoAchievements() {
        val feedback = manualImportFeedback(
            ManualSharedGameImportResult.Imported(
                301,
                "No Achievements Game",
                false,
                PlayerDataProbe.Returned(total = 0, unlocked = 0),
            ),
        )

        assertTrue(feedback.message.contains("this game has no achievements"))
        assertEquals("Family Shared game added.", manualImportToast(feedback))
    }

    @Test
    fun importedGameWithUnavailableProbeMentionsTemporarilyUnavailable() {
        val feedback = manualImportFeedback(
            ManualSharedGameImportResult.Imported(401, "Flaky Probe", true, PlayerDataProbe.Unavailable),
        )

        assertTrue(feedback.message.contains("The achievement check is temporarily unavailable."))
        assertEquals("Family Shared game is already tracked.", manualImportToast(feedback))
    }
}
