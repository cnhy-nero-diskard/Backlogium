package com.example.backlogium.ui.settings

import com.example.backlogium.data.repo.CloudPresenceRangeMetadata
import com.example.backlogium.data.repo.CloudPresenceRefilingGameMinutes
import com.example.backlogium.data.repo.CloudPresenceRefilingReceipt
import com.example.backlogium.data.local.entity.CloudHistoricalOperation
import com.example.backlogium.domain.CloudPresenceHistoricalRangeResolution
import com.example.backlogium.domain.CloudPresenceHistoricalRangeRules
import com.example.backlogium.domain.CloudPresenceHistoricalStartChoice
import com.example.backlogium.domain.CloudPresencePreDataCutoffResolution
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

    @Test
    fun historicalAcquisitionDoesNotStartWithoutExplicitConfirmation() = runTest {
        var startCalls = 0

        val started = runAfterCloudHistoricalConfirmation(confirmation = null) {
            startCalls++
        }

        assertFalse(started)
        assertEquals(0, startCalls)
    }

    @Test
    fun confirmationCarriesFixedRangeCutoffAndPerGameImportedCeiling() = runTest {
        val throughAt = instant("2025-04-20T12:00:00Z")
        val selection = CloudPresenceHistoricalRangeRules.recent31Days(throughAt, ZoneId.of("UTC"))
        val confirmation = CloudHistoricalRefilingConfirmation(
            metadata = metadata(earliest = instant("2025-01-01T00:00:00Z"), readAt = throughAt),
            selection = selection,
            confirmedCutoffAt = instant("2025-04-10T12:00:00Z"),
            importedBalanceCeiling = listOf(
                CloudPresenceRefilingGameMinutes(appId = 440L, minutes = 75),
                CloudPresenceRefilingGameMinutes(appId = 570L, minutes = 25),
            ),
        )
        var received: CloudHistoricalRefilingConfirmation? = null

        val started = runAfterCloudHistoricalConfirmation(confirmation) { received = it }

        assertTrue(started)
        assertEquals(confirmation.selection.throughAt, received?.selection?.throughAt)
        assertEquals(confirmation.confirmedCutoffAt, received?.confirmedCutoffAt)
        assertEquals(100L, received?.importedBalanceCeilingMinutes)
    }

    @Test
    fun restoredHistoricalOperationRequiresAnExplicitContinueOrApply() {
        val incomplete = historicalOperation(acquisitionComplete = false)
        val complete = historicalOperation(acquisitionComplete = true)

        assertEquals(CloudHistoricalAcquisitionStatus.PARTIAL, incomplete.toSettingsAcquisitionStatus())
        assertEquals(CloudHistoricalAcquisitionStatus.READY, complete.toSettingsAcquisitionStatus())
    }

    @Test
    fun durableZeroChangeReceiptStillReportsItsRangeAndRemainingImport() {
        val receipt = CloudPresenceRefilingReceipt(
            operationId = "operation",
            account = "76561198000000001",
            startChoice = "CUSTOM_LOCAL_DATE",
            selectedStartAt = 10L,
            effectiveStartAt = 20L,
            throughAt = 300L,
            coveredStartAt = 25L,
            coveredEndAt = 280L,
            confirmedCutoffAt = 250L,
            zoneId = "UTC",
            pagesFetched = 3,
            transitionsFetched = 17,
            sessionsRefiled = 0,
            datesAffected = emptyList(),
            createdSessionIds = emptyList(),
            transferredMinutesByAppId = emptyList(),
            remainingImportedMinutesByAppId = listOf(
                CloudPresenceRefilingGameMinutes(appId = 440L, minutes = 95),
            ),
        )

        val summary = receipt.toSettingsSummary()

        assertEquals(10L, summary.selectedStartAt)
        assertEquals(20L, summary.effectiveStartAt)
        assertEquals(300L, summary.throughAt)
        assertEquals(25L, summary.coveredStartAt)
        assertEquals(280L, summary.coveredEndAt)
        assertEquals(0, summary.sessionsRefiled)
        assertEquals(0L, summary.transferredMinutes)
        assertEquals(95L, summary.remainingImportedMinutes)
    }

    @Test
    fun reversalRestoresConfirmedLocalChoicesInTheReceiptZoneAfterDeviceZoneChanges() {
        val previousTimeZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
            val confirmedZone = ZoneId.systemDefault()
            val confirmedStartDate = LocalDate.parse("2025-03-01")
            val confirmedCutoff = LocalDateTime.parse("2025-03-15T12:45:00")
            val selection = (
                CloudPresenceHistoricalRangeRules.customLocalDate(
                    selectedDate = confirmedStartDate,
                    nowAt = instant("2025-03-20T00:00:00Z"),
                    throughAt = instant("2025-04-01T00:00:00Z"),
                    zone = confirmedZone,
                    earliestObservedAt = instant("2025-01-01T00:00:00Z"),
                ) as CloudPresenceHistoricalRangeResolution.Ready
            ).selection
            val confirmedCutoffAt = (
                CloudPresenceHistoricalRangeRules.confirmedPreDataCutoff(
                    localDateTime = confirmedCutoff,
                    zone = confirmedZone,
                    selection = selection,
                ) as CloudPresencePreDataCutoffResolution.Confirmed
            ).cutoffAt
            val receipt = CloudPresenceRefilingReceipt(
                operationId = "operation",
                account = "76561198000000001",
                startChoice = CloudPresenceHistoricalStartChoice.CUSTOM_LOCAL_DATE.name,
                selectedStartAt = selection.selectedStartAt,
                effectiveStartAt = selection.effectiveStartAt,
                throughAt = selection.throughAt,
                coveredStartAt = null,
                coveredEndAt = null,
                confirmedCutoffAt = confirmedCutoffAt,
                zoneId = selection.zoneId,
                pagesFetched = 1,
                transitionsFetched = 1,
                sessionsRefiled = 1,
                datesAffected = emptyList(),
                createdSessionIds = emptyList(),
                transferredMinutesByAppId = emptyList(),
                remainingImportedMinutesByAppId = emptyList(),
            )
            val controlsBeforeReversal = CloudHistoricalRangeControls(
                startChoice = CloudPresenceHistoricalStartChoice.RECENT_31_DAYS,
                customStartDate = LocalDate.parse("2025-02-01"),
                transferPreDataPlay = false,
                cutoffLocalDateTime = null,
            )

            // The receipt contains these confirmed Tokyo choices; reversal happens after travel.
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            val deviceZone = ZoneId.systemDefault()
            val restored = controlsBeforeReversal.restoreAfterCloudHistoricalReversal(receipt)

            assertNotEquals(confirmedZone, deviceZone)
            assertNotEquals(
                confirmedStartDate,
                Instant.ofEpochMilli(receipt.selectedStartAt).atZone(deviceZone).toLocalDate(),
            )
            assertNotEquals(
                confirmedCutoff,
                Instant.ofEpochMilli(checkNotNull(receipt.confirmedCutoffAt))
                    .atZone(deviceZone)
                    .toLocalDateTime(),
            )
            assertEquals(CloudPresenceHistoricalStartChoice.CUSTOM_LOCAL_DATE, restored.startChoice)
            assertEquals(confirmedStartDate, restored.customStartDate)
            assertTrue(restored.transferPreDataPlay)
            assertEquals(confirmedCutoff, restored.cutoffLocalDateTime)
        } finally {
            TimeZone.setDefault(previousTimeZone)
        }
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

    private fun historicalOperation(acquisitionComplete: Boolean) = CloudHistoricalOperation(
        operationId = "operation",
        account = "76561198000000001",
        readerGeneration = 4L,
        endpointIdentity = "https://reader.example",
        selectedStartAt = 10L,
        fromAt = 20L,
        throughAt = 300L,
        pagesFetched = 3,
        transitionsFetched = 17,
        acquisitionComplete = acquisitionComplete,
        state = if (acquisitionComplete) "COMPLETE" else "ACQUIRING",
        createdAt = 1L,
        updatedAt = 2L,
    )
}
