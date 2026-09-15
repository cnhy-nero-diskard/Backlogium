package com.example.backlogium.domain

data class CloudPresenceTransition(
    val at: Long,
    val appId: Long?,
    val gameName: String?,
    val personastate: Int?,
    val previousLastObservedAt: Long?,
    val previousCoverageLapseFrom: Long?,
    val previousCoverageLapseRecoveredAt: Long?,
    val schemaVersion: Int?,
)

data class CloudPresenceCurrentState(
    val observedAt: Long?,
    val appId: Long?,
    val gameName: String?,
    val personastate: Int?,
    val since: Long?,
    val coverageLapseFrom: Long?,
    val coverageLapseRecoveredAt: Long?,
    val schemaVersion: Int?,
)

enum class CloudCoverageState {
    CONTINUOUS,
    OBSERVED_UNTIL,
    UNKNOWN,
}

data class CloudPresenceInterval(
    val appId: Long,
    val gameName: String?,
    val startAt: Long,
    val endAt: Long?,
    val ongoing: Boolean,
    val coverage: CloudCoverageState,
    val observedUntil: Long?,
    val coverageLapseFrom: Long?,
    val coverageLapseRecoveredAt: Long?,
    val mayHaveStartedBefore: Boolean,
)

/**
 * Reconstructs only what the cloud observations can vouch for.
 *
 * This deliberately returns raw observation intervals rather than app sessions or derived progress.
 * A reader may tune [tailToleranceMillis], while the poller remains an observation ledger.
 */
object CloudPresenceReconstruction {
    const val DEFAULT_TAIL_TOLERANCE_MILLIS = 2L * 60 * 1000

    fun reconstruct(
        transitions: List<CloudPresenceTransition>,
        current: CloudPresenceCurrentState?,
        tailToleranceMillis: Long = DEFAULT_TAIL_TOLERANCE_MILLIS,
    ): List<CloudPresenceInterval> {
        if (transitions.isEmpty()) {
            // A resumed read with no new transitions still reports the live state: when the
            // user is still in the same game, the response carries no transition but the
            // current document's `since` bounds the ongoing interval. Without it the new
            // snapshot would go empty and diagnostics would lose the active cloud interval.
            val appId = current?.appId ?: return emptyList()
            val since = current.since ?: return emptyList()
            val observedAt = current.observedAt ?: return emptyList()
            if (observedAt < since) return emptyList()
            val decision = coverage(
                closingAt = observedAt,
                observedAt = observedAt,
                coverageLapseFrom = current.coverageLapseFrom,
                coverageLapseRecoveredAt = current.coverageLapseRecoveredAt,
                tailToleranceMillis = tailToleranceMillis,
            )
            return listOf(
                CloudPresenceInterval(
                    appId = appId,
                    gameName = current.gameName,
                    startAt = since,
                    endAt = observedAt,
                    ongoing = true,
                    coverage = decision.state,
                    observedUntil = decision.observedUntil,
                    coverageLapseFrom = current.coverageLapseFrom,
                    coverageLapseRecoveredAt = current.coverageLapseRecoveredAt,
                    mayHaveStartedBefore = true,
                ),
            )
        }

        val ordered = transitions.sortedBy { it.at }
        val intervals = mutableListOf<CloudPresenceInterval>()
        var mayHaveStartedBefore = true

        for (index in 0 until ordered.lastIndex) {
            val opening = ordered[index]
            val closing = ordered[index + 1]
            val decision = coverage(
                closingAt = closing.at,
                observedAt = closing.previousLastObservedAt,
                coverageLapseFrom = closing.previousCoverageLapseFrom,
                coverageLapseRecoveredAt = closing.previousCoverageLapseRecoveredAt,
                tailToleranceMillis = tailToleranceMillis,
            )
            val appId = opening.appId
            if (appId != null && closing.at >= opening.at) {
                intervals += CloudPresenceInterval(
                    appId = appId,
                    gameName = opening.gameName,
                    startAt = opening.at,
                    endAt = closing.at,
                    ongoing = false,
                    coverage = decision.state,
                    observedUntil = decision.observedUntil,
                    coverageLapseFrom = closing.previousCoverageLapseFrom,
                    coverageLapseRecoveredAt = closing.previousCoverageLapseRecoveredAt,
                    mayHaveStartedBefore = mayHaveStartedBefore,
                )
            }
            mayHaveStartedBefore = decision.state != CloudCoverageState.CONTINUOUS
        }

        val last = ordered.last()
        val currentAppId = current?.appId
        val currentObservedAt = current?.observedAt
        if (
            last.appId != null &&
            currentAppId == last.appId &&
            currentObservedAt != null &&
            currentObservedAt >= last.at
        ) {
            val decision = coverage(
                closingAt = currentObservedAt,
                observedAt = currentObservedAt,
                coverageLapseFrom = current.coverageLapseFrom,
                coverageLapseRecoveredAt = current.coverageLapseRecoveredAt,
                tailToleranceMillis = tailToleranceMillis,
            )
            intervals += CloudPresenceInterval(
                appId = last.appId,
                gameName = last.gameName,
                startAt = last.at,
                endAt = currentObservedAt,
                ongoing = true,
                coverage = decision.state,
                observedUntil = decision.observedUntil,
                coverageLapseFrom = current.coverageLapseFrom,
                coverageLapseRecoveredAt = current.coverageLapseRecoveredAt,
                mayHaveStartedBefore = mayHaveStartedBefore,
            )
        }

        return intervals
    }

    private fun coverage(
        closingAt: Long,
        observedAt: Long?,
        coverageLapseFrom: Long?,
        coverageLapseRecoveredAt: Long?,
        tailToleranceMillis: Long,
    ): CoverageDecision {
        if (coverageLapseFrom != null && coverageLapseRecoveredAt != null) {
            // The writer retains the largest consecutive-observation step seen within the
            // state, including normal on-cadence polls, and applies no tolerance itself:
            // whether a span of a minute or an hour counts as a lapse is this reader's
            // decision. Only a retained span exceeding the tolerance marks the whole
            // interval unconfirmed; a short step falls through to the tail verdict below.
            val retainedSpan = coverageLapseRecoveredAt - coverageLapseFrom
            if (retainedSpan < 0 || retainedSpan > tailToleranceMillis) {
                return CoverageDecision(
                    state = CloudCoverageState.OBSERVED_UNTIL,
                    observedUntil = observedAt,
                )
            }
        }
        if (observedAt == null || observedAt > closingAt) {
            return CoverageDecision(CloudCoverageState.UNKNOWN, observedAt)
        }
        val tail = closingAt - observedAt
        return if (tail <= tailToleranceMillis) {
            CoverageDecision(CloudCoverageState.CONTINUOUS, observedAt)
        } else {
            CoverageDecision(CloudCoverageState.OBSERVED_UNTIL, observedAt)
        }
    }

    private data class CoverageDecision(
        val state: CloudCoverageState,
        val observedUntil: Long?,
    )
}
