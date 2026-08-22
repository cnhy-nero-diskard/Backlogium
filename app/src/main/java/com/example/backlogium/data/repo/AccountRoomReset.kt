package com.example.backlogium.data.repo

import androidx.room.withTransaction
import com.example.backlogium.data.local.BacklogiumDatabase
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The account-owned Room reset. Keeping this transaction separate from credential promotion
 * makes the durable account-change protocol directly testable and keeps retry behavior explicit.
 */
@Singleton
class AccountRoomReset @Inject constructor(
    private val database: BacklogiumDatabase,
) {
    suspend fun resetForAccountChange(steamId: String) {
        database.withTransaction {
            database.sessionDao().deleteAll()
            database.achievementDao().deleteAll()
            database.gameGenreCacheDao().deleteAll()
            database.gameAchievementSyncDao().deleteAll()
            database.collectionDao().deleteAllMembers()
            // A hide is a statement about one account's library. Carrying it across would hide
            // whatever the new account happens to own under the same app ids.
            database.hiddenGameDao().deleteAll()
            database.collectionDao().deleteAll()
            database.dailyProgressDao().deleteAll()
            database.diagnosticsDao().deleteAll()
            database.gameDao().deleteAll()
            database.playerProfileDao().insertIfMissing()
            // longestStreak is a historical fact only within one Steam identity. Carrying it to
            // another person would attribute the old account's record to the new account.
            database.playerProfileDao().resetForAccountChange(steamId)
        }
    }
}
