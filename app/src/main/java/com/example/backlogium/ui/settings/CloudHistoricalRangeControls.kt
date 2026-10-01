package com.example.backlogium.ui.settings

import com.example.backlogium.data.repo.CloudPresenceRangeMetadata
import com.example.backlogium.data.repo.CloudPresenceRefilingGameMinutes
import com.example.backlogium.data.repo.CloudPresenceRefilingReceipt
import com.example.backlogium.data.repo.CloudReadFailure
import com.example.backlogium.data.local.entity.CloudHistoricalOperation
import com.example.backlogium.domain.CloudPresenceHistoricalRangeResolution
import com.example.backlogium.domain.CloudPresenceHistoricalRangeRules
import com.example.backlogium.domain.CloudPresenceHistoricalSelection
import com.example.backlogium.domain.CloudPresenceHistoricalStartChoice
import com.example.backlogium.domain.CloudPresencePreDataCutoffResolution
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

enum class CloudHistoricalRangeLookupStatus {
    NOT_REQUESTED,
    LOADING,
    AVAILABLE,
    EMPTY,
    UNCONFIGURED,
    NO_STEAM_ACCOUNT,
    FAILED,
}

data class CloudHistoricalRangeControls(
    val lookupStatus: CloudHistoricalRangeLookupStatus = CloudHistoricalRangeLookupStatus.NOT_REQUESTED,
    val metadata: CloudPresenceRangeMetadata? = null,
    val lookupFailure: CloudReadFailure? = null,
    val startChoice: CloudPresenceHistoricalStartChoice = CloudPresenceHistoricalStartChoice.RECENT_31_DAYS,
    val customStartDate: LocalDate? = null,
    val rangeResolution: CloudPresenceHistoricalRangeResolution? = null,
    val transferPreDataPlay: Boolean = false,
    val cutoffLocalDateTime: LocalDateTime? = null,
    val cutoffResolution: CloudPresencePreDataCutoffResolution =
        CloudPresencePreDataCutoffResolution.NotConfirmed,
    val pendingConfirmation: CloudHistoricalRefilingConfirmation? = null,
    val operation: CloudHistoricalOperation? = null,
    val acquisitionStatus: CloudHistoricalAcquisitionStatus = CloudHistoricalAcquisitionStatus.IDLE,
    val lastBatchPages: Int = 0,
    val lastBatchTransitions: Int = 0,
    val acquisitionFailure: CloudReadFailure? = null,
) {
    val selection: CloudPresenceHistoricalSelection?
        get() = (rangeResolution as? CloudPresenceHistoricalRangeResolution.Ready)?.selection
}

/** Restore the confirmed form choices in the timezone in which the receipt recorded them. */
internal fun CloudHistoricalRangeControls.restoreAfterCloudHistoricalReversal(
    receipt: CloudPresenceRefilingReceipt?,
): CloudHistoricalRangeControls {
    val savedZone = receipt?.let { saved ->
        runCatching { ZoneId.of(saved.zoneId) }.getOrNull()
    }
    return copy(
        startChoice = receipt?.let { saved ->
            runCatching { CloudPresenceHistoricalStartChoice.valueOf(saved.startChoice) }
                .getOrDefault(startChoice)
        } ?: startChoice,
        customStartDate = receipt?.let { saved ->
            savedZone?.let { zone ->
                Instant.ofEpochMilli(saved.selectedStartAt).atZone(zone).toLocalDate()
            }
        } ?: customStartDate,
        transferPreDataPlay = receipt?.confirmedCutoffAt != null || transferPreDataPlay,
        cutoffLocalDateTime = receipt?.confirmedCutoffAt?.let { cutoff ->
            savedZone?.let { zone -> Instant.ofEpochMilli(cutoff).atZone(zone).toLocalDateTime() }
        } ?: cutoffLocalDateTime,
        operation = null,
        pendingConfirmation = null,
        acquisitionStatus = CloudHistoricalAcquisitionStatus.IDLE,
        lastBatchPages = 0,
        lastBatchTransitions = 0,
        acquisitionFailure = null,
    )
}

data class CloudHistoricalRefilingConfirmation(
    val metadata: CloudPresenceRangeMetadata,
    val selection: CloudPresenceHistoricalSelection,
    val confirmedCutoffAt: Long?,
    val importedBalanceCeiling: List<CloudPresenceRefilingGameMinutes>,
) {
    val importedBalanceCeilingMinutes: Long
        get() = importedBalanceCeiling.sumOf { it.minutes.toLong() }
}

internal fun CloudHistoricalOperation.toSettingsAcquisitionStatus(): CloudHistoricalAcquisitionStatus =
    if (acquisitionComplete) CloudHistoricalAcquisitionStatus.READY else CloudHistoricalAcquisitionStatus.PARTIAL

internal data class CloudHistoricalReceiptSummary(
    val selectedStartAt: Long,
    val effectiveStartAt: Long,
    val throughAt: Long,
    val coveredStartAt: Long?,
    val coveredEndAt: Long?,
    val confirmedCutoffAt: Long?,
    val zoneId: String,
    val pagesFetched: Int,
    val transitionsFetched: Int,
    val sessionsRefiled: Int,
    val datesAffected: Int,
    val transferredMinutes: Long,
    val remainingImportedMinutes: Long,
)

internal fun CloudPresenceRefilingReceipt.toSettingsSummary() = CloudHistoricalReceiptSummary(
    selectedStartAt = selectedStartAt,
    effectiveStartAt = effectiveStartAt,
    throughAt = throughAt,
    coveredStartAt = coveredStartAt,
    coveredEndAt = coveredEndAt,
    confirmedCutoffAt = confirmedCutoffAt,
    zoneId = zoneId,
    pagesFetched = pagesFetched,
    transitionsFetched = transitionsFetched,
    sessionsRefiled = sessionsRefiled,
    datesAffected = datesAffected.size,
    transferredMinutes = transferredMinutesByAppId.sumOf { it.minutes.toLong() },
    remainingImportedMinutes = remainingImportedMinutesByAppId.sumOf { it.minutes.toLong() },
)

enum class CloudHistoricalAcquisitionStatus {
    IDLE,
    STARTING,
    RUNNING,
    PARTIAL,
    FAILED,
    READY,
    APPLYING,
    APPLY_FAILED,
    APPLIED,
}

/** The only path into historical start/acquisition is an explicit dialog confirmation. */
internal suspend fun runAfterCloudHistoricalConfirmation(
    confirmation: CloudHistoricalRefilingConfirmation?,
    action: suspend (CloudHistoricalRefilingConfirmation) -> Unit,
): Boolean {
    if (confirmation == null) return false
    action(confirmation)
    return true
}

/** Resolve the form against the latest server end and the device's current local-time rules. */
internal fun resolveCloudHistoricalRangeControls(
    controls: CloudHistoricalRangeControls,
    historyImported: Boolean,
    nowAt: Long,
    zone: ZoneId,
): CloudHistoricalRangeControls {
    val metadata = controls.metadata
    val range = when {
        metadata == null -> null
        metadata.earliestObservedAt == null -> CloudPresenceHistoricalRangeResolution.NoAvailableEvidence
        controls.startChoice == CloudPresenceHistoricalStartChoice.RECENT_31_DAYS ->
            CloudPresenceHistoricalRangeResolution.Ready(
                CloudPresenceHistoricalRangeRules.recent31Days(metadata.readAt, zone),
            )
        controls.customStartDate == null -> null
        else -> CloudPresenceHistoricalRangeRules.customLocalDate(
            selectedDate = controls.customStartDate,
            nowAt = nowAt,
            throughAt = metadata.readAt,
            zone = zone,
            earliestObservedAt = metadata.earliestObservedAt,
        )
    }
    val transferSelected = controls.transferPreDataPlay && historyImported
    if (!historyImported && controls.transferPreDataPlay) {
        return controls.copy(
            rangeResolution = range,
            transferPreDataPlay = false,
            cutoffLocalDateTime = null,
            cutoffResolution = CloudPresencePreDataCutoffResolution.NotConfirmed,
        )
    }
    val cutoff = if (transferSelected) {
        CloudPresenceHistoricalRangeRules.confirmedPreDataCutoff(
            localDateTime = controls.cutoffLocalDateTime,
            zone = zone,
            selection = (range as? CloudPresenceHistoricalRangeResolution.Ready)?.selection
                ?: return controls.copy(
                    rangeResolution = range,
                    transferPreDataPlay = transferSelected,
                    cutoffResolution = CloudPresencePreDataCutoffResolution.NotConfirmed,
                ),
        )
    } else {
        CloudPresencePreDataCutoffResolution.NotConfirmed
    }
    return controls.copy(
        rangeResolution = range,
        transferPreDataPlay = transferSelected,
        cutoffResolution = cutoff,
    )
}
