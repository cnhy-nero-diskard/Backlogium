package com.example.backlogium.ui.settings

import com.example.backlogium.data.repo.CloudReadFailure
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
