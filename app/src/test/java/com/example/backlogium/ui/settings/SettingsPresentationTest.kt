package com.example.backlogium.ui.settings

import com.example.backlogium.data.repo.CloudReadFailure
import com.example.backlogium.data.updates.AppUpdateState
import com.example.backlogium.data.updates.AvailableUpdate
import com.example.backlogium.gamification.RuleConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
                updateCheckMessage = "Check did not complete. Try again later.",
            ),
        ).first()
        assertEquals(SettingsAttention.HEALTHY, messageOnly.attention)

        val failedState = SettingsUiState(
            loading = false,
            configured = true,
            updateCheckMessage = "Check did not complete. Try again later.",
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
                title = "Couldn't check Steam",
                message = "Steam Store verification is unavailable. Try again.",
            ),
        )

        val failed = settingsGroupSummaries(failedState).single { it.group == SettingsGroup.GAMEPLAY }
        assertEquals(SettingsAttention.BLOCKING, failed.attention)

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
    fun failedCompletionDatasetCheckBlocksDataUntilItsResultIsCleared() {
        val healthyCloud = SettingsUiState(
            loading = false,
            configured = true,
            cloudEndpoint = "https://reader.example",
            cloudHealthy = true,
        )
        val failedState = healthyCloud.copy(
            hltbDatasetCheckMessage = "Check did not complete. Try again later.",
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
            hltbContributionMessage = "Couldn't save the contribution file.",
            hltbContributionSeverity = SettingsResultSeverity.ERROR,
        )

        val failed = settingsGroupSummaries(failedState).single { it.group == SettingsGroup.DATA_PRIVACY }
        assertEquals(SettingsAttention.BLOCKING, failed.attention)

        val replacedState = failedState.copy(
            hltbContributionMessage = "Saved contribution file.",
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
