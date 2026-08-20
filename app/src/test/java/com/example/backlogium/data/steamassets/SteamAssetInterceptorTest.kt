package com.example.backlogium.data.steamassets

import android.graphics.drawable.ColorDrawable
import coil.decode.DataSource
import coil.intercept.Interceptor
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.ImageResult
import coil.request.SuccessResult
import coil.size.Size
import com.example.backlogium.data.local.dao.SteamAssetDao
import com.example.backlogium.data.local.dao.SteamAssetStoredSummary
import com.example.backlogium.data.local.dao.SteamGameImageSource
import com.example.backlogium.data.local.entity.SteamAssetDownloadState
import com.example.backlogium.data.local.entity.SteamAssetManifest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.Base64

/**
 * Coverage for task 4.5 (add-offline-steam-assets): [SteamAssetLocalResolver]'s in-memory
 * manifest cache and [SteamAssetInterceptor]'s local-first / remote-fallback Coil interception.
 * Runs under Robolectric so [SteamAssetStore] can do real file I/O and real PNG decoding.
 */
@RunWith(RobolectricTestRunner::class)
class SteamAssetInterceptorTest {

    // ---- fixtures ---------------------------------------------------------------------------

    private fun newStore(): SteamAssetStore = SteamAssetStore(RuntimeEnvironment.getApplication())

    /** Writes real, valid image bytes through the store and builds the STORED manifest row for them. */
    private suspend fun storedManifest(store: SteamAssetStore, url: String): SteamAssetManifest {
        val saved = store.write(url, "image/png", PNG_BYTES) ?: error("fixture PNG failed to store")
        return SteamAssetManifest(
            normalizedUrl = store.normalizedUrl(url),
            kind = "icon",
            relativePath = saved.relativePath,
            byteCount = saved.bytes,
            checksum = saved.checksum,
            state = SteamAssetManifestState.STORED.name,
            lastSuccessAt = 1L,
            lastCheckedAt = 1L,
        )
    }

    /**
     * An Unconfined scope makes the resolver's `scope.launch { assetDao.observeAll().collect {} }`
     * run synchronously up to its first suspension: the initial collected value lands in the
     * in-memory map before the constructor call returns, and later `MutableStateFlow.value`
     * updates in these tests are observed synchronously too, with no advanceUntilIdle() needed.
     */
    private fun unconfinedScope(): CoroutineScope = CoroutineScope(Dispatchers.Unconfined)

    private fun request(url: String): ImageRequest =
        ImageRequest.Builder(RuntimeEnvironment.getApplication()).data(url).build()

    private fun success(request: ImageRequest, source: DataSource): ImageResult =
        SuccessResult(ColorDrawable(), request, source)

    private fun error(request: ImageRequest): ImageResult =
        ErrorResult(null, request, IllegalStateException("simulated decode failure"))

    /**
     * Minimal fake of Coil 2.7's `Interceptor.Chain`. `proceed()` is driven by a supplied function
     * and every call (and the request it was called with) is recorded, in order, for assertions.
     */
    private class FakeChain(
        override val request: ImageRequest,
        private val onProceed: (ImageRequest) -> ImageResult,
    ) : Interceptor.Chain {
        val proceededWith = mutableListOf<ImageRequest>()
        override val size: Size = Size.ORIGINAL
        override fun withRequest(request: ImageRequest): Interceptor.Chain = FakeChain(request, onProceed)
        override fun withSize(size: Size): Interceptor.Chain = this
        override suspend fun proceed(request: ImageRequest): ImageResult {
            proceededWith += request
            return onProceed(request)
        }
    }

    /** Hand-written `SteamAssetDao` fake: this module has no interface-mocking library (grepped, none found). */
    private class FakeSteamAssetDao : SteamAssetDao {
        private val rows = linkedMapOf<String, SteamAssetManifest>()
        private val state = MutableStateFlow<List<SteamAssetManifest>>(emptyList())
        val invalidated = mutableListOf<String>()

        fun seed(vararg manifests: SteamAssetManifest) {
            manifests.forEach { rows[it.normalizedUrl] = it }
            state.value = rows.values.toList()
        }

        fun drop(url: String) {
            rows.remove(url)
            state.value = rows.values.toList()
        }

        override suspend fun get(url: String): SteamAssetManifest? = rows[url]
        override fun observeAll(): Flow<List<SteamAssetManifest>> = state
        override suspend fun getAll(): List<SteamAssetManifest> = rows.values.toList()

        override suspend fun upsert(manifest: SteamAssetManifest) {
            rows[manifest.normalizedUrl] = manifest
            state.value = rows.values.toList()
        }

        override suspend fun invalidate(url: String) {
            invalidated += url
            rows.remove(url)
            state.value = rows.values.toList()
        }

        override fun observeStoredSummary(): Flow<SteamAssetStoredSummary> =
            throw UnsupportedOperationException("not exercised by these tests")

        override fun observeHasInventory(): Flow<Boolean> =
            throw UnsupportedOperationException("not exercised by these tests")

        override fun observeLastRun(): Flow<SteamAssetDownloadState?> =
            throw UnsupportedOperationException("not exercised by these tests")

        override suspend fun gameImageSources(): List<SteamGameImageSource> =
            throw UnsupportedOperationException("not exercised by these tests")

        override suspend fun profileAvatarUrl(): String? =
            throw UnsupportedOperationException("not exercised by these tests")

        override suspend fun achievementIconUrls(): List<String> =
            throw UnsupportedOperationException("not exercised by these tests")

        override suspend fun saveLastRun(state: SteamAssetDownloadState) =
            throw UnsupportedOperationException("not exercised by these tests")
    }

    // ---- SteamAssetLocalResolver --------------------------------------------------------------

    @Test
    fun localData_returnsFile_forValidStoredManifest() = runBlocking {
        val store = newStore()
        val dao = FakeSteamAssetDao()
        val url = "https://steamcommunity-a.akamaihd.net/economy/image/hit.jpg"
        val manifest = storedManifest(store, url)
        dao.seed(manifest)
        val resolver = SteamAssetLocalResolver(dao, store, unconfinedScope())

        val file = resolver.localData(url)

        assertEquals(store.fileFor(manifest), file)
        assertTrue((file as File).isFile)
        assertTrue(dao.invalidated.isEmpty())
    }

    @Test
    fun localData_returnsNullAndInvalidates_whenStoreReportsInvalid() = runBlocking {
        val store = newStore()
        val dao = FakeSteamAssetDao()
        val url = "https://steamcommunity-a.akamaihd.net/economy/image/corrupt.jpg"
        val manifest = storedManifest(store, url)
        // Corrupt the file on disk after recording it STORED, so store.isValid() fails the checksum/length check.
        store.fileFor(manifest)!!.writeBytes(byteArrayOf(1, 2, 3))
        dao.seed(manifest)
        val resolver = SteamAssetLocalResolver(dao, store, unconfinedScope())

        val file = resolver.localData(url)

        assertNull(file)
        assertEquals(listOf(manifest.normalizedUrl), dao.invalidated)
    }

    @Test
    fun localData_returnsNull_whenNoManifestPresent() = runBlocking {
        val store = newStore()
        val dao = FakeSteamAssetDao()
        val resolver = SteamAssetLocalResolver(dao, store, unconfinedScope())

        assertNull(resolver.localData("https://steamcommunity.com/nope.jpg"))
        assertTrue(dao.invalidated.isEmpty())
    }

    @Test
    fun invalidate_delegatesNormalizedUrlToDao() = runBlocking {
        val store = newStore()
        val dao = FakeSteamAssetDao()
        val resolver = SteamAssetLocalResolver(dao, store, unconfinedScope())

        resolver.invalidate("https://steamcommunity.com/foo.jpg#fragment-is-stripped")

        assertEquals(listOf("https://steamcommunity.com/foo.jpg"), dao.invalidated)
    }

    @Test
    fun snapshot_updatesWhenObserveAllEmits_addThenRemove() = runBlocking {
        val store = newStore()
        val dao = FakeSteamAssetDao()
        val url = "https://steamstatic.com/gadd.jpg"
        val resolver = SteamAssetLocalResolver(dao, store, unconfinedScope())

        assertNull(resolver.localData(url)) // nothing seeded yet

        val manifest = storedManifest(store, url)
        dao.seed(manifest)
        assertEquals(store.fileFor(manifest), resolver.localData(url))

        dao.drop(manifest.normalizedUrl)
        assertNull(resolver.localData(url))
    }

    // ---- SteamAssetInterceptor ----------------------------------------------------------------

    @Test
    fun localHit_servesLocalFile_withoutTouchingRemote() = runBlocking {
        val store = newStore()
        val dao = FakeSteamAssetDao()
        val url = "https://steamcommunity.com/public/images/apps/1/hero.jpg"
        val manifest = storedManifest(store, url)
        dao.seed(manifest)
        val resolver = SteamAssetLocalResolver(dao, store, unconfinedScope())
        val interceptor = SteamAssetInterceptor(resolver)
        val originalRequest = request(url)
        var remoteCalls = 0
        val chain = FakeChain(originalRequest) { req ->
            if (req.data is File) success(req, DataSource.DISK) else { remoteCalls++; success(req, DataSource.NETWORK) }
        }

        val result = interceptor.intercept(chain)

        assertEquals(1, chain.proceededWith.size)
        assertEquals(store.fileFor(manifest), chain.proceededWith.single().data)
        assertEquals(0, remoteCalls) // the local-hit path never reaches the "remote" branch at all
        assertTrue(result is SuccessResult)
        assertEquals(DataSource.DISK, (result as SuccessResult).dataSource)
    }

    @Test
    fun remoteMiss_proceedsWithOriginalRequestUnchanged() = runBlocking {
        val store = newStore()
        val dao = FakeSteamAssetDao() // nothing seeded: no manifest for this url
        val resolver = SteamAssetLocalResolver(dao, store, unconfinedScope())
        val interceptor = SteamAssetInterceptor(resolver)
        val url = "https://steamcommunity.com/public/images/apps/2/hero.jpg"
        val originalRequest = request(url)
        val chain = FakeChain(originalRequest) { req -> success(req, DataSource.NETWORK) }

        val result = interceptor.intercept(chain)

        assertEquals(1, chain.proceededWith.size)
        assertSame(originalRequest, chain.proceededWith.single())
        assertTrue(result is SuccessResult)
    }

    @Test
    fun corruptLocalFile_invalidatesAndRetriesRemote() = runBlocking {
        val store = newStore()
        val dao = FakeSteamAssetDao()
        val url = "https://steamcommunity.com/public/images/apps/3/hero.jpg"
        val manifest = storedManifest(store, url)
        dao.seed(manifest)
        val resolver = SteamAssetLocalResolver(dao, store, unconfinedScope())
        val interceptor = SteamAssetInterceptor(resolver)
        val originalRequest = request(url)
        val chain = FakeChain(originalRequest) { req ->
            if (req.data is File) error(req) else success(req, DataSource.NETWORK)
        }

        val result = interceptor.intercept(chain)

        assertEquals(2, chain.proceededWith.size)
        assertTrue(chain.proceededWith[0].data is File)
        assertSame(originalRequest, chain.proceededWith[1])
        assertEquals(listOf(manifest.normalizedUrl), dao.invalidated)
        assertTrue(result is SuccessResult)
        assertEquals(DataSource.NETWORK, (result as SuccessResult).dataSource)
    }

    @Test
    fun nonSteamOrNonHttpsUrl_passesThroughUnchanged() = runBlocking {
        val store = newStore()
        val dao = FakeSteamAssetDao()
        val resolver = SteamAssetLocalResolver(dao, store, unconfinedScope())
        val interceptor = SteamAssetInterceptor(resolver)

        val cases = listOf(
            "https://example.com/not-steam.jpg", // https, but not a recognized Steam asset host
            "http://steamcommunity.com/insecure.jpg", // recognized host, but not https
        )
        for (url in cases) {
            val originalRequest = request(url)
            val chain = FakeChain(originalRequest) { req -> success(req, DataSource.NETWORK) }

            interceptor.intercept(chain)

            assertEquals(1, chain.proceededWith.size)
            assertSame(originalRequest, chain.proceededWith.single())
        }
    }

    @Test
    fun nonStringRequestData_passesThroughUnchanged() = runBlocking {
        val store = newStore()
        val dao = FakeSteamAssetDao()
        val resolver = SteamAssetLocalResolver(dao, store, unconfinedScope())
        val interceptor = SteamAssetInterceptor(resolver)
        val originalRequest = ImageRequest.Builder(RuntimeEnvironment.getApplication())
            .data(File("/not/a/url.jpg"))
            .build()
        val chain = FakeChain(originalRequest) { req -> success(req, DataSource.NETWORK) }

        interceptor.intercept(chain)

        assertEquals(1, chain.proceededWith.size)
        assertSame(originalRequest, chain.proceededWith.single())
    }

    @Test
    fun sequentialCalls_resolveIndependently_inFixedOrder() = runBlocking {
        val store = newStore()
        val dao = FakeSteamAssetDao()
        val hitUrlA = "https://steamcommunity.com/apps/10/hit-a.jpg"
        val hitUrlB = "https://steamstatic.com/apps/11/hit-b.jpg"
        val missUrl = "https://steamcommunity.com/apps/12/miss.jpg"
        val manifestA = storedManifest(store, hitUrlA)
        val manifestB = storedManifest(store, hitUrlB)
        dao.seed(manifestA, manifestB)
        val resolver = SteamAssetLocalResolver(dao, store, unconfinedScope())
        val interceptor = SteamAssetInterceptor(resolver)

        // A fixed, ordered mix of hits and misses, some URLs repeated: each call must resolve on
        // its own, and earlier calls must not affect later ones (no reordering, no cross-call cache).
        val orderedUrls = listOf(hitUrlA, missUrl, hitUrlB, missUrl, hitUrlA)
        val outcomes = orderedUrls.map { url ->
            val chain = FakeChain(request(url)) { req ->
                success(req, if (req.data is File) DataSource.DISK else DataSource.NETWORK)
            }
            interceptor.intercept(chain) as SuccessResult
        }

        assertEquals(
            listOf(DataSource.DISK, DataSource.NETWORK, DataSource.DISK, DataSource.NETWORK, DataSource.DISK),
            outcomes.map { it.dataSource },
        )
        assertEquals(store.fileFor(manifestA), outcomes[0].request.data)
        assertEquals(missUrl, outcomes[1].request.data)
        assertEquals(store.fileFor(manifestB), outcomes[2].request.data)
        assertEquals(missUrl, outcomes[3].request.data)
        assertEquals(store.fileFor(manifestA), outcomes[4].request.data)
    }

    private companion object {
        // A real, minimal 1x1 transparent PNG. Embedded literally so BitmapFactory (real decoding
        // under Robolectric's native graphics mode) reports positive bounds and SteamAssetStore.write()
        // accepts it, without needing to render/compress a Bitmap through Robolectric shadows.
        val PNG_BYTES: ByteArray = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==",
        )
    }
}
