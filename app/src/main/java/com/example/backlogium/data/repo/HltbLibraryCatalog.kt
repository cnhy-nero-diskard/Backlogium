package com.example.backlogium.data.repo

import com.example.backlogium.data.local.dao.GameDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Narrow current-library seam used for dataset application summaries. */
fun interface HltbLibraryCatalog {
    suspend fun appIds(): Set<Long>

    /** Reactive library scope for derived dataset coverage; the default keeps test seams tiny. */
    fun observeAppIds(): Flow<Set<Long>> = flowOf(emptySet())
}

@Singleton
class RoomHltbLibraryCatalog @Inject constructor(
    private val gameDao: GameDao,
) : HltbLibraryCatalog {
    override suspend fun appIds(): Set<Long> = gameDao.getAll().mapTo(mutableSetOf()) { it.appId }

    override fun observeAppIds(): Flow<Set<Long>> = gameDao.observeAppIds().map { it.toSet() }
}
