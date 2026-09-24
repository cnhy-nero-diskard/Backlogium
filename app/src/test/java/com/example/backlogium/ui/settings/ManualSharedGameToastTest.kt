package com.example.backlogium.ui.settings

import com.example.backlogium.R
import com.example.backlogium.data.repo.ManualImportUnavailableAt
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

        assertEquals(R.string.settings_shared_game_toast_added, manualImportToast(feedback).resId)
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

        assertEquals(R.string.settings_shared_game_toast_already_tracked, manualImportToast(feedback).resId)
    }

    @Test
    fun rejectedGameShowsNotAddedToast() {
        val feedback = manualImportFeedback(
            ManualSharedGameImportResult.NotAGame(620),
        )

        assertEquals(R.string.settings_shared_game_toast_not_added, manualImportToast(feedback).resId)
    }

    @Test
    fun invalidInputShowsCheckTheLinkFeedback() {
        val feedback = manualImportFeedback(ManualSharedGameImportResult.InvalidInput)

        assertEquals(ManualImportFeedbackTone.ERROR, feedback.tone)
        assertEquals(R.string.settings_shared_game_feedback_title_invalid_input, feedback.title.resId)
        assertEquals(R.string.settings_shared_game_toast_not_added, manualImportToast(feedback).resId)
    }

    @Test
    fun ownedGameShowsAlreadyInLibraryFeedback() {
        val feedback = manualImportFeedback(ManualSharedGameImportResult.Owned(440, "Team Fortress 2"))

        assertEquals(ManualImportFeedbackTone.INFO, feedback.tone)
        assertEquals(R.string.settings_shared_game_feedback_title_owned, feedback.title.resId)
        assertEquals(R.string.settings_shared_game_feedback_owned, feedback.message.resId)
        assertEquals(listOf("Team Fortress 2"), feedback.message.args)
        assertEquals(R.string.settings_shared_game_toast_not_added, manualImportToast(feedback).resId)
    }

    @Test
    fun excludedGameShowsRemovedFeedback() {
        val feedback = manualImportFeedback(ManualSharedGameImportResult.Excluded(42))

        assertEquals(ManualImportFeedbackTone.ERROR, feedback.tone)
        assertEquals(R.string.settings_shared_game_feedback_title_excluded, feedback.title.resId)
        assertEquals(R.string.settings_shared_game_toast_not_added, manualImportToast(feedback).resId)
    }

    @Test
    fun unavailableOwnedLibraryShowsCouldntCheckSteamFeedback() {
        val feedback = manualImportFeedback(
            ManualSharedGameImportResult.Unavailable(100, ManualImportUnavailableAt.OWNED_LIBRARY),
        )

        assertEquals(ManualImportFeedbackTone.ERROR, feedback.tone)
        assertEquals(R.string.settings_shared_game_feedback_owned_unavailable, feedback.message.resId)
        assertEquals(R.string.settings_shared_game_toast_not_added, manualImportToast(feedback).resId)
    }

    @Test
    fun unavailableStoreShowsCouldntCheckSteamFeedback() {
        val feedback = manualImportFeedback(
            ManualSharedGameImportResult.Unavailable(200, ManualImportUnavailableAt.STORE),
        )

        assertEquals(ManualImportFeedbackTone.ERROR, feedback.tone)
        assertEquals(R.string.settings_shared_game_feedback_store_unavailable, feedback.message.resId)
        assertEquals(R.string.settings_shared_game_toast_not_added, manualImportToast(feedback).resId)
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
        assertEquals(R.string.settings_shared_game_feedback_imported, feedback.message.resId)
        val probe = feedback.message.args[1] as SettingsText
        assertEquals(R.plurals.settings_shared_game_feedback_achievements_returned, probe.resId)
        assertEquals(listOf(12, 4), probe.args)
        assertEquals(R.string.settings_shared_game_toast_added, manualImportToast(feedback).resId)
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

        val probe = feedback.message.args[1] as SettingsText
        assertEquals(R.string.settings_shared_game_feedback_no_achievements, probe.resId)
        assertEquals(R.string.settings_shared_game_toast_added, manualImportToast(feedback).resId)
    }

    @Test
    fun importedGameWithUnavailableProbeMentionsTemporarilyUnavailable() {
        val feedback = manualImportFeedback(
            ManualSharedGameImportResult.Imported(401, "Flaky Probe", true, PlayerDataProbe.Unavailable),
        )

        val probe = feedback.message.args[1] as SettingsText
        assertEquals(R.string.settings_shared_game_feedback_achievement_check_unavailable, probe.resId)
        assertEquals(R.string.settings_shared_game_toast_already_tracked, manualImportToast(feedback).resId)
    }
}
