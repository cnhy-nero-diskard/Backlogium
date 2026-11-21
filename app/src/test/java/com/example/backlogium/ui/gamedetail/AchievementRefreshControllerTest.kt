package com.example.backlogium.ui.gamedetail

import com.example.backlogium.domain.AchievementRefreshOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AchievementRefreshControllerTest {
    @Test fun repeatedTapsDoNotQueueAndRetryStartsAnotherRequest() = runTest {
        var calls = 0
        val gate = CompletableDeferred<Unit>()
        val controller = AchievementRefreshController(backgroundScope) {
            calls++
            gate.await()
            AchievementRefreshOutcome.FAILED
        }
        controller.show(10, "visit")
        repeat(5) { controller.refresh() }
        runCurrent()
        assertEquals(1, calls)
        assertTrue(controller.state.value.pending)
        gate.complete(Unit)
        runCurrent()
        assertEquals(AchievementRefreshOutcome.FAILED, controller.state.value.outcome)
        controller.refresh()
        runCurrent()
        assertEquals(2, calls)
    }

    @Test fun oldGameAndOldVisitCannotPublishEvenIfCancellationIsIgnored() = runTest {
        val gate = CompletableDeferred<Unit>()
        val controller = AchievementRefreshController(backgroundScope) {
            withContext(NonCancellable) { gate.await() }
            AchievementRefreshOutcome.UPDATED
        }
        controller.show(10, "first")
        controller.refresh()
        runCurrent()
        controller.show(20, "second")
        gate.complete(Unit)
        runCurrent()
        assertEquals(AchievementRefreshActionState(), controller.state.value)
        controller.refresh()
        runCurrent()
        assertEquals(AchievementRefreshOutcome.UPDATED, controller.state.value.outcome)
        controller.leave()
        assertEquals(AchievementRefreshActionState(), controller.state.value)
        controller.show(20, "third")
        assertNull(controller.state.value.outcome)
    }
}
