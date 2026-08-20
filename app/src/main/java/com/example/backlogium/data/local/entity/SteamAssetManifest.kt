package com.example.backlogium.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Durable index for an app-private Steam CDN image. The bytes live outside Room. */
@Entity(tableName = "steam_asset_manifest")
data class SteamAssetManifest(
    @PrimaryKey val normalizedUrl: String,
    val kind: String,
    val relativePath: String? = null,
    val byteCount: Long = 0L,
    val checksum: String? = null,
    val state: String,
    val lastSuccessAt: Long? = null,
    val lastCheckedAt: Long,
)

/** Singleton record retained after a batch completes, independent from WorkManager's transient output. */
@Entity(tableName = "steam_asset_download_state")
data class SteamAssetDownloadState(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val mode: String,
    val completedAt: Long,
    val storedCount: Int,
    val alreadyPresentCount: Int,
    val unavailableCount: Int,
    val failedCount: Int,
) {
    companion object { const val SINGLETON_ID = 0 }
}
