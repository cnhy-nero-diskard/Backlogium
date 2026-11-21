package com.example.backlogium.domain

/** One successful or failed existing presence sample; no elapsed timer or play quantity. */
data class LocalPlaytimeSample(
    val account: String,
    val processGeneration: String,
    val appId: Long?,
    val observedAt: Long,
    val successful: Boolean,
)

/**
 * Adjacent successful same-game samples are timing evidence, never authorization for owned minutes.
 * A first sample, a stopped-game sample, a failed request, a restart or a monitoring lapse cannot
 * fabricate the unobserved interval. The generation is minted by the observer process and injected.
 */
object LocalPlaytimeTiming {
    const val MAX_SAMPLE_GAP_MILLIS = 90_000L

    fun span(
        previous: LocalPlaytimeSample?,
        current: LocalPlaytimeSample,
        maxSampleGapMillis: Long = MAX_SAMPLE_GAP_MILLIS,
    ): PlaytimeTimingEvidence? {
        require(maxSampleGapMillis > 0)
        if (previous == null || !previous.successful || !current.successful) return null
        if (current.account.isBlank() || current.processGeneration.isBlank()) return null
        if (previous.account != current.account || previous.processGeneration != current.processGeneration) return null
        val appId = current.appId?.takeIf { it > 0 } ?: return null
        if (previous.appId != appId || current.observedAt <= previous.observedAt) return null
        // Compare with wider arithmetic so extreme injected clocks cannot wrap into a usable gap.
        val elapsed = current.observedAt.toBigInteger() - previous.observedAt.toBigInteger()
        if (elapsed > maxSampleGapMillis.toBigInteger()) return null
        return PlaytimeTimingEvidence(
            evidenceId = "local|${current.account}|${current.processGeneration}|$appId|${previous.observedAt}|${current.observedAt}",
            appId = appId,
            account = current.account,
            source = PlaytimeEvidenceSource.LOCAL,
            startAt = previous.observedAt,
            endAt = current.observedAt,
        )
    }
}
