package com.example.backlogium.domain

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ExpiryTickerTest {

    @Test
    fun `emits immediately and at the exact expiry deadline`() = runTest {
        val expiryAt = 5_000L

        val emissions = exactExpiryTicks(
            nowMillis = { testScheduler.currentTime },
            nextExpiryAt = { expiryAt },
        ).take(2).toList()

        assertEquals(listOf(0L, expiryAt), emissions)
    }
}
