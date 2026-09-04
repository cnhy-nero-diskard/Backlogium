package com.example.backlogium.domain

/**
 * How the app came to track a game.
 *
 * The set is deliberately closed and every branch on it is written as an exhaustive `when` with no
 * `else`, so adding a third value (non-Steam titles, when a sensor for them exists) surfaces as a
 * compile error at each decision point rather than silently taking an owned-game default. Two of
 * those decisions are load-bearing: which session mechanism applies to a game
 * ([PresenceSessionDeriver] versus [SessionDiffer]) and whether removal is offered — both would be
 * wrong, not merely incomplete, for a value they had never been told about.
 */
enum class GameSource {
    /** Present in the player's own Steam library, so Steam reports `playtime_forever` for it. */
    STEAM_OWNED,

    /**
     * Played through Steam Family Sharing without being owned. Steam reports presence for it but
     * no playtime, so its sessions are derived from observed presence.
     */
    FAMILY_SHARED,
}

/**
 * The playtime value that is truthful for a game source in player-facing summaries.
 *
 * [manualSharedMinutes] is a family-shared game's own hours-played estimate, additive with
 * [trackedMinutes]; it defaults to 0 so every existing owned-game call site is unaffected
 * (add-shared-game-playtime-and-filter).
 */
fun GameSource.displayedPlaytimeMinutes(
    steamPlaytimeMinutes: Int,
    trackedMinutes: Int,
    manualSharedMinutes: Int = 0,
): Int = when (this) {
    GameSource.STEAM_OWNED -> steamPlaytimeMinutes
    // Wider-type sum, clamped: a legacy near-Int.MAX estimate plus tracked minutes must not wrap.
    GameSource.FAMILY_SHARED -> (trackedMinutes.toLong() + manualSharedMinutes.toLong())
        .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}
