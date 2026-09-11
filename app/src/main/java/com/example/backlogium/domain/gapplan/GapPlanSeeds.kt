package com.example.backlogium.domain.gapplan

/**
 * Where a generation's seed comes from.
 *
 * A seam rather than a call to `Random.nextLong()` inside the ViewModel, because the two properties
 * that matter about seeding are only testable if the seeds can be dictated: that identical inputs
 * and an identical seed produce identical picks, and that rebuilding draws a *different* seed and
 * therefore a different set. A test that could only observe real randomness would have to assert
 * "probably different", which is not an assertion.
 */
fun interface GapPlanSeeds {
    fun next(): Long
}
