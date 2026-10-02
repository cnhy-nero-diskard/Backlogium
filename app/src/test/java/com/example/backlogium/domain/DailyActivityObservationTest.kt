package com.example.backlogium.domain

import java.time.LocalDate
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DailyActivityObservationTest {
    @Test fun dateAndAccountReplacementRejectObsoleteResults() = runTest {
        val today = LocalDate.of(2026, 10, 3)
        val keys = MutableStateFlow(DailyActivityKey("fixture-a", today))
        val results = mutableListOf<DailyActivityRead>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            observeDailyActivity(keys) { key -> flow {
                delay(100)
                emit(DailyActivity(key.date, key.accountId, emptyList(), 60, true))
            } }.collect { results.add(it) }
        }
        keys.value = DailyActivityKey("fixture-a", today.plusDays(1))
        runCurrent()
        keys.value = DailyActivityKey("fixture-b", today.plusDays(1))
        advanceTimeBy(101)
        runCurrent()
        assertTrue(results.filter { it.activity != null }.all { it.key == keys.value })
        assertEquals(keys.value, results.last().key)
        assertNotNull(results.last().activity)
        assertTrue(results.dropLast(1).all { it.activity == null })
        job.cancel()
    }
}
