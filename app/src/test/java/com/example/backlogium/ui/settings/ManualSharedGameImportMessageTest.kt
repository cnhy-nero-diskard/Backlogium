package com.example.backlogium.ui.settings

import com.example.backlogium.R
import com.example.backlogium.data.repo.ManualImportUnavailableAt
import com.example.backlogium.data.repo.ManualSharedGameImportResult
import com.example.backlogium.data.repo.PlayerDataProbe
import org.junit.Assert.assertEquals
import org.junit.Test

class ManualSharedGameImportMessageTest {
    @Test fun invalidInputExplainsAcceptedShape() {
        assertEquals(
            R.string.settings_shared_game_feedback_invalid_input,
            manualImportMessage(ManualSharedGameImportResult.InvalidInput).resId,
        )
    }

    @Test fun ownedResultRefusesSharedImport() {
        val message = manualImportMessage(ManualSharedGameImportResult.Owned(620, "Portal 2"))
        assertEquals(R.string.settings_shared_game_feedback_owned, message.resId)
        assertEquals(listOf("Portal 2"), message.args)
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
        assertEquals(R.string.settings_shared_game_feedback_imported, message.resId)
        val tracking = message.args[0] as SettingsText
        assertEquals(R.string.settings_shared_game_feedback_tracked_now, tracking.resId)
        assertEquals(listOf("Portal 2"), tracking.args)
        val probe = message.args[1] as SettingsText
        assertEquals(R.plurals.settings_shared_game_feedback_achievements_returned, probe.resId)
        assertEquals(listOf(50, 12), probe.args)
    }

    @Test fun importedResultDistinguishesNoDataFromUnavailable() {
        val noData = manualImportMessage(
            ManualSharedGameImportResult.Imported(1, "Game", false, PlayerDataProbe.NoData),
        )
        val unavailable = manualImportMessage(
            ManualSharedGameImportResult.Imported(1, "Game", true, PlayerDataProbe.Unavailable),
        )
        val noDataProbe = noData.args[1] as SettingsText
        val unavailableProbe = unavailable.args[1] as SettingsText
        assertEquals(R.string.settings_shared_game_feedback_no_player_data, noDataProbe.resId)
        assertEquals(R.string.settings_shared_game_feedback_achievement_check_unavailable, unavailableProbe.resId)
    }

    @Test fun unavailableResultNamesFailedSafetyCheck() {
        val message = manualImportMessage(
            ManualSharedGameImportResult.Unavailable(1, ManualImportUnavailableAt.OWNED_LIBRARY),
        )
        assertEquals(R.string.settings_shared_game_feedback_owned_unavailable, message.resId)
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
        assertEquals(R.string.settings_shared_game_feedback_title_imported, feedback.title.resId)
    }

    @Test fun rejectedStoreAppUsesProminentNotFoundFeedback() {
        val feedback = manualImportFeedback(
            ManualSharedGameImportResult.NotAGame(620),
        )

        assertEquals(ManualImportFeedbackTone.ERROR, feedback.tone)
        assertEquals(R.string.settings_shared_game_feedback_title_not_game, feedback.title.resId)
    }
}
