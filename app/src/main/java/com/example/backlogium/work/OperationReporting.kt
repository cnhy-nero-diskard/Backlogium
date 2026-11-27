package com.example.backlogium.work

import androidx.work.Data
import androidx.work.WorkInfo
import androidx.work.workDataOf
import com.example.backlogium.domain.OperationCounts
import com.example.backlogium.domain.OperationPhase
import com.example.backlogium.domain.OperationProgress
import com.example.backlogium.domain.OperationStatus

internal const val OP_ID = "operation_id"
internal const val OP_ACCOUNT = "operation_account_generation"
internal const val OP_PHASE = "operation_phase"
internal const val OP_PROCESSED = "operation_processed"
internal const val OP_TOTAL = "operation_total"
internal const val OP_SKIPPED = "operation_skipped"
internal const val OP_FAILED = "operation_failed"
internal const val OP_UPDATED = "operation_updated"
internal const val OP_FAILURE = "operation_failure"

internal fun operationData(
    id: String,
    generation: Long,
    phase: OperationPhase,
    counts: OperationCounts? = null,
    updated: Int? = null,
    failed: Boolean = false,
): Data = workDataOf(OP_ID to id, OP_ACCOUNT to generation, OP_PHASE to phase.name,
    OP_PROCESSED to (counts?.processed ?: -1), OP_TOTAL to (counts?.total ?: -1),
    OP_SKIPPED to (counts?.skipped ?: 0), OP_FAILED to (counts?.failed ?: 0),
    OP_UPDATED to (updated ?: -1), OP_FAILURE to failed)

internal fun Data.operationCounts(): OperationCounts? = getInt(OP_TOTAL, -1).takeIf { it >= 0 }?.let {
    OperationCounts(getInt(OP_PROCESSED, 0), it, getInt(OP_SKIPPED, 0), getInt(OP_FAILED, 0))
}

/** Unknown constraint causes stay queued; older builds can supply only a coarse WorkInfo state. */
internal fun operationProgress(info: WorkInfo, generation: Long): OperationProgress? {
    val data = if (info.state.isFinished) info.outputData else info.progress
    val taggedGeneration = info.tags.firstOrNull { it.startsWith("setup-account:") }
        ?.substringAfter(":")?.toLongOrNull()
    val account = data.getLong(OP_ACCOUNT, taggedGeneration ?: 0L)
    if (account != generation) return null
    if (data.getString(OP_ID)?.let { it != info.id.toString() } == true) return null
    val counts = data.operationCounts()
    val status = when (info.state) {
        WorkInfo.State.RUNNING -> OperationStatus.RUNNING
        WorkInfo.State.ENQUEUED -> when {
            info.stopReason in setOf(WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY,
                WorkInfo.STOP_REASON_CONSTRAINT_CHARGING, WorkInfo.STOP_REASON_CONSTRAINT_BATTERY_NOT_LOW,
                WorkInfo.STOP_REASON_CONSTRAINT_DEVICE_IDLE, WorkInfo.STOP_REASON_CONSTRAINT_STORAGE_NOT_LOW) -> OperationStatus.WAITING
            info.runAttemptCount > 0 && info.stopReason == WorkInfo.STOP_REASON_NOT_STOPPED -> OperationStatus.RETRYING
            else -> OperationStatus.QUEUED
        }
        WorkInfo.State.BLOCKED -> OperationStatus.QUEUED
        WorkInfo.State.CANCELLED -> OperationStatus.CANCELLED
        WorkInfo.State.FAILED -> OperationStatus.FAILED
        WorkInfo.State.SUCCEEDED -> when {
            data.getBoolean(OP_FAILURE, false) || data.getString(SteamSyncWorker.KEY_SETUP_FAILURE) != null -> OperationStatus.FAILED
            (counts?.failed ?: 0) > 0 -> if (data.getString(SteamSyncWorker.KEY_POLL_RESULT) == SteamSyncWorker.POLL_COMMITTED ||
                counts!!.failed < counts.processed - counts.skipped) OperationStatus.PARTIAL else OperationStatus.FAILED
            else -> OperationStatus.SUCCEEDED
        }
    }
    return OperationProgress(info.id.toString(), status,
        data.getString(OP_PHASE)?.let { name -> OperationPhase.entries.firstOrNull { it.name == name } },
        counts, data.getInt(OP_UPDATED, -1).takeIf { it >= 0 })
}

/** Publish phase changes immediately and bound writes during a large batch; always flush its end. */
internal class OperationReporter(private val clock: () -> Long = android.os.SystemClock::elapsedRealtime) {
    private var lastPhase: OperationPhase? = null
    private var lastWrite = Long.MIN_VALUE
    suspend fun report(phase: OperationPhase, counts: OperationCounts?, write: suspend () -> Unit) {
        val now = clock()
        if (phase != lastPhase || (counts != null && counts.processed == counts.total) || lastWrite == Long.MIN_VALUE || now - lastWrite >= 250L) {
            write()
            lastPhase = phase
            lastWrite = now
        }
    }
}
