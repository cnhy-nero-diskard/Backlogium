package com.example.backlogium.ui.settings

import com.example.backlogium.data.repo.CloudPresenceRangeMetadata
import com.example.backlogium.data.repo.CloudReadFailure
import com.example.backlogium.domain.CloudPresenceHistoricalRangeResolution
import com.example.backlogium.domain.CloudPresenceHistoricalRangeRules
import com.example.backlogium.domain.CloudPresenceHistoricalSelection
import com.example.backlogium.domain.CloudPresenceHistoricalStartChoice
import com.example.backlogium.domain.CloudPresencePreDataCutoffResolution
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
) {
    val selection: CloudPresenceHistoricalSelection?
        get() = (rangeResolution as? CloudPresenceHistoricalRangeResolution.Ready)?.selection
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
