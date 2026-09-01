package com.example.backlogium.ui.review

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the production candidate-grid adaptive column count
 * (`GridCells.Adaptive(280.dp)` semantics used by [HltbReviewScreen]'s
 * `AdaptiveCandidateGrid`): one column on narrow viewports, additional
 * minimum-width columns on wider ones, never fewer than one.
 */
class HltbAdaptiveGridTest {

    @Test
    fun narrowViewport_usesSingleColumn() {
        assertEquals(1, adaptiveColumnCount(240.dp))
        assertEquals(1, adaptiveColumnCount(279.dp))
    }

    @Test
    fun exactlyOneMinimumColumn_fitsSingleColumn() {
        assertEquals(1, adaptiveColumnCount(280.dp))
    }

    @Test
    fun widerViewport_addsMinimumWidthColumns() {
        assertEquals(2, adaptiveColumnCount(560.dp))
        assertEquals(2, adaptiveColumnCount(600.dp))
        assertEquals(3, adaptiveColumnCount(840.dp))
    }

    @Test
    fun degenerateWidth_neverReturnsZeroColumns() {
        assertEquals(1, adaptiveColumnCount(0.dp))
    }
}
