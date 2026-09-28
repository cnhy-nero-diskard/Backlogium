package com.example.backlogium.ui.settings

import com.example.backlogium.data.repo.CloudPresenceRangeMetadata
import com.example.backlogium.domain.CloudPresenceHistoricalRangeResolution
import com.example.backlogium.domain.CloudPresenceHistoricalStartChoice
import com.example.backlogium.domain.CloudPresencePreDataCutoffResolution
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudHistoricalRangeControlsTest {
    @Test
    fun recentPresetUsesTheServerReadAtAndKeepsItsRollingBoundary() {
        val throughAt = instant("2025-03-10T06:30:00Z")
        val resolved = resolveCloudHistoricalRangeControls(
            controls(metadata(earliest = instant("2025-01-01T00:00:00Z"), readAt = throughAt)),
            historyImported = false,
            nowAt = instant("2025-03-10T06:00:00Z"),
            zone = ZoneId.of("America/Los_Angeles"),
        )

        val selection = resolved.selection!!
        assertEquals(throughAt, selection.throughAt)
        assertEquals(31L * 24 * 60 * 60 * 1000, selection.throughAt - selection.effectiveStartAt)
        assertEquals(CloudPresenceHistoricalStartChoice.RECENT_31_DAYS, selection.choice)
    }

    @Test
    fun customStartRejectsDatesBeforeEvidenceTodayAndInTheFuture() {
        val readAt = instant("2025-04-20T12:00:00Z")
        val base = controls(metadata(earliest = instant("2025-04-10T00:00:00Z"), readAt = readAt))
            .copy(startChoice = CloudPresenceHistoricalStartChoice.CUSTOM_LOCAL_DATE)
        val zone = ZoneId.of("UTC")
        fun resolve(date: String) = resolveCloudHistoricalRangeControls(
            base.copy(customStartDate = LocalDate.parse(date)),
            historyImported = true,
            nowAt = readAt,
            zone = zone,
        ).rangeResolution

        assertEquals(
            CloudPresenceHistoricalRangeResolution.BeforeEarliestEvidence,
            resolve("2025-04-09"),
        )
        assertEquals(
            CloudPresenceHistoricalRangeResolution.CustomDateNotInPast,
            resolve("2025-04-20"),
        )
        assertEquals(
            CloudPresenceHistoricalRangeResolution.CustomDateNotInPast,
            resolve("2025-04-21"),
        )
    }

    @Test
    fun explicitCutoffRejectsDaylightSavingGapsAndOverlaps() {
        val zone = ZoneId.of("America/New_York")
        val base = controls(
            metadata(earliest = instant("2024-01-01T00:00:00Z"), readAt = instant("2024-12-01T00:00:00Z")),
        ).copy(
            startChoice = CloudPresenceHistoricalStartChoice.CUSTOM_LOCAL_DATE,
            customStartDate = LocalDate.parse("2024-03-01"),
            transferPreDataPlay = true,
        )
        val nowAt = instant("2024-12-01T00:00:00Z")

        val gap = resolveCloudHistoricalRangeControls(
            base.copy(cutoffLocalDateTime = LocalDateTime.parse("2024-03-10T02:30:00")),
            historyImported = true,
            nowAt = nowAt,
            zone = zone,
        )
        val overlap = resolveCloudHistoricalRangeControls(
            base.copy(cutoffLocalDateTime = LocalDateTime.parse("2024-11-03T01:30:00")),
            historyImported = true,
            nowAt = nowAt,
            zone = zone,
        )

        assertEquals(CloudPresencePreDataCutoffResolution.InvalidLocalDateTime, gap.cutoffResolution)
        assertEquals(CloudPresencePreDataCutoffResolution.InvalidLocalDateTime, overlap.cutoffResolution)
    }

    @Test
    fun missingSteamHistoryImportCannotEnablePreDataTransfer() {
        val readAt = instant("2025-04-20T12:00:00Z")
        val controls = controls(metadata(earliest = instant("2025-04-01T00:00:00Z"), readAt = readAt)).copy(
            startChoice = CloudPresenceHistoricalStartChoice.CUSTOM_LOCAL_DATE,
            customStartDate = LocalDate.parse("2025-04-10"),
            transferPreDataPlay = true,
            cutoffLocalDateTime = LocalDateTime.parse("2025-04-15T12:00:00"),
        )

        val resolved = resolveCloudHistoricalRangeControls(
            controls = controls,
            historyImported = false,
            nowAt = readAt,
            zone = ZoneId.of("UTC"),
        )

        assertFalse(resolved.transferPreDataPlay)
        assertEquals(CloudPresencePreDataCutoffResolution.NotConfirmed, resolved.cutoffResolution)
        assertNull(resolved.cutoffLocalDateTime)
        assertTrue(resolved.selection != null)
    }

    @Test
    fun emptyMetadataDoesNotResolveAnInventedRange() {
        val resolved = resolveCloudHistoricalRangeControls(
            controls(metadata(earliest = null, readAt = instant("2025-04-20T12:00:00Z"))),
            historyImported = false,
            nowAt = instant("2025-04-20T12:00:00Z"),
            zone = ZoneId.of("UTC"),
        )

        assertNull(resolved.selection)
        assertEquals(
            CloudPresenceHistoricalRangeResolution.NoAvailableEvidence,
            resolved.rangeResolution,
        )
    }

    private fun controls(metadata: CloudPresenceRangeMetadata) = CloudHistoricalRangeControls(
        lookupStatus = if (metadata.earliestObservedAt == null) {
            CloudHistoricalRangeLookupStatus.EMPTY
        } else {
            CloudHistoricalRangeLookupStatus.AVAILABLE
        },
        metadata = metadata,
    )

    private fun metadata(earliest: Long?, readAt: Long) = CloudPresenceRangeMetadata(
        account = "76561198000000001",
        earliestObservedAt = earliest,
        current = null,
        readAt = readAt,
    )

    private fun instant(value: String): Long = Instant.parse(value).toEpochMilli()
}
