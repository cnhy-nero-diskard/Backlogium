package com.example.backlogium.ui.settings

import com.example.backlogium.R
import com.example.backlogium.data.repo.CloudConfigurationResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsViewModelCloudActionTest {
    @Test
    fun invalidVerificationResultCarriesErrorSeverity() {
        val feedback = CloudConfigurationResult.InvalidEndpoint.toSettingsActionFeedback()

        assertEquals(SettingsResultSeverity.ERROR, feedback.severity)
        assertEquals(R.string.settings_cloud_feedback_invalid_endpoint, feedback.message.resId)
    }

    @Test
    fun thrownRemoveAndRefilingActionsBecomeTypedErrors() = runTest {
        val removeFailure = settingsCloudActionFeedback(SettingsText(R.string.settings_cloud_feedback_remove_failed)) {
            throw IllegalStateException("remove failed")
        }
        val refileFailure = settingsCloudActionFeedback(SettingsText(R.string.settings_cloud_feedback_refile_failed)) {
            throw IllegalStateException("re-file failed")
        }

        assertEquals(SettingsResultSeverity.ERROR, removeFailure.severity)
        assertEquals(SettingsResultSeverity.ERROR, refileFailure.severity)
    }

    @Test
    fun successfulCloudActionCarriesSuccessSeverity() = runTest {
        val result = settingsCloudActionFeedback(SettingsText(R.string.settings_cloud_feedback_remove_failed)) {
            SettingsActionFeedback.success(SettingsText(R.string.settings_cloud_feedback_removed))
        }

        assertEquals(SettingsResultSeverity.SUCCESS, result.severity)
    }
}
