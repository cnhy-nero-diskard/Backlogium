package com.example.backlogium.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.example.backlogium.data.local.entity.SteamAssetDownloadState
import com.example.backlogium.data.local.entity.SteamAssetManifest
import kotlinx.coroutines.flow.Flow

@Dao
interface SteamAssetDao {
    @Query("SELECT * FROM steam_asset_manifest WHERE normalizedUrl = :url LIMIT 1")
    suspend fun get(url: String): SteamAssetManifest?

    @Query("SELECT * FROM steam_asset_manifest")
    fun observeAll(): Flow<List<SteamAssetManifest>>

    @Query("SELECT * FROM steam_asset_manifest")
    suspend fun getAll(): List<SteamAssetManifest>

    @Upsert suspend fun upsert(manifest: SteamAssetManifest)

    @Query("DELETE FROM steam_asset_manifest WHERE normalizedUrl = :url")
    suspend fun invalidate(url: String)

    @Query("SELECT COUNT(*) AS count, COALESCE(SUM(byteCount), 0) AS bytes FROM steam_asset_manifest WHERE state = 'STORED'")
    fun observeStoredSummary(): Flow<SteamAssetStoredSummary>

    @Query("SELECT * FROM steam_asset_download_state WHERE id = 0")
    fun observeLastRun(): Flow<SteamAssetDownloadState?>

    @Upsert suspend fun saveLastRun(state: SteamAssetDownloadState)
}

data class SteamAssetStoredSummary(val count: Int, val bytes: Long)
