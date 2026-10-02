package com.example.backlogium.data.repo

import androidx.room.withTransaction
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.domain.CollectionPicker
import com.example.backlogium.domain.CollectionPickerTarget
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

/** Direct local membership actions, independent of any buffered editor draft. */
@Singleton
class CollectionMembershipRepository @Inject constructor(
    private val database: BacklogiumDatabase,
    private val identity: AccountDataWriteGuard,
) {
    fun picker(appId: Long): Flow<CollectionPicker> = combine(
        database.collectionDao().observeCollections(), database.collectionDao().observeAllMembers(),
        database.playerProfileDao().observe(),
    ) { collections, members, profile ->
        val existing = members.filter { it.appId == appId }.mapTo(mutableSetOf()) { it.collectionId }
        CollectionPicker(profile?.steamId.orEmpty(), collections.map {
            CollectionPickerTarget(it.id, it.name, it.id in existing)
        })
    }

    suspend fun add(collectionId: Long, appId: Long, steamId: String): Boolean = database.withTransaction {
        checkTarget(collectionId, appId, steamId)
        val dao = database.collectionDao()
        if (dao.getMembers(collectionId).any { it.appId == appId }) false else {
            dao.addMember(collectionId, appId)
            true
        }
    }

    suspend fun remove(collectionId: Long, appId: Long, steamId: String): Boolean = database.withTransaction {
        checkTarget(collectionId, appId, steamId)
        val dao = database.collectionDao()
        if (dao.getMembers(collectionId).none { it.appId == appId }) false else {
            dao.removeMember(collectionId, appId)
            dao.getMembers(collectionId).forEachIndexed { index, member ->
                dao.setOrderIndex(collectionId, member.appId, index)
            }
            true
        }
    }

    private suspend fun checkTarget(collectionId: Long, appId: Long, steamId: String) {
        identity.check(steamId)
        require(database.collectionDao().getById(collectionId) != null) {
            "Collection is no longer available. Refresh and retry."
        }
        require(database.gameDao().getById(appId) != null && !database.hiddenGameDao().isHidden(appId)) {
            "Game is no longer available. Refresh and retry."
        }
    }
}
