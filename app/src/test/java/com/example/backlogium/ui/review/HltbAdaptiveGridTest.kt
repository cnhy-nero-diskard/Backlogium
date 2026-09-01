package com.example.backlogium.ui.review

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the production candidate-grid adaptive column count
 * (`GridCells.Adaptive(280.dp)` semantics used by [HltbReviewScreen]'s
 * `AdaptiveCandidateGrid`): one column on narrow viewports, additional
 * minimum-width columns on wider ones, never fewer than one. The 12dp gap
 * between columns counts against the available width, so every column keeps
 * its 280dp minimum: `n * 280 + (n - 1) * 12 <= width`.
 */
class HltbAdaptiveGridTest {

    @Test
    fun narrowViewport_usesSingleColumn() {
        assertEquals(1, adaptiveColumnCount(240.dp))
        assertEquals(1, adaptiveColumnCount(279.dp))
        assertEquals(1, adaptiveColumnCount(280.dp))
    }

    @Test
    fun spacingBetweenColumns_countsAgainstTheWidth() {
        // Two columns need 2 * 280 + 12 = 572dp; 560dp would leave 274dp per cell.
        assertEquals(1, adaptiveColumnCount(560.dp))
        assertEquals(2, adaptiveColumnCount(572.dp))
        // Three columns need 3 * 280 + 2 * 12 = 864dp; 840dp would leave 272dp per cell.
        assertEquals(2, adaptiveColumnCount(840.dp))
        assertEquals(3, adaptiveColumnCount(864.dp))
    }

    @Test
    fun widerViewport_addsMinimumWidthColumns() {
        assertEquals(2, adaptiveColumnCount(600.dp))
    }

    @Test
    fun degenerateWidth_neverReturnsZeroColumns() {
        assertEquals(1, adaptiveColumnCount(0.dp))
    }
}
