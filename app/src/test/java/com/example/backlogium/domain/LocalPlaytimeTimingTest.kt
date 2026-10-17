package com.example.backlogium.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalPlaytimeTimingTest {
    private fun sample(
        at: Long,
        game: Long? = 440,
        account: String = "fixture-account",
        generation: String = "fixture-process",
        success: Boolean = true,
    ) = LocalPlaytimeSample(account, generation, game, at, success)

    @Test
    fun `first sample and end-only observation supply no interval`() {
        assertNull(LocalPlaytimeTiming.span(null, sample(30_000)))
        assertNull(LocalPlaytimeTiming.span(sample(0), sample(30_000, game = null)))
        assertNull(LocalPlaytimeTiming.span(sample(0, game = null), sample(30_000)))
    }

    @Test
    fun `adjacent samples supply bounded local evidence without quantity`() {
        val evidence = LocalPlaytimeTiming.span(sample(0), sample(30_000))!!
        assertEquals(0L, evidence.startAt)
        assertEquals(30_000L, evidence.endAt)
        assertEquals(PlaytimeEvidenceSource.LOCAL, evidence.source)
        assertEquals(evidence, LocalPlaytimeTiming.span(sample(0), sample(30_000)))
    }

    @Test
    fun `failed request invalidates subsequent continuity`() {
        assertNull(LocalPlaytimeTiming.span(sample(0), sample(30_000, success = false)))
        assertNull(LocalPlaytimeTiming.span(sample(30_000, success = false), sample(60_000)))
    }

    @Test
    fun `account process game and monitoring gaps cannot be bridged`() {
        val previous = sample(0)
        assertNull(LocalPlaytimeTiming.span(previous, sample(30_000, account = "another-account")))
        assertNull(LocalPlaytimeTiming.span(previous, sample(30_000, generation = "new-process")))
        assertNull(LocalPlaytimeTiming.span(previous, sample(30_000, game = 570)))
        assertNull(LocalPlaytimeTiming.span(previous, sample(90_001)))
        assertNull(LocalPlaytimeTiming.span(previous, sample(0)))
        assertNull(LocalPlaytimeTiming.span(previous, sample(-1)))
        assertNull(LocalPlaytimeTiming.span(sample(Long.MIN_VALUE), sample(Long.MAX_VALUE)))
    }
}
