package com.example.backlogium.domain

import com.example.backlogium.data.remote.dto.PlayerSummaryDto

/** The player's Steam identity as persisted on the profile. */
data class PlayerIdentity(
    val personaName: String?,
    val avatarUrl: String?,
)

/**
 * Merge whatever identity a `GetPlayerSummaries` response exposed over the stored values.
 *
 * A null [summary] (request failed or returned no players) or a blank field leaves the
 * corresponding stored value untouched, so a private profile or a transient error never
 * downgrades a header that was previously populated. Both writers — the periodic sync and the
 * live poll — go through here so they agree on that rule.
 */
fun mergePlayerIdentity(summary: PlayerSummaryDto?, stored: PlayerIdentity): PlayerIdentity =
    PlayerIdentity(
        personaName = summary?.personaName?.takeIf { it.isNotBlank() } ?: stored.personaName,
        avatarUrl = summary?.avatarFull?.takeIf { it.isNotBlank() } ?: stored.avatarUrl,
    )
