package com.example.backlogium.data.repo

import androidx.room.withTransaction
import com.example.backlogium.data.local.BacklogiumDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Confirmed player evidence only; catalogue entries never imply progress. */
data class DatedAchievementEvidence(val appId: Long, val apiName: String, val name: String, val at: Long, val rarityPercent: Double?)
data class DatedCompletionEvidence(val appId: Long, val observedAt: Long)
data class AchievementEvidenceSnapshot(
    val unlocks: List<DatedAchievementEvidence> = emptyList(),
    val completions: List<DatedCompletionEvidence> = emptyList(),
    val unsupportedUnlocks: Int = 0,
    val unsupportedCompletions: Int = 0,
)

@Singleton
class AchievementInsightsRepository @Inject constructor(private val database: BacklogiumDatabase) {
    /** A bounded indexed read and coverage aggregates from one account-fenced Room snapshot. */
    fun between(account: String, start: Long, end: Long): Flow<AchievementEvidenceSnapshot> =
        database.invalidationTracker.createFlow("achievements", "game_achievement_sync", "hidden_games", "player_profile").map {
            database.withTransaction {
                val dao = database.achievementInsightsDao()
                AchievementEvidenceSnapshot(
                    dao.unlocks(account, start, end).map { DatedAchievementEvidence(it.appId, it.apiName, it.name, it.unlockedAt, it.snapshotPercent) },
                    dao.completions(account, start, end).map { DatedCompletionEvidence(it.appId, it.completedAt) },
                    dao.unsupportedUnlocks(account), dao.unsupportedCompletions(account),
                )
            }
        }
}
