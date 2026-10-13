package com.example.backlogium.data.remote.dto

/**
 * Validation of Steam's playtime payloads at the boundary between the wire format and ingestion.
 *
 * The two cumulative fields are not the same when they are absent:
 *
 * - `playtime_forever` is reported for every owned game, so an omitted value is an unreadable
 *   reading rather than zero. Treating it as zero would seed a fresh baseline at zero or look like
 *   a regression against the stored total. A negative count is impossible and is rejected.
 * - `playtime_2weeks` is **omitted** when the game was not played in the last two weeks — that
 *   absence is genuinely zero. A **negative** count, however, is malformed, not a statement of
 *   "no play", and reads as null so a caller keeps stored recent-play metadata instead of
 *   overwriting it with a fabricated zero.
 *
 * The envelope validators distinguish an unconfirmed/private or contradictory response from a
 * confirmed library reading, and the `validated*Games` helpers keep only the rows ingestion may
 * commit: positive identity, a usable cumulative total, and no conflicting duplicate app ids.
 */

/** The cumulative lifetime minutes Steam reported, or null when omitted or negative. */
val OwnedGameDto.validatedPlaytimeForever: Int?
    get() = playtimeForever?.takeIf { it >= 0 }

/** [OwnedGameDto.playtimeForever]'s recently-played counterpart, with the same absence rule. */
val RecentlyPlayedGameDto.validatedPlaytimeForever: Int?
    get() = playtimeForever?.takeIf { it >= 0 }

/**
 * Minutes played in the last two weeks. Absent means Steam did not report recent play and reads as
 * zero; negative is malformed and reads as null, never as a genuine "not played recently" value.
 */
val OwnedGameDto.validatedPlaytime2Weeks: Int?
    get() = when {
        playtime2Weeks == null -> 0
        playtime2Weeks >= 0 -> playtime2Weeks
        else -> null
    }

/** [OwnedGameDto.validatedPlaytime2Weeks] for the recently-played payload. */
val RecentlyPlayedGameDto.validatedPlaytime2Weeks: Int?
    get() = when {
        playtime2Weeks == null -> 0
        playtime2Weeks >= 0 -> playtime2Weeks
        else -> null
    }

/**
 * Whether an owned-games envelope is a confirmed library reading that may be persisted.
 *
 * Steam answers a private/unreadable profile with an empty `response` object and no `game_count`;
 * only a present nonnegative count confirms the endpoint answered. An empty list persists only for
 * an explicit `game_count: 0`; a positive count with an empty list and a zero count with games
 * present are both contradictory and must leave last-good data in place. Per-game omissions are
 * handled independently: missing members never become zero readings (see [validatedOwnedGames]).
 */
fun isConfirmedOwnedEnvelope(gameCount: Int?, gamesAreEmpty: Boolean): Boolean =
    gameCount != null && gameCount >= 0 && gamesAreEmpty == (gameCount == 0)

/**
 * Whether a recently-played envelope is a confirmed reading. The requested window is bounded, so a
 * positive `total_count` may legitimately exceed the number of games returned; a positive total
 * with no games returned, a zero total with games present, or an absent/negative total are
 * unconfirmed and must never author an observation.
 */
fun isConfirmedRecentEnvelope(totalCount: Int?, gamesAreEmpty: Boolean): Boolean =
    totalCount != null && totalCount >= 0 && gamesAreEmpty == (totalCount == 0)

/**
 * The owned games ingestion may commit: rows with a positive [OwnedGameDto.appid], a usable
 * cumulative total, and no conflicting duplicate. A duplicate app id whose rows disagree on the
 * lifetime total is untrustworthy and is excluded entirely; identical duplicates collapse to one
 * row. Rows that fail (an omitted field, for instance) keep their stored baseline and metadata.
 */
fun List<OwnedGameDto>.validatedOwnedGames(): List<OwnedGameDto> =
    groupBy { it.appid }
        .values
        .mapNotNull { rows ->
            val totals = rows.mapNotNull { it.validatedPlaytimeForever }.distinct()
            totals.singleOrNull()?.let { total ->
                rows.firstOrNull { it.validatedPlaytimeForever == total }
            }
        }
        .filter { it.appid > 0 }

/** [validatedOwnedGames] for the recently-played payload. */
fun List<RecentlyPlayedGameDto>.validatedRecentGames(): List<RecentlyPlayedGameDto> =
    groupBy { it.appid }
        .values
        .mapNotNull { rows ->
            val totals = rows.mapNotNull { it.validatedPlaytimeForever }.distinct()
            totals.singleOrNull()?.let { total ->
                rows.firstOrNull { it.validatedPlaytimeForever == total }
            }
        }
        .filter { it.appid > 0 }
