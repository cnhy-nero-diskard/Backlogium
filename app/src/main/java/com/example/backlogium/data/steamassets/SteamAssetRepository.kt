package com.example.backlogium.data.steamassets

import com.example.backlogium.data.local.dao.SteamAssetDao
import com.example.backlogium.data.local.entity.SteamAssetDownloadState
import com.example.backlogium.data.local.entity.SteamAssetManifest
import com.example.backlogium.data.remote.SteamIconMapper
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SteamAssetRepository @Inject constructor(
    private val assetDao: SteamAssetDao,
    private val store: SteamAssetStore,
    private val client: OkHttpClient,
) {
    suspend fun inventory(): List<SteamAssetInventoryItem> {
        val all = linkedMapOf<String, SteamAssetInventoryItem>()
        fun add(url: String?, kind: SteamAssetKind) {
            val normalized = url?.trim().orEmpty()
            if (normalized.isNotEmpty()) all.putIfAbsent(store.normalizedUrl(normalized), SteamAssetInventoryItem(normalized, kind))
        }
        add(assetDao.profileAvatarUrl(), SteamAssetKind.AVATAR)
        assetDao.gameImageSources().forEach { game ->
            add(game.iconUrl, SteamAssetKind.GAME_ICON)
            add(SteamIconMapper.headerUrl(game.appId), SteamAssetKind.HEADER)
            add(SteamIconMapper.heroCapsuleUrl(game.appId), SteamAssetKind.HERO_CAPSULE)
            add(SteamIconMapper.libraryHeroUrl(game.appId), SteamAssetKind.LIBRARY_HERO)
            add(SteamIconMapper.libraryCapsuleUrl(game.appId), SteamAssetKind.LIBRARY_CAPSULE)
            add(SteamIconMapper.wideCapsuleUrl(game.appId), SteamAssetKind.WIDE_CAPSULE)
        }
        assetDao.achievementIconUrls().forEach { add(it, SteamAssetKind.ACHIEVEMENT) }
        return all.values.toList()
    }

    suspend fun run(
        mode: SteamAssetDownloadMode,
        startedAt: Long,
        onProgress: suspend (processed: Int, total: Int, label: String, counts: SteamAssetRunCounts) -> Unit,
    ): SteamAssetRunCounts = coroutineScope {
        store.deleteTemporaryFiles()
        assetDao.getAll()
            .filter { it.state == SteamAssetManifestState.STORED.name && !store.isValid(it) }
            .forEach { assetDao.invalidate(it.normalizedUrl) }
        val items = inventory()
        val permits = Semaphore(MAX_CONCURRENCY)
        var counts = SteamAssetRunCounts()
        var processed = 0
        val lock = Mutex()
        items.map { item -> async {
            val outcome = permits.withPermit { process(item, mode, startedAt) }
            lock.withLock {
                processed += 1
                counts = counts.plus(outcome)
                onProgress(processed, items.size, item.url, counts)
            }
        } }.awaitAll()
        assetDao.saveLastRun(
            SteamAssetDownloadState(
                mode = mode.name,
                completedAt = System.currentTimeMillis(),
                storedCount = counts.stored,
                alreadyPresentCount = counts.alreadyPresent,
                unavailableCount = counts.unavailable,
                failedCount = counts.failed,
            ),
        )
        counts
    }

    private suspend fun process(item: SteamAssetInventoryItem, mode: SteamAssetDownloadMode, startedAt: Long): SteamAssetOutcome {
        val normalized = store.normalizedUrl(item.url)
        val existing = assetDao.get(normalized)
        if (shouldSkip(existing, mode, startedAt)) return existingOutcome(existing)
        return try {
            client.newCall(Request.Builder().url(item.url).build()).execute().use { response ->
                val now = System.currentTimeMillis()
                when (response.code) {
                    404, 410 -> {
                        if (existing?.state == SteamAssetManifestState.STORED.name && store.isValid(existing)) {
                            assetDao.upsert(existing.copy(lastCheckedAt = now))
                        } else {
                            assetDao.upsert(SteamAssetManifest(normalized, item.kind.name, state = SteamAssetManifestState.UNAVAILABLE.name, lastCheckedAt = now))
                        }
                        SteamAssetOutcome.UNAVAILABLE
                    }
                    in 200..299 -> {
                        val saved = store.write(item.url, response.header("Content-Type"), response.body?.bytes() ?: ByteArray(0))
                        if (saved == null) SteamAssetOutcome.FAILED else {
                            assetDao.upsert(SteamAssetManifest(normalized, item.kind.name, saved.relativePath, saved.bytes, saved.checksum, SteamAssetManifestState.STORED.name, now, now))
                            SteamAssetOutcome.STORED
                        }
                    }
                    else -> SteamAssetOutcome.FAILED
                }
            }
        } catch (_: Exception) {
            SteamAssetOutcome.FAILED
        }
    }

    private suspend fun shouldSkip(existing: SteamAssetManifest?, mode: SteamAssetDownloadMode, startedAt: Long): Boolean {
        if (existing == null) return false
        if (mode == SteamAssetDownloadMode.REFRESH_ALL) return existing.lastCheckedAt >= startedAt
        return when (existing.state) {
            SteamAssetManifestState.STORED.name -> store.isValid(existing)
            SteamAssetManifestState.UNAVAILABLE.name -> System.currentTimeMillis() - existing.lastCheckedAt < UNAVAILABLE_FRESHNESS_MS
            else -> false
        }
    }

    private fun existingOutcome(existing: SteamAssetManifest?): SteamAssetOutcome =
        if (existing?.state == SteamAssetManifestState.UNAVAILABLE.name) SteamAssetOutcome.UNAVAILABLE else SteamAssetOutcome.ALREADY_PRESENT

    companion object {
        const val MAX_CONCURRENCY = 4
        const val UNAVAILABLE_FRESHNESS_MS = 30L * 24 * 60 * 60 * 1000
    }
}
