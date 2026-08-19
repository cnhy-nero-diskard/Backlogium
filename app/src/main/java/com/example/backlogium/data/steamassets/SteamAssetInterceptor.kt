package com.example.backlogium.data.steamassets

import coil.intercept.Interceptor
import coil.request.ErrorResult
import coil.request.ImageResult
import com.example.backlogium.data.local.dao.SteamAssetDao
import com.example.backlogium.data.local.entity.SteamAssetManifest
import com.example.backlogium.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Process-local Room snapshot keeps normal image requests off the database hot path. */
@Singleton
class SteamAssetLocalResolver @Inject constructor(
    private val assetDao: SteamAssetDao,
    private val store: SteamAssetStore,
    @ApplicationScope scope: CoroutineScope,
) {
    private val manifests = ConcurrentHashMap<String, SteamAssetManifest>()

    init {
        scope.launch {
            assetDao.observeAll().collect { rows ->
                manifests.clear()
                rows.filter { it.state == SteamAssetManifestState.STORED.name }
                    .forEach { manifests[it.normalizedUrl] = it }
            }
        }
    }

    suspend fun localData(url: String): Any? {
        val manifest = manifests[store.normalizedUrl(url)] ?: return null
        if (!store.isValid(manifest)) {
            assetDao.invalidate(manifest.normalizedUrl)
            return null
        }
        return store.fileFor(manifest)
    }

    suspend fun invalidate(url: String) = assetDao.invalidate(store.normalizedUrl(url))
}

/** Local-first Coil component; remote loading, cache policy, placeholders, and fallback order stay unchanged. */
@Singleton
class SteamAssetInterceptor @Inject constructor(
    private val resolver: SteamAssetLocalResolver,
) : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val url = chain.request.data as? String ?: return chain.proceed(chain.request)
        if (!url.startsWith("https://") || !isSteamAssetUrl(url)) return chain.proceed(chain.request)
        val local = resolver.localData(url) ?: return chain.proceed(chain.request)
        val localResult = chain.proceed(chain.request.newBuilder().data(local).build())
        return if (localResult is ErrorResult) {
            resolver.invalidate(url)
            chain.proceed(chain.request)
        } else {
            localResult
        }
    }

    private fun isSteamAssetUrl(url: String): Boolean =
        url.contains("steamcommunity.com/") || url.contains("steamstatic.com/")
}
