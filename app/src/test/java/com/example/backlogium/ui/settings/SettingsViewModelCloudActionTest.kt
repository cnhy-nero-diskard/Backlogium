package com.example.backlogium.ui.settings

import com.example.backlogium.data.repo.CloudConfigurationResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsViewModelCloudActionTest {
    @Test
    fun invalidVerificationResultCarriesErrorSeverity() {
        val feedback = CloudConfigurationResult.InvalidEndpoint.toSettingsActionFeedback()

        assertEquals(SettingsResultSeverity.ERROR, feedback.severity)
        assertEquals("Use an HTTPS Cloud reader URL.", feedback.message)
    }

    @Test
    fun thrownRemoveAndRefilingActionsBecomeTypedErrors() = runTest {
        val removeFailure = settingsCloudActionFeedback("The cloud reader could not be removed.") {
            throw IllegalStateException("remove failed")
        }
        val refileFailure = settingsCloudActionFeedback("Cloud playtime could not be re-filed. Try again.") {
            throw IllegalStateException("re-file failed")
        }

        assertEquals(SettingsResultSeverity.ERROR, removeFailure.severity)
        assertEquals(SettingsResultSeverity.ERROR, refileFailure.severity)
    }

    @Test
    fun successfulCloudActionCarriesSuccessSeverity() = runTest {
        val result = settingsCloudActionFeedback("fallback") {
            SettingsActionFeedback.success("Cloud presence removed.")
        }

        assertEquals(SettingsResultSeverity.SUCCESS, result.severity)
    }
}
