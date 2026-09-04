package com.example.backlogium.data.achievement

import com.example.backlogium.domain.GameSource

/**
 * Pure tier selection for the achievement sync, unit-testable without Room, network, or
 * WorkManager.
 *
 * Tiers classification uses free signals already present in the sync:
 * - HOT: playtime increased since the last sync (`playtimeDelta > 0`)
 * - WARM: played in the last two weeks (`playtime2Weeks > 0`)
 * - COLD: played at some point, but not hot or warm
 * - NEVER: no recorded playtime (`playtimeForever == 0`)
 *
 * Inline sync refreshes hot + warm games, plus a bounded number of cold/never games that have no
 * stored achievement data at all. COLD games are otherwise deferred to the reconciliation pass.
 * NEVER games are never fetched.
 *
 * That NEVER rule assumes `playtimeForever` is Steam's own figure, which is true for an owned
 * game but not for a [GameSource.FAMILY_SHARED] one: its `playtimeForever` input is locally
 * tracked session minutes (Steam reports no owned-library playtime for a game the player doesn't
 * own), which only reflects what Backlogium itself observed and can be zero for a game that was
 * played, or even completed, before it was ever admitted or while presence monitoring was off. A
 * family-shared game therefore never lands in NEVER: it is always at least cold, so zero tracked
 * minutes never excludes it from missing-data eligibility or the reconciliation pass
 * (fix-shared-game-achievement-visibility).
 */
object AchievementFreshness {

    /** Maximum cold/never games with missing data that may be fetched inline per sync. */
    const val MISSING_DATA_CAP = 25

    data class OwnedGame(
        val appId: Long,
        val playtimeForever: Long,
        val playtime2Weeks: Long,
        val source: GameSource = GameSource.STEAM_OWNED,
    )

    data class SyncMetadata(
        val appId: Long,
        val playerStateFetchedAt: Long?,
        val schemaFetchedAt: Long? = null,
    )

    data class Result(
        val hot: List<Long>,
        val warm: List<Long>,
        val cold: List<Long>,
        val never: List<Long>,
        val missingDataOverride: List<Long>,
    ) {
        /** Games that should be refreshed during the inline sync. */
        val inlineSelected: List<Long> = hot + warm + missingDataOverride
    }

    /**
     * Select which games to refresh in the inline sync.
     *
     * @param now current epoch millis; used only to timestamp the conceptual decision, not for
     *        freshness gating (tiering uses play evidence, not wall-clock staleness)
     * @param ownedGames every owned game with its playtime signals
     * @param playtimeDeltaByAppId games whose `playtimeForever` increased since the last sync
     * @param metadataByAppId stored per-game achievement sync metadata; absence means no data
     * @return tier classifications and the inline selection
     */
    fun selectByTier(
        now: Long,
        ownedGames: List<OwnedGame>,
        playtimeDeltaByAppId: Map<Long, Int>,
        metadataByAppId: Map<Long, SyncMetadata>,
    ): Result {
        val hot = mutableListOf<Long>()
        val warm = mutableListOf<Long>()
        val cold = mutableListOf<Long>()
        val never = mutableListOf<Long>()
        val missingDataEligible = mutableListOf<MissingDataCandidate>()

        for (game in ownedGames) {
            when {
                playtimeDeltaByAppId[game.appId]?.let { it > 0 } == true -> {
                    hot.add(game.appId)
                }
                game.playtime2Weeks > 0 -> {
                    warm.add(game.appId)
                }
                game.playtimeForever > 0 || game.source == GameSource.FAMILY_SHARED -> {
                    cold.add(game.appId)
                    if (metadataByAppId[game.appId] == null) {
                        missingDataEligible.add(MissingDataCandidate(game.appId, null))
                    }
                }
                else -> {
                    never.add(game.appId)
                }
            }
        }

        val missingDataOverride = missingDataEligible
            .sortedWith(compareBy { it.playerStateFetchedAt ?: Long.MIN_VALUE })
            .take(MISSING_DATA_CAP)
            .map { it.appId }

        return Result(hot, warm, cold, never, missingDataOverride)
    }

    private data class MissingDataCandidate(
        val appId: Long,
        val playerStateFetchedAt: Long?,
    )
}
