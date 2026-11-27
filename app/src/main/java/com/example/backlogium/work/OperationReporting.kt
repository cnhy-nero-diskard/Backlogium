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
internal const val OP_EXECUTION_ID = "operation_execution_id"
internal const val OP_STARTED_AT = "operation_started_at"
internal const val OP_PARTIAL = "operation_partial"

internal fun operationData(
    id: String,
    generation: Long,
    phase: OperationPhase,
    counts: OperationCounts? = null,
    updated: Int? = null,
    failed: Boolean = false,
    executionId: String? = null,
    startedAt: Long? = null,
    partial: Boolean = false,
): Data = Data.Builder()
    .putString(OP_ID, id)
    .putLong(OP_ACCOUNT, generation)
    .putString(OP_PHASE, phase.name)
    .putInt(OP_PROCESSED, counts?.processed ?: -1)
    .putInt(OP_TOTAL, counts?.total ?: -1)
    .putInt(OP_SKIPPED, counts?.skipped ?: 0)
    .putInt(OP_FAILED, counts?.failed ?: 0)
    .putInt(OP_UPDATED, updated ?: -1)
    .putBoolean(OP_FAILURE, failed)
    .putBoolean(OP_PARTIAL, partial)
    .apply {
        executionId?.let { putString(OP_EXECUTION_ID, it) }
        startedAt?.let { putLong(OP_STARTED_AT, it) }
    }
    .build()

internal fun Data.operationCounts(): OperationCounts? = getInt(OP_TOTAL, -1).takeIf { it >= 0 }?.let {
    OperationCounts(getInt(OP_PROCESSED, 0), it, getInt(OP_SKIPPED, 0), getInt(OP_FAILED, 0))
}

/** Unknown constraint causes stay queued; older builds can supply only a coarse WorkInfo state. */
internal fun operationProgress(info: WorkInfo, generation: Long): OperationProgress? {
    val data = if (info.state.isFinished) info.outputData else info.progress
    val taggedGenerations = info.tags.filter { it.startsWith("setup-account:") }
        .map { it.substringAfter(":").toLongOrNull() ?: return null }
        .distinct()
    if (taggedGenerations.size > 1) return null
    val taggedGeneration = taggedGenerations.singleOrNull()
    val payloadGeneration = data.getLong(OP_ACCOUNT, Long.MIN_VALUE).takeUnless { it == Long.MIN_VALUE }
    if (taggedGeneration != null && payloadGeneration != null && taggedGeneration != payloadGeneration) return null
    val account = payloadGeneration ?: taggedGeneration ?: 0L
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
            data.getBoolean(OP_PARTIAL, false) -> OperationStatus.PARTIAL
            (counts?.failed ?: 0) > 0 -> if (data.getString(SteamSyncWorker.KEY_POLL_RESULT) == SteamSyncWorker.POLL_COMMITTED ||
                counts!!.failed < counts.processed - counts.skipped) OperationStatus.PARTIAL else OperationStatus.FAILED
            else -> OperationStatus.SUCCEEDED
        }
    }
    return OperationProgress(
        id = data.getString(OP_EXECUTION_ID) ?: info.id.toString(),
        status = status,
        phase = data.getString(OP_PHASE)?.let { name -> OperationPhase.entries.firstOrNull { it.name == name } },
        counts = counts,
        updated = data.getInt(OP_UPDATED, -1).takeIf { it >= 0 },
        workId = info.id.toString(),
    )
}

internal data class TimedOperation(val progress: OperationProgress, val startedAt: Long)

internal fun latestOperation(candidates: List<TimedOperation>): OperationProgress? =
    candidates.maxWithOrNull(compareBy<TimedOperation>({ it.startedAt }, { it.progress.active }))?.progress

internal fun periodicOperation(
    info: WorkInfo?,
    generation: Long,
    stored: StoredOperationReport?,
): TimedOperation? {
    val report = stored?.takeIf {
        it.generation == generation && (info == null || it.workId == info.id.toString())
    }
    val live = info?.let { operationProgress(it, generation)?.let { progress ->
        TimedOperation(progress, operationStartedAt(it) ?: operationRequestedAt(it) ?: 0L)
    } }
    return when {
        info?.state == WorkInfo.State.RUNNING -> live ?: report?.timed()
        info?.state == WorkInfo.State.ENQUEUED && info.runAttemptCount > 0 ->
            (report?.takeIf { it.progress.status in setOf(OperationStatus.RUNNING, OperationStatus.RETRYING) }
                ?.copy(progress = report.progress.copy(status = OperationStatus.RETRYING))?.timed()) ?: live
        live != null && info?.state?.isFinished == true -> live
        report != null -> report.timed()
        else -> live?.takeIf { info?.state != WorkInfo.State.ENQUEUED }
    }
}

internal fun operationStartedAt(info: WorkInfo): Long? =
    (if (info.state.isFinished) info.outputData else info.progress)
        .getLong(OP_STARTED_AT, -1L).takeIf { it >= 0L }

internal fun operationRequestedAt(info: WorkInfo): Long? = info.tags.firstOrNull {
    it.startsWith("operation-requested:")
}?.substringAfter(":")?.toLongOrNull()

private fun StoredOperationReport.timed() = TimedOperation(progress.copy(workId = workId), startedAt)

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
