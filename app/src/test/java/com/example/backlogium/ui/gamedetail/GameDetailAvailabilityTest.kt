package com.example.backlogium.ui.gamedetail

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.backlogium.domain.AchievementCatalogueState
import com.example.backlogium.domain.AchievementHydrationState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GameDetailAvailabilityTest {
    @get:Rule val compose = createComposeRule()

    @Test fun offlineUnknownOffersAnAccessibleRetryWithoutClaimingNoAchievements() {
        var retries = 0
        compose.setContent { MaterialTheme {
            AchievementAvailability(GameDetailUiState(loading = false,
                hydration = AchievementHydrationState.Unavailable(offline = true)), { retries++ })
        } }
        compose.onNodeWithText("Achievements unavailable while offline.").assertIsDisplayed()
        compose.onNodeWithText("Retry achievements").performClick()
        assertEquals(1, retries)
        compose.onNodeWithText("This game has no achievements.").assertDoesNotExist()
    }

    @Test fun onlyConfirmedEmptyClaimsNoAchievements() {
        compose.setContent { MaterialTheme {
            AchievementAvailability(GameDetailUiState(loading = false,
                catalogue = AchievementCatalogueState(fetchedAt = 1, confirmedEmpty = true),
                hydration = AchievementHydrationState.Ready), {})
        } }
        compose.onNodeWithText("This game has no achievements.").assertIsDisplayed()
        compose.onNodeWithText("Retry achievements").assertDoesNotExist()
    }

    @Test fun loadingIsUnderstandableWithoutAnimation() {
        compose.setContent { MaterialTheme {
            AchievementAvailability(GameDetailUiState(hydration = AchievementHydrationState.Loading), {})
        } }
        compose.onNodeWithText("Loading achievements…").assertIsDisplayed()
    }
}
