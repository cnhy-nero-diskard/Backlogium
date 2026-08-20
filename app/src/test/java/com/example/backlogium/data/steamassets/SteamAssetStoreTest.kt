package com.example.backlogium.data.steamassets

import com.example.backlogium.data.local.entity.SteamAssetManifest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowBitmapFactory
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Unit coverage for [SteamAssetStore]'s file-integrity and validated-write behavior
 * (add-offline-steam-assets, task 2.7).
 *
 * [ShadowBitmapFactory.setAllowInvalidImageData] is disabled in [setUp]: by default Robolectric's
 * `BitmapFactory` shadow fakes a decoded bitmap (fixed 100x100 bounds) for byte content it
 * doesn't recognize as any image format, which would make [SteamAssetStore.write]'s "reject
 * undecodable bytes" behavior untestable — the fake decode would always "succeed". With it
 * disabled, unrecognized bytes correctly report failed bounds (-1/-1), and a genuinely valid
 * PNG still decodes to its real dimensions, matching real Android's BitmapFactory behavior for
 * the inputs this test exercises.
 */
@RunWith(RobolectricTestRunner::class)
class SteamAssetStoreTest {

    private lateinit var store: SteamAssetStore

    @Before
    fun setUp() {
        ShadowBitmapFactory.setAllowInvalidImageData(false)
        store = SteamAssetStore(RuntimeEnvironment.getApplication())
    }

    /**
     * Builds a minimal, genuinely decodable PNG by hand (signature + IHDR + IDAT + IEND) rather
     * than via `java.awt`/`javax.imageio`, which are unavailable on this module's unit-test
     * compile classpath (no `java.desktop`).
     */
    private fun validPngBytes(width: Int = 2, height: Int = 2, fill: Byte = 0): ByteArray {
        fun be32(value: Int) = byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())
        fun chunk(type: String, data: ByteArray): ByteArray {
            val typeAndData = type.toByteArray(Charsets.US_ASCII) + data
            val crc = CRC32().apply { update(typeAndData) }
            return be32(data.size) + typeAndData + be32(crc.value.toInt())
        }

        val ihdr = be32(width) + be32(height) + byteArrayOf(8, 2, 0, 0, 0) // 8-bit depth, RGB, no interlace
        val raw = ByteArrayOutputStream()
        repeat(height) {
            raw.write(0) // filter type: none
            repeat(width) { raw.write(byteArrayOf(fill, fill, fill)) }
        }
        val deflater = Deflater().apply { setInput(raw.toByteArray()); finish() }
        val compressed = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (!deflater.finished()) compressed.write(buffer, 0, deflater.deflate(buffer))

        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        return signature + chunk("IHDR", ihdr) + chunk("IDAT", compressed.toByteArray()) + chunk("IEND", ByteArray(0))
    }

    private fun manifestFor(
        url: String,
        stored: SteamAssetStore.StoredFile,
        state: String = SteamAssetManifestState.STORED.name,
    ) = SteamAssetManifest(
        normalizedUrl = store.normalizedUrl(url),
        kind = SteamAssetKind.GAME_ICON.name,
        relativePath = stored.relativePath,
        byteCount = stored.bytes,
        checksum = stored.checksum,
        state = state,
        lastSuccessAt = 1_000L,
        lastCheckedAt = 1_000L,
    )

    @Test
    fun normalizedUrl_stripsFragmentAndTrimsWhitespace() {
        assertEquals(
            "https://example.test/a.jpg",
            store.normalizedUrl("  https://example.test/a.jpg#fragment  "),
        )
    }

    @Test
    fun write_rejectsNonImageContentType() = runBlocking {
        val saved = store.write("https://example.test/a.jpg", "text/html", validPngBytes())
        assertNull(saved)
    }

    @Test
    fun write_rejectsNullContentType() = runBlocking {
        val saved = store.write("https://example.test/a.jpg", null, validPngBytes())
        assertNull(saved)
    }

    @Test
    fun write_rejectsEmptyBytes() = runBlocking {
        val saved = store.write("https://example.test/a.jpg", "image/jpeg", ByteArray(0))
        assertNull(saved)
    }

    @Test
    fun write_rejectsNonDecodableBytes() = runBlocking {
        val saved = store.write("https://example.test/a.jpg", "image/jpeg", byteArrayOf(1, 2, 3, 4, 5))
        assertNull(saved)
    }

    @Test
    fun write_acceptsValidImageAndProducesInternallyConsistentStoredFile() = runBlocking {
        val bytes = validPngBytes()
        val saved = store.write("https://example.test/a.jpg", "image/png; charset=utf-8", bytes)

        assertNotNull(saved)
        checkNotNull(saved)
        val file = store.fileFor(manifestFor("https://example.test/a.jpg", saved))
        assertNotNull(file)
        checkNotNull(file)
        assertTrue(file.isFile)
        assertEquals(saved.bytes, file.length())
        assertEquals(bytes.size.toLong(), file.length())
    }

    @Test
    fun isValid_trueForFreshlyWrittenFile() = runBlocking {
        val saved = store.write("https://example.test/a.jpg", "image/png", validPngBytes())
        checkNotNull(saved)

        val manifest = manifestFor("https://example.test/a.jpg", saved)
        assertTrue(store.isValid(manifest))
    }

    @Test
    fun isValid_falseWhenFileIsMissing() = runBlocking {
        val manifest = SteamAssetManifest(
            normalizedUrl = "https://example.test/missing.jpg",
            kind = SteamAssetKind.GAME_ICON.name,
            relativePath = "does-not-exist.img",
            byteCount = 10L,
            checksum = "deadbeef",
            state = SteamAssetManifestState.STORED.name,
            lastSuccessAt = 1_000L,
            lastCheckedAt = 1_000L,
        )
        assertFalse(store.isValid(manifest))
    }

    @Test
    fun isValid_falseWhenChecksumDoesNotMatch() = runBlocking {
        val saved = store.write("https://example.test/a.jpg", "image/png", validPngBytes())
        checkNotNull(saved)

        val manifest = manifestFor("https://example.test/a.jpg", saved).copy(checksum = "wrong-checksum")
        assertFalse(store.isValid(manifest))
    }

    @Test
    fun isValid_falseWhenByteCountDoesNotMatchFileLength() = runBlocking {
        val saved = store.write("https://example.test/a.jpg", "image/png", validPngBytes())
        checkNotNull(saved)

        val manifest = manifestFor("https://example.test/a.jpg", saved).copy(byteCount = saved.bytes + 1)
        assertFalse(store.isValid(manifest))
    }

    @Test
    fun isValid_falseWhenStateIsNotStored() = runBlocking {
        val saved = store.write("https://example.test/a.jpg", "image/png", validPngBytes())
        checkNotNull(saved)

        val manifest = manifestFor("https://example.test/a.jpg", saved, state = SteamAssetManifestState.UNAVAILABLE.name)
        assertFalse(store.isValid(manifest))
    }

    @Test
    fun isValid_falseWhenRelativePathIsNull() = runBlocking {
        val manifest = SteamAssetManifest(
            normalizedUrl = "https://example.test/a.jpg",
            kind = SteamAssetKind.GAME_ICON.name,
            relativePath = null,
            byteCount = 0L,
            checksum = null,
            state = SteamAssetManifestState.STORED.name,
            lastSuccessAt = null,
            lastCheckedAt = 1_000L,
        )
        assertFalse(store.isValid(manifest))
    }

    @Test
    fun fileFor_rejectsPathTraversal() {
        val manifest = SteamAssetManifest(
            normalizedUrl = "https://example.test/a.jpg",
            kind = SteamAssetKind.GAME_ICON.name,
            relativePath = "../outside.img",
            byteCount = 0L,
            checksum = null,
            state = SteamAssetManifestState.STORED.name,
            lastSuccessAt = null,
            lastCheckedAt = 1_000L,
        )
        assertNull(store.fileFor(manifest))
    }

    @Test
    fun write_replacesPriorFileForSameUrlAtomically() = runBlocking {
        val first = store.write("https://example.test/a.jpg", "image/png", validPngBytes(width = 2, height = 2, fill = 0))
        checkNotNull(first)

        val second = store.write("https://example.test/a.jpg", "image/png", validPngBytes(width = 4, height = 4, fill = 10))
        checkNotNull(second)

        // Same URL hashes to the same relative path; the second write should be reflected there,
        // with a genuinely different (larger, differently-filled) payload than the first.
        assertEquals(first.relativePath, second.relativePath)
        assertTrue(first.bytes != second.bytes)
        val manifest = manifestFor("https://example.test/a.jpg", second)
        assertTrue(store.isValid(manifest))
    }
}
