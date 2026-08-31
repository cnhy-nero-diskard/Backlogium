package com.example.backlogium.data.repo

import com.example.backlogium.data.local.dao.GameDao
import javax.inject.Inject
import javax.inject.Singleton

/** Narrow current-library seam used for dataset application summaries. */
fun interface HltbLibraryCatalog {
    suspend fun appIds(): Set<Long>
}

@Singleton
class RoomHltbLibraryCatalog @Inject constructor(
    private val gameDao: GameDao,
) : HltbLibraryCatalog {
    override suspend fun appIds(): Set<Long> = gameDao.getAll().mapTo(mutableSetOf()) { it.appId }
}
