package com.example.backlogium.domain

import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PersonalMomentumObservationTest {
    @Test fun dateZoneAndAccountReplacementCancelObsoleteReadsAndRetainOldDatesOnlyForSameAccount() = runTest {
        val first = MomentumKey("a", LocalDate.of(2026, 10, 3), ZoneId.of("UTC"))
        val keys = MutableStateFlow(first)
        val observed = mutableListOf<MomentumRead>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            observePersonalMomentum(keys) { key -> flow {
                emit(null); delay(100)
                emit(PersonalMomentum(key, completedWeeks(key.today), MomentumAvailability.NO_INCREASE, emptyList()))
            } }.collect { observed.add(it) }
        }
        advanceTimeBy(101); runCurrent()
        keys.value = first.copy(today = first.today.plusDays(1)); runCurrent()
        assertTrue(observed.last().updating)
        assertEquals(first, observed.last().result!!.key)
        keys.value = keys.value.copy(zone = ZoneId.of("Asia/Taipei")); runCurrent()
        keys.value = keys.value.copy(accountId = "b"); runCurrent()
        assertNull(observed.last().result)
        assertEquals("b", observed.last().key!!.accountId)
        advanceTimeBy(101); runCurrent()
        assertEquals(keys.value, observed.last().result!!.key)
        assertTrue(observed.filter { !it.updating }.map { it.key }.all { it == first || it == keys.value })
        job.cancel()
    }
}
