package com.example.backlogium.data.repo

/** Minimum gaps apply to routine reads only; manual and accuracy-driven reads are independent. */
enum class CloudRoutinePolicy(val minimumGapHours: Long?, val periodicHours: Long?) {
    AUTOMATIC(12, 24),
    EVERY_12_HOURS(12, 12),
    DAILY(24, 24),
    EVERY_48_HOURS(48, 48),
    OFF_MANUAL_ONLY(null, null);

    val routineEnabled: Boolean get() = minimumGapHours != null && periodicHours != null
}

data class CloudRoutineState(
    val policy: CloudRoutinePolicy? = null,
    val lastAdmittedAt: Long? = null,
    val lastOutcome: CloudReadSummaryOutcome? = null,
    val orderingWatermark: Long = 0L,
    val lastAdmissionWatermark: Long = 0L,
    val latestOtherReadWatermark: Long = 0L,
    val latestOtherReadTerminal: Boolean = false,
    val consumedOtherReadWatermark: Long = 0L,
)

enum class CloudRoutineAdmission { ADMITTED, SATISFIED_BY_READ, COOLDOWN, UNAVAILABLE }
