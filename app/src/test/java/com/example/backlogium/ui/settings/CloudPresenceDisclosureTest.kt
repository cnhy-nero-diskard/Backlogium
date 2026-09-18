package com.example.backlogium.ui.settings

import com.example.backlogium.ui.gamedetail.observedCoverageRemedy
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

    @Test fun coverageRemedyNamesTheObserverThatIsNotConfigured() {
        assertTrue(observedCoverageRemedy(false, false).contains("background monitoring"))
        assertTrue(observedCoverageRemedy(false, false).contains("cloud presence"))
        assertTrue(observedCoverageRemedy(true, false).contains("cloud presence"))
        assertTrue(observedCoverageRemedy(false, true).contains("background presence"))
        assertTrue(observedCoverageRemedy(true, true).isEmpty())
    }

    @Test fun refileDisclosureLimitsItsImpactToAttribution() {
        assertTrue(CLOUD_PRESENCE_REFILING_DISCLOSURE.contains("Dates, quests, and streaks may change"))
        assertTrue(CLOUD_PRESENCE_REFILING_DISCLOSURE.contains("Experience, levels, and total playtime will not"))
    }
}
