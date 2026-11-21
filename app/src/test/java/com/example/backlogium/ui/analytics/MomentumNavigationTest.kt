package com.example.backlogium.ui.analytics

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class MomentumNavigationTest {
    @Test fun hiddenRemovedAndReplacedAccountTargetsNeverOpenButVisibleTargetDoes() = runTest {
        var account = "a"
        val opened = mutableListOf<Long>()
        assertFalse(openMomentumDetail(1, "a", { account }, { false }, { true }, opened::add))
        assertFalse(openMomentumDetail(1, "a", { account }, { true }, { false }, opened::add))
        assertFalse(openMomentumDetail(1, "a", { account }, { account = "b"; true }, { true }, opened::add))
        assertTrue(opened.isEmpty())
        assertTrue(openMomentumDetail(1, "b", { account }, { true }, { true }, opened::add))
        assertEquals(listOf(1L), opened)
    }
}
