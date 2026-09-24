package com.example.backlogium.ui.settings

import com.example.backlogium.R
import com.example.backlogium.data.repo.CloudConfigurationResult
import com.example.backlogium.data.repo.CloudReadFailure
import com.example.backlogium.data.updates.AppUpdateState
import com.example.backlogium.data.updates.AvailableUpdate
import com.example.backlogium.gamification.RuleConfig
import com.example.backlogium.work.SteamAssetDownloadStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsPresentationTest {
    @Test
    fun inventory_mapsEverySectionOperationDialogAndBusyStateExactlyOnce() {
        assertTrue(settingsInventoryIssues().toString(), settingsInventoryIssues().isEmpty())
        assertEquals(16, SETTINGS_INVENTORY.size)
        assertEquals(4, SETTINGS_INVENTORY.map { it.group }.toSet().size)
    }

    @Test
    fun attentionUsesBlockingThenProgressThenRecommendationPriority() {
        assertEquals(SettingsAttention.BLOCKING, settingsAttention(true, true, true))
        assertEquals(SettingsAttention.IN_PROGRESS, settingsAttention(false, true, true))
        assertEquals(SettingsAttention.RECOMMENDED, settingsAttention(false, false, true))
        assertEquals(SettingsAttention.HEALTHY, settingsAttention(false, false, false))
    }

    @Test
    fun loadingKeepsAllFourGroupsIdentifiableWithoutResolvedAction() {
        val summaries = settingsGroupSummaries(SettingsUiState())

        assertEquals(4, summaries.size)
        assertTrue(summaries.all { it.loading })
        assertTrue(summaries.all { it.attention == SettingsAttention.IN_PROGRESS })
    }

    @Test
    fun accountSummaryCoversUnconfiguredFailedAndInProgressStates() {
        val unconfigured = settingsGroupSummaries(SettingsUiState(loading = false)).first()
        assertEquals(SettingsAttention.BLOCKING, unconfigured.attention)

        val failed = settingsGroupSummaries(
            SettingsUiState(loading = false, configured = true, lastSyncError = "offline"),
        ).first()
        assertEquals(SettingsAttention.BLOCKING, failed.attention)

        val syncing = settingsGroupSummaries(
            SettingsUiState(loading = false, configured = true, isSyncing = true),
        ).first()
        assertEquals(SettingsAttention.IN_PROGRESS, syncing.attention)
    }

    @Test
    fun activeUpdateCheckTakesPriorityOverAvailableUpdateRecommendation() {
        val summary = settingsGroupSummaries(
            SettingsUiState(
                loading = false,
                configured = true,
                appUpdateState = AppUpdateState(
                    available = AvailableUpdate(
                        tag = "v1.8.0",
                        versionName = "1.8.0",
                        versionCode = 1_008_000L,
                        releaseName = "Release",
                        releaseNotes = "Notes",
                        apkName = "app-release.apk",
                        apkUrl = "https://example.test/app.apk",
                        checksumUrl = "https://example.test/app.sha256",
                    ),
                ),
                updateCheckInProgress = true,
            ),
        ).first()

        assertEquals(SettingsAttention.IN_PROGRESS, summary.attention)
    }

    @Test
    fun failedUpdateCheckBlocksAccountSummaryUntilItsResultIsCleared() {
        val messageOnly = settingsGroupSummaries(
            SettingsUiState(
                loading = false,
                configured = true,
                updateCheckMessage = SettingsText(R.string.settings_update_check_failed),
            ),
        ).first()
        assertEquals(SettingsAttention.HEALTHY, messageOnly.attention)

        val failedState = SettingsUiState(
            loading = false,
            configured = true,
            updateCheckMessage = SettingsText(R.string.settings_update_check_failed),
            updateCheckSeverity = SettingsResultSeverity.ERROR,
        )
        val failed = settingsGroupSummaries(failedState).first()
        assertEquals(SettingsAttention.BLOCKING, failed.attention)

        val cleared = settingsGroupSummaries(
            failedState.copy(updateCheckMessage = null, updateCheckSeverity = null),
        ).first()
        assertEquals(SettingsAttention.HEALTHY, cleared.attention)
    }

    @Test
    fun failedManualSharedGameImportBlocksGameplayUntilItsFeedbackIsCleared() {
        val failedState = SettingsUiState(
            loading = false,
            configured = true,
            manualSharedGameFeedback = ManualImportFeedback(
                tone = ManualImportFeedbackTone.ERROR,
                title = SettingsText(R.string.settings_shared_game_feedback_title_unavailable),
                message = SettingsText(R.string.settings_shared_game_feedback_store_unavailable),
                toastMessage = SettingsText(R.string.settings_shared_game_toast_not_added),
            ),
        )

        val failed = settingsGroupSummaries(failedState).single { it.group == SettingsGroup.GAMEPLAY }
        assertEquals(SettingsAttention.BLOCKING, failed.attention)

        val retrying = settingsGroupSummaries(
            failedState.copy(manualSharedGameBusy = true, manualSharedGameFeedback = null),
        ).single { it.group == SettingsGroup.GAMEPLAY }
        assertEquals(SettingsAttention.IN_PROGRESS, retrying.attention)

        val completed = settingsGroupSummaries(
            failedState.copy(
                manualSharedGameBusy = false,
                manualSharedGameFeedback = ManualImportFeedback(
                    tone = ManualImportFeedbackTone.SUCCESS,
                    title = SettingsText(R.string.settings_shared_game_feedback_title_imported),
                    message = SettingsText(R.string.settings_shared_game_feedback_imported),
                    toastMessage = SettingsText(R.string.settings_shared_game_toast_added),
                ),
            ),
        ).single { it.group == SettingsGroup.GAMEPLAY }
        assertEquals(SettingsAttention.HEALTHY, completed.attention)

        val cleared = settingsGroupSummaries(
            failedState.copy(manualSharedGameFeedback = null),
        ).single { it.group == SettingsGroup.GAMEPLAY }
        assertEquals(SettingsAttention.HEALTHY, cleared.attention)
    }

    @Test
    fun summariesExposeRecommendationsAndQuietHealth() {
        val recommended = settingsGroupSummaries(
            SettingsUiState(
                loading = false,
                configured = true,
                nonGameCandidateCount = 3,
                savedConfig = RuleConfig(),
                draft = RuleDraft.from(RuleConfig()),
            ),
        )
        assertEquals(SettingsAttention.RECOMMENDED, recommended[1].attention)
        assertEquals(SettingsAttention.RECOMMENDED, recommended[2].attention)

        val healthy = settingsGroupSummaries(
            SettingsUiState(
                loading = false,
                configured = true,
                cloudHealthy = true,
                savedConfig = RuleConfig(),
                draft = RuleDraft.from(RuleConfig()),
            ),
        )
        assertEquals(SettingsAttention.HEALTHY, healthy[2].attention)
        assertEquals(SettingsAttention.HEALTHY, healthy[3].attention)
    }

    @Test
    fun invalidRetainedRuleDraftBlocksAdvancedSummaryUntilCorrectedOrDiscarded() {
        val saved = RuleConfig()
        val invalidDraft = RuleDraft.from(saved).with(RuleField.LEVEL_BASE, "0")
        val invalidState = SettingsUiState(
            loading = false,
            savedConfig = saved,
            draft = invalidDraft,
        )

        assertTrue(invalidState.hasInvalidField)
        assertNull(invalidState.candidate)
        assertFalse(invalidState.dirty)
        val invalidSummary = settingsGroupSummaries(invalidState).single { it.group == SettingsGroup.ADVANCED }
        assertEquals(SettingsAttention.BLOCKING, invalidSummary.attention)
        assertEquals(R.string.settings_summary_advanced_invalid, invalidSummary.status.resId)
        assertEquals(R.string.settings_action_fix_invalid_rules, invalidSummary.nextAction.resId)

        val corrected = invalidState.copy(
            draft = invalidDraft.with(RuleField.LEVEL_BASE, (saved.levelBase + 1).toString()),
        )
        assertEquals(
            SettingsAttention.RECOMMENDED,
            settingsGroupSummaries(corrected).single { it.group == SettingsGroup.ADVANCED }.attention,
        )

        val discarded = invalidState.copy(draft = RuleDraft.from(saved))
        assertEquals(
            SettingsAttention.HEALTHY,
            settingsGroupSummaries(discarded).single { it.group == SettingsGroup.ADVANCED }.attention,
        )
    }

    @Test
    fun dataPrivacyReturnsToHealthyAfterCloudReadSucceedsDespiteHistoricalFailure() {
        val state = SettingsUiState(
            loading = false,
            configured = true,
            cloudEndpoint = "https://reader.example",
            cloudLastFailureAt = 1_000L,
            cloudLastFailure = CloudReadFailure.UNREACHABLE,
            cloudLastSuccessAt = 2_000L,
            cloudHealthy = true,
        )

        val dataPrivacy = settingsGroupSummaries(state).single { it.group == SettingsGroup.DATA_PRIVACY }

        assertEquals(SettingsAttention.HEALTHY, dataPrivacy.attention)
        assertFalse(state.hasDataAttention())
    }

    @Test
    fun failedSteamAssetDownloadBlocksDataPrivacyButCancelledDownloadDoesNot() {
        val healthyState = SettingsUiState(
            loading = false,
            configured = true,
            cloudEndpoint = "https://reader.example",
            cloudHealthy = true,
        )
        val failedState = healthyState.copy(steamAssetStatus = SteamAssetDownloadStatus.FAILED)
        val cancelledState = healthyState.copy(steamAssetStatus = SteamAssetDownloadStatus.CANCELLED)

        val failed = settingsGroupSummaries(failedState).single { it.group == SettingsGroup.DATA_PRIVACY }
        assertEquals(SettingsAttention.BLOCKING, failed.attention)
        assertTrue(failedState.hasDataAttention())

        val cancelled = settingsGroupSummaries(cancelledState).single { it.group == SettingsGroup.DATA_PRIVACY }
        assertEquals(SettingsAttention.HEALTHY, cancelled.attention)
        assertFalse(cancelledState.hasDataAttention())
    }

    @Test
    fun invalidCloudVerificationBlocksDataPrivacyWithoutARecordedRepositoryFailure() {
        val feedback = CloudConfigurationResult.InvalidEndpoint.toSettingsActionFeedback()
        val state = SettingsUiState(
            loading = false,
            configured = true,
            cloudMessage = feedback.message,
            cloudMessageSeverity = feedback.severity,
            cloudHealthy = null,
        )

        val dataPrivacy = settingsGroupSummaries(state).single { it.group == SettingsGroup.DATA_PRIVACY }

        assertEquals(SettingsResultSeverity.ERROR, feedback.severity)
        assertEquals(SettingsAttention.BLOCKING, dataPrivacy.attention)
        assertTrue(state.hasDataAttention())
    }

    @Test
    fun cloudActionFailuresBlockDataPrivacyWhileRepositoryStillLooksHealthy() {
        val healthyCloud = SettingsUiState(
            loading = false,
            configured = true,
            cloudEndpoint = "https://reader.example",
            cloudHealthy = true,
        )
        val removeFailure = SettingsActionFeedback.error(SettingsText(R.string.settings_cloud_feedback_remove_failed))
        val refileFailure = SettingsActionFeedback.error(SettingsText(R.string.settings_cloud_feedback_refile_failed))

        val removeState = healthyCloud.copy(
            cloudMessage = removeFailure.message,
            cloudMessageSeverity = removeFailure.severity,
        )
        val refileState = healthyCloud.copy(
            cloudPresenceRefilingMessage = refileFailure.message,
            cloudPresenceRefilingMessageSeverity = refileFailure.severity,
        )

        listOf(removeState, refileState).forEach { state ->
            val dataPrivacy = settingsGroupSummaries(state).single { it.group == SettingsGroup.DATA_PRIVACY }
            assertEquals(SettingsAttention.BLOCKING, dataPrivacy.attention)
            assertTrue(state.hasDataAttention())
        }

        val cleared = removeState.copy(cloudMessage = null, cloudMessageSeverity = null)
        assertEquals(
            SettingsAttention.HEALTHY,
            settingsGroupSummaries(cleared).single { it.group == SettingsGroup.DATA_PRIVACY }.attention,
        )
    }

    @Test
    fun failedCompletionDatasetCheckBlocksDataUntilItsResultIsCleared() {
        val healthyCloud = SettingsUiState(
            loading = false,
            configured = true,
            cloudEndpoint = "https://reader.example",
            cloudHealthy = true,
        )
        val failedState = healthyCloud.copy(
            hltbDatasetCheckMessage = SettingsText(R.string.settings_update_check_failed),
            hltbDatasetCheckSeverity = SettingsResultSeverity.ERROR,
        )

        val failed = settingsGroupSummaries(failedState).single { it.group == SettingsGroup.DATA_PRIVACY }
        assertEquals(SettingsAttention.BLOCKING, failed.attention)
        assertTrue(failedState.hasDataAttention())

        val clearedState = failedState.copy(
            hltbDatasetCheckMessage = null,
            hltbDatasetCheckSeverity = null,
        )
        val cleared = settingsGroupSummaries(clearedState).single { it.group == SettingsGroup.DATA_PRIVACY }
        assertEquals(SettingsAttention.HEALTHY, cleared.attention)
        assertFalse(clearedState.hasDataAttention())
    }

    @Test
    fun failedContributionExportBlocksDataUntilItsResultIsReplaced() {
        val healthyCloud = SettingsUiState(
            loading = false,
            configured = true,
            cloudEndpoint = "https://reader.example",
            cloudHealthy = true,
        )
        val failedState = healthyCloud.copy(
            hltbContributionMessage = SettingsText(R.string.settings_completion_contribution_save_failed),
            hltbContributionSeverity = SettingsResultSeverity.ERROR,
        )

        val failed = settingsGroupSummaries(failedState).single { it.group == SettingsGroup.DATA_PRIVACY }
        assertEquals(SettingsAttention.BLOCKING, failed.attention)

        val replacedState = failedState.copy(
            hltbContributionMessage = SettingsText(R.plurals.settings_completion_contribution_saved, listOf(1), quantity = 1),
            hltbContributionSeverity = SettingsResultSeverity.SUCCESS,
        )
        val replaced = settingsGroupSummaries(replacedState).single { it.group == SettingsGroup.DATA_PRIVACY }
        assertEquals(SettingsAttention.HEALTHY, replaced.attention)
    }

    @Test
    fun overviewSummaryNeverCopiesSensitiveOrDiagnosticValues() {
        val state = SettingsUiState(
            loading = false,
            configured = true,
            steamId = "76561197960287930",
            apiKeyMasked = "••••1234",
            cloudEndpoint = "https://private.example/reader",
            cloudTokenMasked = "••••token",
            cloudLastFailure = CloudReadFailure.ACCOUNT_MISMATCH,
            cloudHealthy = false,
        )

        settingsGroupSummaries(state).forEach { summary ->
            assertFalse(summary.status.containsSensitiveSettingsValue(state))
            assertFalse(summary.nextAction.containsSensitiveSettingsValue(state))
        }
    }
}
