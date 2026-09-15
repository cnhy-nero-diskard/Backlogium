package com.example.backlogium.ui.settings

import org.junit.Assert.assertTrue
import org.junit.Test

class CloudPresenceDisclosureTest {
    @Test fun disclosureStatesAdditionalWhenPlayedRecord() {
        assertTrue(
            CLOUD_PRESENCE_DISCLOSURE.contains("adds a record of when play happened"),
        )
    }

    @Test fun disclosureStatesAppFunctionsFullyWithoutIt() {
        assertTrue(
            CLOUD_PRESENCE_DISCLOSURE.contains("functions fully without it"),
        )
    }
}
