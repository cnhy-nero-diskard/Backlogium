package com.example.backlogium.domain

import com.example.backlogium.gamification.RuleConfig

/**
 * Compute derived values under one rule-config version, refusing a candidate when the version
 * moved before its write. The raw Room commit is intentionally outside this helper; callers can
 * therefore retry derived state without replaying or losing observed playtime.
 */
internal suspend fun <T> persistVersionChecked(
    initial: VersionedRuleConfig,
    readCurrent: suspend () -> VersionedRuleConfig,
    compute: suspend (RuleConfig) -> T,
    persist: suspend (value: T, version: Long) -> Unit,
    recomputeLatest: suspend (VersionedRuleConfig) -> Unit,
) {
    var candidate = initial
    repeat(3) {
        val value = compute(candidate.config)
        val current = readCurrent()
        if (current.version == candidate.version) {
            persist(value, candidate.version)
            return
        }
        candidate = current
    }

    // A continuously edited settings screen is unusual, but never write an old candidate after
    // repeated mismatches. The latest recompute owns the final version check policy of its caller.
    recomputeLatest(readCurrent())
}
