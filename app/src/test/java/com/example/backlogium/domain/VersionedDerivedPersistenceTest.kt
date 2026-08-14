package com.example.backlogium.domain

import com.example.backlogium.gamification.RuleConfig
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionedDerivedPersistenceTest {

    @Test
    fun configChangeBetweenComputeAndCheckRefusesStaleValueAndRetries() = runTest {
        val first = VersionedRuleConfig(RuleConfig(xpPerMinute = 1), version = 1L)
        val second = VersionedRuleConfig(RuleConfig(xpPerMinute = 2), version = 2L)
        var current = first
        val computed = mutableListOf<RuleConfig>()
        val persisted = mutableListOf<Pair<Int, Long>>()
        var changed = false

        persistVersionChecked(
            initial = first,
            readCurrent = { current },
            compute = { config ->
                computed += config
                if (!changed) {
                    changed = true
                    current = second
                }
                config.xpPerMinute
            },
            persist = { value, version -> persisted += value to version },
            recomputeLatest = { error("the second version should converge before fallback") },
        )

        assertEquals(listOf(1, 2), computed.map { it.xpPerMinute })
        assertEquals(listOf(2 to 2L), persisted)
        assertTrue("the stale version was never persisted", persisted.none { it.second == 1L })
    }
}
