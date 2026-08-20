package com.example.backlogium.data.steamassets

import androidx.room.Room
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.dao.AchievementDao
import com.example.backlogium.data.local.dao.GameDao
import com.example.backlogium.data.local.dao.PlayerProfileDao
import com.example.backlogium.data.local.dao.SteamAssetDao
import com.example.backlogium.data.local.entity.Achievement
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.PlayerProfile
import com.example.backlogium.data.local.entity.SteamAssetManifest
import com.example.backlogium.data.remote.SteamIconMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowBitmapFactory
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Unit coverage for [SteamAssetRepository] (add-offline-steam-assets, task 2.7): inventory
 * building, resume/skip semantics, and HTTP outcome classification.
 *
 * No MockWebServer dependency is declared in this module's version catalog (verified against
 * `gradle/libs.versions.toml` and `app/build.gradle.kts`), so instead of adding one, HTTP
 * behavior is simulated with a real [OkHttpClient] whose single [Interceptor] short-circuits
 * before any real network call and returns a scripted [Response] (or throws [IOException] to
 * simulate a network failure). This exercises the exact same `client.newCall(...).execute()`
 * code path in [SteamAssetRepository.process] that MockWebServer would, without the extra
 * dependency.
 *
 * [ShadowBitmapFactory.setAllowInvalidImageData] is disabled in [setUp] for the same reason as
 * [SteamAssetStoreTest]: the "invalid body -> FAILED" classification depends on `BitmapFactory`
 * genuinely rejecting undecodable bytes, which Robolectric's default fake-decode fallback does
 * not do.
 */
@RunWith(RobolectricTestRunner::class)
class SteamAssetRepositoryTest {

    private lateinit var db: BacklogiumDatabase
    private lateinit var assetDao: SteamAssetDao
    private lateinit var gameDao: GameDao
    private lateinit var playerProfileDao: PlayerProfileDao
    private lateinit var achievementDao: AchievementDao
    private lateinit var store: SteamAssetStore

    @Before
    fun setUp() {
        ShadowBitmapFactory.setAllowInvalidImageData(false)
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            BacklogiumDatabase::class.java,
        ).allowMainThreadQueries().build()
        assetDao = db.steamAssetDao()
        gameDao = db.gameDao()
        playerProfileDao = db.playerProfileDao()
        achievementDao = db.achievementDao()
        store = SteamAssetStore(RuntimeEnvironment.getApplication())
    }

    @After
    fun tearDown() = db.close()

    /**
     * Builds a minimal, genuinely decodable PNG by hand (signature + IHDR + IDAT + IEND) rather
     * than via `java.awt`/`javax.imageio`, which are unavailable on this module's unit-test
     * compile classpath (no `java.desktop`).
     */
    private fun validPngBytes(size: Int = 2): ByteArray {
        fun be32(value: Int) = byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())
        fun chunk(type: String, data: ByteArray): ByteArray {
            val typeAndData = type.toByteArray(Charsets.US_ASCII) + data
            val crc = CRC32().apply { update(typeAndData) }
            return be32(data.size) + typeAndData + be32(crc.value.toInt())
        }

        val ihdr = be32(size) + be32(size) + byteArrayOf(8, 2, 0, 0, 0) // 8-bit depth, RGB, no interlace
        val raw = ByteArrayOutputStream()
        repeat(size) {
            raw.write(0) // filter type: none
            repeat(size) { raw.write(byteArrayOf(0, 0, 0)) }
        }
        val deflater = Deflater().apply { setInput(raw.toByteArray()); finish() }
        val compressed = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (!deflater.finished()) compressed.write(buffer, 0, deflater.deflate(buffer))

        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        return signature + chunk("IHDR", ihdr) + chunk("IDAT", compressed.toByteArray()) + chunk("IEND", ByteArray(0))
    }

    private fun scriptedResponse(request: Request, code: Int, contentType: String?, bytes: ByteArray): Response {
        val builder = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("scripted")
            .body(bytes.toResponseBody(contentType?.toMediaTypeOrNull()))
        if (contentType != null) builder.header("Content-Type", contentType)
        return builder.build()
    }

    private fun repositoryWith(interceptor: Interceptor): SteamAssetRepository {
        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        return SteamAssetRepository(assetDao, store, client)
    }

    private suspend fun noopProgress(processed: Int, total: Int, label: String, counts: SteamAssetRunCounts) = Unit

    // ---- inventory() ----

    @Test
    fun inventory_coversAllAssetKindsAndDedupesRepeatedUrls() = runBlocking {
        gameDao.upsert(
            Game(appId = 440L, name = "Game", iconUrl = "https://example.test/icon-440.jpg", playtimeForever = 0, playtime2Weeks = 0, lastPlaytime = 0),
        )
        // A second game reusing the exact same icon URL should not produce a duplicate entry.
        gameDao.upsert(
            Game(appId = 441L, name = "Other", iconUrl = "https://example.test/icon-440.jpg", playtimeForever = 0, playtime2Weeks = 0, lastPlaytime = 0),
        )
        gameDao.upsert(
            Game(appId = 442L, name = "No icon hash", iconUrl = "", playtimeForever = 0, playtime2Weeks = 0, lastPlaytime = 0),
        )
        playerProfileDao.upsert(PlayerProfile(avatarUrl = "https://example.test/avatar.jpg"))
        achievementDao.upsertAll(listOf(Achievement(appId = 440L, apiName = "ACH", iconUrl = "https://example.test/ach.jpg", retired = false, fetchedAt = 1L)))

        val repository = repositoryWith(Interceptor { chain -> throw IOException("no HTTP expected") })
        val items = repository.inventory()
        val byUrl = items.associateBy { it.url }

        assertTrue(byUrl.containsKey("https://example.test/avatar.jpg"))
        assertEquals(SteamAssetKind.AVATAR, byUrl["https://example.test/avatar.jpg"]?.kind)
        assertTrue(byUrl.containsKey("https://example.test/ach.jpg"))
        assertEquals(SteamAssetKind.ACHIEVEMENT, byUrl["https://example.test/ach.jpg"]?.kind)

        // One GAME_ICON entry despite two games sharing the same icon URL.
        assertEquals(1, items.count { it.kind == SteamAssetKind.GAME_ICON })
        assertEquals("https://example.test/icon-440.jpg", items.single { it.kind == SteamAssetKind.GAME_ICON }.url)

        // Artwork variants are derived per-appId, so both games contribute independently.
        assertTrue(byUrl.containsKey(SteamIconMapper.headerUrl(440L)))
        assertTrue(byUrl.containsKey(SteamIconMapper.headerUrl(441L)))
        assertTrue(byUrl.containsKey(SteamIconMapper.headerUrl(442L)))
        assertEquals(SteamAssetKind.HEADER, byUrl[SteamIconMapper.headerUrl(440L)]?.kind)
        assertTrue(byUrl.containsKey(SteamIconMapper.heroCapsuleUrl(440L)))
        assertTrue(byUrl.containsKey(SteamIconMapper.libraryHeroUrl(440L)))
        assertTrue(byUrl.containsKey(SteamIconMapper.libraryCapsuleUrl(440L)))
        assertTrue(byUrl.containsKey(SteamIconMapper.wideCapsuleUrl(440L)))
    }

    // ---- shouldSkip / freshness resume semantics (exercised through run()) ----

    @Test
    fun run_downloadMissing_skipsUnavailableMarkerCheckedWithinFreshnessWindow() = runBlocking {
        val url = "https://example.test/avatar.jpg"
        playerProfileDao.upsert(PlayerProfile(avatarUrl = url))
        val twentyNineDaysAgo = System.currentTimeMillis() - 29L * 24 * 60 * 60 * 1000
        assetDao.upsert(
            SteamAssetManifest(
                normalizedUrl = store.normalizedUrl(url),
                kind = SteamAssetKind.AVATAR.name,
                state = SteamAssetManifestState.UNAVAILABLE.name,
                lastCheckedAt = twentyNineDaysAgo,
            ),
        )

        val calls = AtomicInteger(0)
        val repository = repositoryWith(
            Interceptor { chain -> calls.incrementAndGet(); scriptedResponse(chain.request(), 200, "image/png", validPngBytes()) },
        )

        val counts = repository.run(SteamAssetDownloadMode.DOWNLOAD_MISSING, System.currentTimeMillis(), ::noopProgress)

        assertEquals(0, calls.get())
        assertEquals(1, counts.unavailable)
        assertEquals(0, counts.stored)
    }

    @Test
    fun run_downloadMissing_retriesUnavailableMarkerOlderThanFreshnessWindow() = runBlocking {
        val url = "https://example.test/avatar.jpg"
        playerProfileDao.upsert(PlayerProfile(avatarUrl = url))
        val thirtyOneDaysAgo = System.currentTimeMillis() - 31L * 24 * 60 * 60 * 1000
        assetDao.upsert(
            SteamAssetManifest(
                normalizedUrl = store.normalizedUrl(url),
                kind = SteamAssetKind.AVATAR.name,
                state = SteamAssetManifestState.UNAVAILABLE.name,
                lastCheckedAt = thirtyOneDaysAgo,
            ),
        )

        val calls = AtomicInteger(0)
        val repository = repositoryWith(
            Interceptor { chain -> calls.incrementAndGet(); scriptedResponse(chain.request(), 200, "image/png", validPngBytes()) },
        )

        val counts = repository.run(SteamAssetDownloadMode.DOWNLOAD_MISSING, System.currentTimeMillis(), ::noopProgress)

        assertEquals(1, calls.get())
        assertEquals(1, counts.stored)
    }

    // ---- HTTP outcome classification ----

    @Test
    fun run_classifies404And410AsUnavailable() = runBlocking {
        playerProfileDao.upsert(PlayerProfile(avatarUrl = "https://example.test/avatar-404.jpg"))
        gameDao.upsert(Game(appId = 1L, name = "G", iconUrl = "https://example.test/icon-410.jpg", playtimeForever = 0, playtime2Weeks = 0, lastPlaytime = 0))

        val repository = repositoryWith(
            Interceptor { chain ->
                val code = if (chain.request().url.toString().contains("avatar")) 404 else 410
                scriptedResponse(chain.request(), code, null, ByteArray(0))
            },
        )

        val counts = repository.run(SteamAssetDownloadMode.DOWNLOAD_MISSING, System.currentTimeMillis(), ::noopProgress)

        assertEquals(SteamAssetManifestState.UNAVAILABLE.name, assetDao.get(store.normalizedUrl("https://example.test/avatar-404.jpg"))?.state)
        assertEquals(SteamAssetManifestState.UNAVAILABLE.name, assetDao.get(store.normalizedUrl("https://example.test/icon-410.jpg"))?.state)
        assertTrue(counts.unavailable >= 2)
    }

    @Test
    fun run_classifies200WithValidImageAsStored() = runBlocking {
        playerProfileDao.upsert(PlayerProfile(avatarUrl = "https://example.test/avatar-ok.jpg"))

        val repository = repositoryWith(
            Interceptor { chain -> scriptedResponse(chain.request(), 200, "image/png", validPngBytes()) },
        )

        val counts = repository.run(SteamAssetDownloadMode.DOWNLOAD_MISSING, System.currentTimeMillis(), ::noopProgress)

        val manifest = assetDao.get(store.normalizedUrl("https://example.test/avatar-ok.jpg"))
        assertEquals(SteamAssetManifestState.STORED.name, manifest?.state)
        assertTrue(manifest != null && store.isValid(manifest))
        assertEquals(1, counts.stored)
    }

    @Test
    fun run_classifies200WithInvalidBodyAsFailed() = runBlocking {
        playerProfileDao.upsert(PlayerProfile(avatarUrl = "https://example.test/avatar-bad.jpg"))

        val repository = repositoryWith(
            Interceptor { chain -> scriptedResponse(chain.request(), 200, "image/png", byteArrayOf(9, 9, 9)) },
        )

        val counts = repository.run(SteamAssetDownloadMode.DOWNLOAD_MISSING, System.currentTimeMillis(), ::noopProgress)

        assertNull(assetDao.get(store.normalizedUrl("https://example.test/avatar-bad.jpg")))
        assertEquals(1, counts.failed)
    }

    @Test
    fun run_classifiesNetworkErrorAsFailed() = runBlocking {
        playerProfileDao.upsert(PlayerProfile(avatarUrl = "https://example.test/avatar-network-error.jpg"))

        val repository = repositoryWith(Interceptor { throw IOException("simulated network failure") })

        val counts = repository.run(SteamAssetDownloadMode.DOWNLOAD_MISSING, System.currentTimeMillis(), ::noopProgress)

        assertNull(assetDao.get(store.normalizedUrl("https://example.test/avatar-network-error.jpg")))
        assertEquals(1, counts.failed)
    }

    // ---- failed-refresh preservation ----

    @Test
    fun run_failedRefreshLeavesPreviouslyStoredFileAndManifestUntouched() = runBlocking {
        val url = "https://example.test/avatar-refresh.jpg"
        playerProfileDao.upsert(PlayerProfile(avatarUrl = url))

        // Seed a genuinely valid, previously-stored asset.
        val saved = store.write(url, "image/png", validPngBytes())
        checkNotNull(saved)
        val goodManifest = SteamAssetManifest(
            normalizedUrl = store.normalizedUrl(url),
            kind = SteamAssetKind.AVATAR.name,
            relativePath = saved.relativePath,
            byteCount = saved.bytes,
            checksum = saved.checksum,
            state = SteamAssetManifestState.STORED.name,
            lastSuccessAt = 1_000L,
            lastCheckedAt = 1_000L,
        )
        assetDao.upsert(goodManifest)

        // REFRESH_ALL always attempts a re-download; script a broken response for it.
        val repository = repositoryWith(
            Interceptor { chain -> scriptedResponse(chain.request(), 200, "image/png", byteArrayOf(1, 2, 3)) },
        )

        val counts = repository.run(SteamAssetDownloadMode.REFRESH_ALL, System.currentTimeMillis(), ::noopProgress)

        assertEquals(1, counts.failed)
        val afterRun = assetDao.get(store.normalizedUrl(url))
        assertEquals(goodManifest, afterRun)
        assertTrue(afterRun != null && store.isValid(afterRun))
    }

    // ---- stale-manifest reconciliation ----

    @Test
    fun run_reconcilesStaleManifestRowsAndOrphanFilesWithoutDeletingCurrentAsset() = runBlocking {
        val currentUrl = "https://example.test/current.jpg"
        val staleUrl = "https://example.test/stale.jpg"
        playerProfileDao.upsert(PlayerProfile(avatarUrl = currentUrl))

        val currentSaved = checkNotNull(store.write(currentUrl, "image/png", validPngBytes()))
        val staleSaved = checkNotNull(store.write(staleUrl, "image/png", validPngBytes()))
        val currentManifest = SteamAssetManifest(
            normalizedUrl = store.normalizedUrl(currentUrl),
            kind = SteamAssetKind.AVATAR.name,
            relativePath = currentSaved.relativePath,
            byteCount = currentSaved.bytes,
            checksum = currentSaved.checksum,
            state = SteamAssetManifestState.STORED.name,
            lastSuccessAt = 1_000L,
            lastCheckedAt = 1_000L,
        )
        val staleManifest = currentManifest.copy(
            normalizedUrl = store.normalizedUrl(staleUrl),
            relativePath = staleSaved.relativePath,
            byteCount = staleSaved.bytes,
            checksum = staleSaved.checksum,
        )
        assetDao.upsert(currentManifest)
        assetDao.upsert(staleManifest)

        val repository = repositoryWith(Interceptor { throw IOException("current asset should be skipped") })
        val counts = repository.run(SteamAssetDownloadMode.DOWNLOAD_MISSING, System.currentTimeMillis(), ::noopProgress)

        assertEquals(1, counts.alreadyPresent)
        assertNull(assetDao.get(staleManifest.normalizedUrl))
        assertTrue(store.isValid(currentManifest))
        assertTrue(store.fileFor(staleManifest)?.exists() != true)
    }

    @Test
    // ---- bounded concurrency ----
    fun run_boundsConcurrentInFlightRequestsToFour() = runBlocking(Dispatchers.Default) {
        // Enough distinct URLs (achievement icons, one per row) to exceed MAX_CONCURRENCY if
        // requests ran unbounded.
        gameDao.upsert(Game(appId = 1L, name = "G", iconUrl = "", playtimeForever = 0, playtime2Weeks = 0, lastPlaytime = 0))
        val achievements = (1..12).map { i ->
            Achievement(appId = 1L, apiName = "ACH_$i", iconUrl = "https://example.test/ach-$i.jpg", retired = false, fetchedAt = 1L)
        }
        achievementDao.upsertAll(achievements)

        val active = AtomicInteger(0)
        val maxObserved = AtomicInteger(0)
        val interceptor = Interceptor { chain ->
            val current = active.incrementAndGet()
            maxObserved.updateAndGet { previous -> maxOf(previous, current) }
            try {
                Thread.sleep(75)
                scriptedResponse(chain.request(), 200, "image/png", validPngBytes())
            } finally {
                active.decrementAndGet()
            }
        }
        val repository = repositoryWith(interceptor)

        repository.run(SteamAssetDownloadMode.DOWNLOAD_MISSING, System.currentTimeMillis(), ::noopProgress)

        assertTrue("expected concurrency to be bounded at ${SteamAssetRepository.MAX_CONCURRENCY}, was ${maxObserved.get()}", maxObserved.get() <= SteamAssetRepository.MAX_CONCURRENCY)
        assertTrue("expected some real overlap between requests, observed max was ${maxObserved.get()}", maxObserved.get() > 1)
    }
}
