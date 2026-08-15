package com.example.backlogium.data.backup

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [readBytesUpTo] in isolation (tasks.md 4.2/4.4): the read itself is the bound, so it must
 * stop well short of an unbounded stream even when nothing — no reported size, no content-length —
 * tells it how large the data actually is. [BackupRepository.parseFrom] composes this with
 * [querySize] and the [ParsedBackup.TooLarge] decision, which needs a real `ContentResolver` to
 * exercise end-to-end and is covered by inspection rather than duplicated here.
 */
class BackupImportSizeGuardTest {

    /** An input stream with no declared length, standing in for "reported size absent". */
    private class UnboundedStream(private val totalBytes: Long) : java.io.InputStream() {
        private var produced = 0L
        override fun read(): Int = error("not used")
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (produced >= totalBytes) return -1
            val n = minOf(len, (totalBytes - produced).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            produced += n
            return n
        }
    }

    @Test
    fun boundedRead_stopsShortOfAnUnboundedStream() {
        val limit = 1_000L
        val stream = UnboundedStream(totalBytes = 10_000_000L)

        val bytes = stream.readBytesUpTo(limit)

        // Allowed to overshoot by at most one 8 KB chunk, never anywhere close to the full stream.
        assertTrue(bytes.size.toLong() in limit..(limit + 8192))
    }

    @Test
    fun boundedRead_returnsExactContentWhenUnderLimit() {
        val payload = "small backup contents".toByteArray()
        val bytes = java.io.ByteArrayInputStream(payload).readBytesUpTo(maxBytes = 1_000_000L)
        assertTrue(bytes.contentEquals(payload))
    }
}
