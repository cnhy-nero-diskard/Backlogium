package com.example.backlogium.domain

import com.example.backlogium.gamification.RuleConfig

/**
 * Compute derived values under one rule-config version, refusing a candidate when the version
 * moves around its write. The caller-owned raw Room commit is intentionally outside this helper;
 * callers can therefore retry derived state without replaying or losing observed playtime.
 */
internal suspend fun <T> persistVersionChecked(
    initial: VersionedRuleConfig,
    readCurrent: suspend () -> VersionedRuleConfig,
    compute: suspend (RuleConfig) -> T,
    persist: suspend (value: T, version: Long) -> Unit,
    coordinator: DerivedStateWriteCoordinator,
) {
    coordinator.withLock {
        var candidate = initial
        repeat(MAX_ATTEMPTS) {
            val value = compute(candidate.config)
            val beforePersist = readCurrent()
            if (beforePersist.version != candidate.version) {
                candidate = beforePersist
                return@repeat
            }

            persist(value, candidate.version)
            val afterPersist = readCurrent()
            if (afterPersist.version == candidate.version) {
                return@withLock
            }
            // A caller outside this coordinator may have changed the rules while the Room write
            // was in flight. Recompute from that version; never invoke an unguarded fallback.
            candidate = afterPersist
        }

        // A continuously changing settings source must fail closed rather than commit a result
        // whose provenance is unknown. The worker/use case can retry the whole derived write.
        error("Unable to persist gamification for a stable rule-config version")
    }
}

private const val MAX_ATTEMPTS = 3
