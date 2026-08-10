package com.example.backlogium.ui.gamedetail

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class GameDetailRefreshTest {

    @Test
    fun oneShotRefresh_clearsRefreshingStateWhenFetchCompletes() = runTest {
        val refreshing = MutableStateFlow(false)
        var published: Int? = null

        refreshPlayerCountOnce(
            refreshing = refreshing,
            fetch = { 42 },
            publish = { published = it },
        )

        assertEquals(42, published)
        assertFalse(refreshing.value)
    }

    @Test
    fun oneShotRefresh_clearsRefreshingStateWhenSteamReturnsNoCount() = runTest {
        val refreshing = MutableStateFlow(false)
        var published: Int? = null

        refreshPlayerCountOnce(
            refreshing = refreshing,
            fetch = { null },
            publish = { published = it },
        )

        assertNull(published)
        assertFalse(refreshing.value)
    }
}
