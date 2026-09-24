package com.example.backlogium.data.repo

/** Local metadata about reader requests, not a claim about the poller's live health. */
enum class CloudReadSummaryOutcome { COMPLETE, NO_NEW_DATA, PARTIAL, FAILED }

data class CloudReadSummary(
    val lastAttemptAt: Long? = null,
    val lastTrigger: CloudReadTrigger? = null,
    val lastOutcome: CloudReadSummaryOutcome? = null,
    val lastFailure: CloudReadFailure? = null,
    val lastSuccessAt: Long? = null,
    val latestObservationAt: Long? = null,
    val lastSuccessHasMore: Boolean? = null,
    val lastSuccessWindowStart: Long? = null,
    val lastSuccessWindowEnd: Long? = null,
)
