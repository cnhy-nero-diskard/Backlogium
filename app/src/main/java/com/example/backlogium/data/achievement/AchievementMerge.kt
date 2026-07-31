package com.example.backlogium.data.achievement

import com.example.backlogium.data.local.entity.Achievement
import com.example.backlogium.data.remote.dto.AchievementSchemaDto
import com.example.backlogium.data.remote.dto.PlayerAchievementDto

/**
 * Pure merge rule for one fetched achievement, given any [prior] stored row. No I/O — kept
 * separate from [com.example.backlogium.data.repo.AchievementRepository] so the rarity-snapshot
 * rule is unit-testable directly.
 */
object AchievementMerge {

    /**
     * Merges a freshly fetched achievement into its stored row.
     *
     * The rarity snapshot rule (resolves the deferred `add-achievement-xp` / `add-gamification-engine`
     * open question): once [Achievement.snapshotPercent] is set it is never overwritten — it
     * freezes the tier at the moment of accomplishment. If [prior] has no snapshot yet and the
     * achievement is unlocked, the snapshot is backfilled from the currently known
     * [globalPercent] (which may itself still be null, leaving the snapshot null until a sync
     * observes both "unlocked" and a known percent together). [globalPercent] (the live value,
     * for display) is refreshed on every merge regardless.
     */
    fun merge(
        appId: Long,
        dto: PlayerAchievementDto,
        globalPercent: Double?,
        schema: AchievementSchemaDto?,
        prior: Achievement?,
        now: Long,
    ): Achievement {
        val unlocked = dto.achieved != 0
        val snapshotPercent = prior?.snapshotPercent ?: (if (unlocked) globalPercent else null)
        return Achievement(
            appId = appId,
            apiName = dto.apiName,
            displayName = schema?.displayName?.takeIf { it.isNotBlank() } ?: prior?.displayName,
            iconUrl = schema?.icon?.takeIf { it.isNotBlank() } ?: prior?.iconUrl,
            unlocked = unlocked,
            unlockedAt = dto.unlocktime.takeIf { it > 0L },
            globalPercent = globalPercent ?: prior?.globalPercent,
            snapshotPercent = snapshotPercent,
            fetchedAt = now,
        )
    }
}
