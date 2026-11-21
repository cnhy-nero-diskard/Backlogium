package com.example.backlogium.ui.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLoadingPresentationTest {

    @Test
    fun firstLoadShowsStableLoading_butOnboardingRemainsAuthoritative() {
        assertTrue(shouldShowHomeLoading(HomeUiState()))
        assertFalse(
            shouldShowHomeLoading(
                HomeUiState(
                    loading = true,
                    configured = false,
                ),
            ),
        )
    }

    @Test
    fun cachedContentStaysMountedDuringRefresh_andShowsUpdatingStatus() {
        val refreshing = HomeUiState(
            loading = true,
            hasRenderableContent = true,
            isSyncing = true,
        )

        assertFalse(shouldShowHomeLoading(refreshing))
        assertTrue(shouldShowHomeUpdating(refreshing))
        assertFalse(shouldShowHomeUpdating(refreshing.copy(isSyncing = false)))
    }
}
