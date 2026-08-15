package com.example.backlogium.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [BoundedInputStream] (tasks.md 4.2/4.4): the read itself is the bound, so it must fail
 * well short of an unbounded stream even when nothing — no reported size, no content-length —
 * says how large the data is. [BackupRepository.parseFrom] composes this with [querySize] and the
 * [ParsedBackup.TooLarge] decision, which needs a real `ContentResolver` to exercise end-to-end.
 */
class BackupImportSizeGuardTest {

    /** A stream with no declared length, standing in for "reported size absent". */
    private class UnboundedStream(private val totalBytes: Long) : java.io.InputStream() {
        var produced = 0L
            private set

        override fun read(): Int = error("not used")

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (produced >= totalBytes) return -1
            val n = minOf(len, (totalBytes - produced).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            produced += n
            return n
        }
    }

    @Test
    fun boundedRead_failsFastOnAnUnboundedStream() {
        val limit = 1_000L
        val source = UnboundedStream(totalBytes = 10_000_000L)
        val bounded = BoundedInputStream(source, limit)

        assertThrows(StreamLimitExceededException::class.java) {
            bounded.readBytes()
        }

        // The point of a streaming bound: it gives up almost immediately instead of pulling the
        // whole 10 MB. One buffer's overshoot is expected; orders of magnitude are not.
        assertTrue("consumed ${source.produced} bytes", source.produced < limit + 64 * 1024)
    }

    @Test
    fun boundedRead_reportsHowFarItGot() {
        val bounded = BoundedInputStream(UnboundedStream(totalBytes = 10_000L), maxBytes = 100L)
        val failure = assertThrows(StreamLimitExceededException::class.java) { bounded.readBytes() }
        assertTrue(failure.bytesReadAtLeast > 100L)
    }

    @Test
    fun contentUnderTheLimit_passesThroughUnchanged() {
        val payload = "small backup contents".toByteArray()
        val bounded = BoundedInputStream(java.io.ByteArrayInputStream(payload), maxBytes = 1_000_000L)
        assertTrue(bounded.readBytes().contentEquals(payload))
    }

    @Test
    fun contentExactlyAtTheLimit_isAccepted() {
        val payload = ByteArray(256) { 'a'.code.toByte() }
        val bounded = BoundedInputStream(java.io.ByteArrayInputStream(payload), maxBytes = 256L)
        assertEquals(256, bounded.readBytes().size)
    }
}
