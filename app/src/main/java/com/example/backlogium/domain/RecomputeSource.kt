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
}
