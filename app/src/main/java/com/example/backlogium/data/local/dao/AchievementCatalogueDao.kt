package com.example.backlogium.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.example.backlogium.data.local.entity.AchievementCatalogueEntry
import com.example.backlogium.data.local.entity.AchievementCatalogueCache
import kotlinx.coroutines.flow.Flow

@Dao
interface AchievementCatalogueDao {
    @Query("SELECT * FROM achievement_catalogue WHERE appId = :appId ORDER BY apiName")
    fun observeEntries(appId: Long): Flow<List<AchievementCatalogueEntry>>

    @Query("SELECT * FROM achievement_catalogue WHERE appId = :appId ORDER BY apiName")
    suspend fun entries(appId: Long): List<AchievementCatalogueEntry>

    @Query("SELECT * FROM achievement_catalogue_cache WHERE appId = :appId")
    fun observeCache(appId: Long): Flow<AchievementCatalogueCache?>

    @Query("SELECT * FROM achievement_catalogue_cache WHERE appId = :appId")
    suspend fun cache(appId: Long): AchievementCatalogueCache?

    @Upsert suspend fun upsert(entries: List<AchievementCatalogueEntry>)
    @Upsert suspend fun upsert(cache: AchievementCatalogueCache)

    @Query("DELETE FROM achievement_catalogue WHERE appId = :appId")
    suspend fun clearEntries(appId: Long)

    @Query("DELETE FROM achievement_catalogue")
    suspend fun clearEntries()

    @Query("DELETE FROM achievement_catalogue_cache")
    suspend fun clearCache()
}
