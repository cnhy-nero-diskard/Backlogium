package com.example.backlogium.ui.gamedetail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The "Set hours played" dialog parses free text with `String.toDoubleOrNull()`, which accepts
 * `NaN`/`Infinity` — and `NaN.roundToInt()` throws while unbounded values can overflow the
 * minutes total. [parseManualHoursInput]/[manualHoursToMinutes] must reject those (plus
 * negatives and out-of-range totals) so the dialog disables Save and the ViewModel never writes
 * them (add-shared-game-playtime-and-filter).
 */
class ManualSharedHoursValidationTest {

    @Test
    fun blankInputClearsWithZero() {
        assertEquals(0, parseManualHoursInput(""))
        assertEquals(0, parseManualHoursInput("   "))
    }

    @Test
    fun validHoursConvertToMinutes() {
        assertEquals(600, parseManualHoursInput("10"))
        assertEquals(90, parseManualHoursInput("1.5"))
        assertEquals(90, manualHoursToMinutes(1.5))
        assertEquals(0, manualHoursToMinutes(0.0))
    }

    @Test
    fun pastedSpecialValuesAreRejected() {
        assertNull(parseManualHoursInput("NaN"))
        assertNull(parseManualHoursInput("Infinity"))
        assertNull(parseManualHoursInput("-Infinity"))
        assertNull(manualHoursToMinutes(Double.NaN))
        assertNull(manualHoursToMinutes(Double.POSITIVE_INFINITY))
        assertNull(manualHoursToMinutes(Double.NEGATIVE_INFINITY))
    }

    @Test
    fun negativeValuesAreRejected() {
        assertNull(parseManualHoursInput("-1"))
        assertNull(manualHoursToMinutes(-0.5))
    }

    @Test
    fun outOfRangeTotalsAreRejected() {
        assertNull(parseManualHoursInput("1e20"))
        assertNull(manualHoursToMinutes(Double.MAX_VALUE))
    }

    @Test
    fun estimatesAboveTheProductCapAreRejected() {
        // 6,000,000 minutes = 100,000 hours: beyond any plausible single-game borrowed estimate,
        // and the ceiling that keeps a valid estimate plus tracked minutes from overflowing Int.
        assertEquals(6_000_000, manualHoursToMinutes(100_000.0))
        assertNull(manualHoursToMinutes(100_001.0))
        assertNull(parseManualHoursInput("100001"))
        // The old Int.MAX_VALUE-wide ceiling is gone: near-max minutes are no longer valid input.
        assertNull(manualHoursToMinutes(Int.MAX_VALUE / 60.0))
    }

    @Test
    fun nonNumericTextIsRejected() {
        assertNull(parseManualHoursInput("abc"))
    }
}
