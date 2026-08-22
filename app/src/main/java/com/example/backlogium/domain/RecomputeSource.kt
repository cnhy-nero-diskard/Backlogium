package com.example.backlogium.domain

/**
 * Why a set of derived gamification values is being persisted.
 *
 * Only [SYNC] represents progress earned through play. The remaining sources may legitimately
 * move derived values in either direction, but they redefine the presentation baseline rather
 * than producing player-facing progress events.
 */
enum class RecomputeSource {
    SYNC,
    RULE_CHANGE,
    BACKFILL,
    RESTORE,

    /**
     * A game was hidden or unhidden (add-hidden-games). Not earned, so it emits no progress
     * events and reseeds the baseline — **including downward**, which is the point: hiding a
     * heavily-played game lowers the level, and a stale high-water mark left behind would
     * swallow the next genuine level-up.
     */
    VISIBILITY_CHANGE,
}
