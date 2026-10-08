package com.example.backlogium.ui.library

import org.junit.Assert.*
import org.junit.Test

class LibraryVisitStateTest {
    private fun active() = LibraryVisitState().apply {
        routeChanged(true, 0)
        filters.value = LibraryFilters("portal", setOf("puzzle"), true, true)
        captureScroll(LibraryScrollAnchor(gameId = 42, index = 8, offset = 17), 0)
    }

    @Test fun exactThresholdExpiresButOneMillisecondEarlierRetains() {
        for (elapsed in listOf(299_999L, 300_000L)) {
            val visit = active()
            visit.routeChanged(false, 100)
            visit.routeChanged(true, 100 + elapsed)
            assertEquals(elapsed == 300_000L, !visit.filters.value.hasActiveFilters)
            assertEquals(if (elapsed == 300_000L) 1L else 0L, visit.generation.value)
            assertEquals(elapsed == 300_000L, visit.scrollAnchor == null)
            assertNull(visit.absenceStartedAt)
        }
    }

    @Test fun foregroundChildrenHaveNoTimeout() {
        val visit = active()
        visit.routeChanged(true, 900_000)
        assertEquals("portal", visit.filters.value.query)
        assertNull(visit.absenceStartedAt)
    }

    @Test fun backgroundFromLibraryOrChildExpires() {
        val visit = active()
        visit.background(50)
        visit.foreground(300_050)
        assertFalse(visit.filters.value.hasActiveFilters)
    }

    @Test fun backgroundNeverReplacesEarlierTabDepartureOrResumesOtherTab() {
        val visit = active()
        visit.routeChanged(false, 100)
        visit.background(200_000)
        visit.foreground(250_000)
        assertEquals(100L, visit.absenceStartedAt)
        visit.routeChanged(true, 300_100)
        assertEquals(1L, visit.generation.value)
    }

    @Test fun eachTimelyReturnStartsANewInterval() {
        val visit = active()
        visit.routeChanged(false, 100)
        visit.routeChanged(true, 299_000)
        visit.routeChanged(false, 400_000)
        visit.routeChanged(true, 699_999)
        assertEquals("portal", visit.filters.value.query)
        assertEquals(0L, visit.generation.value)
    }

    @Test fun newProcessStartsFreshAndStaleCaptureCannotUndoExpiry() {
        val visit = active()
        visit.background(0)
        visit.foreground(300_000)
        visit.captureScroll(LibraryScrollAnchor(gameId = 42), 0)
        assertNull(visit.scrollAnchor)
        assertEquals(LibraryFilters(), LibraryVisitState().filters.value)
    }

    @Test fun configurationReattachmentKeepsRunningAbsence() {
        val visit = active()
        visit.routeChanged(false, 100)
        visit.routeChanged(false, 200)
        assertEquals(100L, visit.absenceStartedAt)
    }
}
