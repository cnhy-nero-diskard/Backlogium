package com.example.backlogium.domain

/** Counts describe attempts, never successful requests masquerading as changed data. */
data class OperationCounts(
    val processed: Int = 0,
    val total: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
)

enum class OperationPhase { PREPARING, FETCHING_LIBRARY, ACHIEVEMENTS, SAVING }
enum class OperationStatus { QUEUED, RUNNING, RETRYING, CANCELLED, SUCCEEDED, PARTIAL, FAILED }

data class OperationProgress(
    val id: String,
    val status: OperationStatus,
    val phase: OperationPhase? = null,
    val counts: OperationCounts? = null,
    val updated: Int? = null,
) {
    val active: Boolean get() = status in setOf(OperationStatus.QUEUED, OperationStatus.RUNNING, OperationStatus.RETRYING)
}
