package com.example.backlogium.ui.gamedetail

import com.example.backlogium.domain.GameFavorite
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FavoriteActionControllerTest {
    @Test fun pendingTapsAreSuppressedAndOnlyCommittedStateIsPresented() = runTest {
        val favorite = MutableStateFlow(GameFavorite(1, false, "fixture"))
        val gate = CompletableDeferred<Unit>()
        var writes = 0
        val controller = FavoriteActionController(backgroundScope, { favorite }) { _, desired, _ ->
            writes++; gate.await(); favorite.value = favorite.value.copy(isFavorite = desired)
        }
        controller.show(1); runCurrent()
        controller.toggle(); controller.toggle(); runCurrent()
        assertEquals(1, writes)
        assertTrue(controller.state.value.pending)
        assertFalse(controller.state.value.favorite!!.isFavorite)
        gate.complete(Unit); runCurrent()
        assertTrue(controller.state.value.favorite!!.isFavorite)
        assertFalse(controller.state.value.pending)
        assertEquals("Added to favorites", controller.state.value.feedback)
    }

    @Test fun failedWritePreservesStateAndRetryUsesTheSameAccount() = runTest {
        val favorite = MutableStateFlow(GameFavorite(1, true, "fixture"))
        var fail = true
        val controller = FavoriteActionController(backgroundScope, { favorite }) { _, desired, identity ->
            assertEquals("fixture", identity)
            if (fail) error("fixture failure")
            favorite.value = favorite.value.copy(isFavorite = desired)
        }
        controller.show(1); runCurrent(); controller.toggle(); runCurrent()
        assertTrue(controller.state.value.favorite!!.isFavorite)
        assertTrue(controller.state.value.failed)
        fail = false; controller.toggle(); runCurrent()
        assertFalse(controller.state.value.favorite!!.isFavorite)
        assertFalse(controller.state.value.failed)
        favorite.value = GameFavorite(1, true, "another-account"); runCurrent()
        assertNull(controller.state.value.favorite)
    }

    @Test fun accountChangeDuringWriteCannotRestoreTheOldAccountAction() = runTest {
        val favorite = MutableStateFlow(GameFavorite(1, false, "fixture"))
        val gate = CompletableDeferred<Unit>()
        val controller = FavoriteActionController(backgroundScope, { favorite }) { _, _, _ -> gate.await() }
        controller.show(1); runCurrent(); controller.toggle(); runCurrent()
        favorite.value = GameFavorite(1, false, "another-account"); runCurrent()
        gate.complete(Unit); runCurrent()
        assertNull(controller.state.value.favorite)
        assertFalse(controller.state.value.pending)
        assertTrue(controller.state.value.failed)
        assertEquals("Account changed. Reopen the game.", controller.state.value.feedback)
    }
}
