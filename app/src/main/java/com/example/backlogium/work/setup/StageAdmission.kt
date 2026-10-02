package com.example.backlogium.work.setup

import java.util.UUID

/**
 * Whether an admitted chain belongs to this request or was retained from earlier.
 *
 * This distinction is persisted in the stage's attempt record so an interrupted admission can be
 * re-attributed exactly: a `REUSED` row means the scheduler's `KEEP` kept live work that already
 * existed, a `NEW` row means the request's own job was enqueued.
 */
enum class AdmissionKind { NEW, REUSED }

/**
 * The exact job one admission request established. The work identity is always a concrete job id —
 * never a reusable unique work name — so observation and recovery attach to the exact operation.
 */
sealed interface AdmittedWork {
    /** The exact work identity every admission carries — a concrete job id, never a reusable name. */
    val workId: UUID

    /** A job this request itself enqueued (no live work existed for `KEEP` to retain). */
    data class New(override val workId: UUID) : AdmittedWork

    /** A live job the scheduler retained by `KEEP` instead of enqueueing the new request. */
    data class Reused(override val workId: UUID) : AdmittedWork
}

/**
 * What admitting one stage produced. [Work] is the exact association the coordinator persists; a
 * [NeedsRecovery] is never misread as success — the stage offers an explicit request instead.
 */
sealed interface StageAdmission {
    data class Work(
        val workId: UUID,
        val uniqueWorkName: String,
        val kind: AdmissionKind,
        /** The operation state observed immediately after admission (never fabricated). */
        val initialState: SetupOperationState,
    ) : StageAdmission

    data class NeedsRecovery(val reason: String) : StageAdmission
}

/** The durable work tag carrying a setup request identity, for exact admission recovery. */
fun setupRequestTag(requestId: String): String = "setup_request_$requestId"

/**
 * Resolve what an admission request actually admitted, **exactly** — never by guessing from a set
 * of ids that appeared around the enqueue.
 *
 * The request's own job is enqueued with [requestId] as its work id, and the single live job the
 * chain held *before* the enqueue is the candidate `KEEP` was expected to retain. The durable copy
 * of that candidate is the coordinator's recorded [com.example.backlogium.data.setup.StageAttemptRecord.candidateWorkId] —
 * recovery always uses the recorded candidate, never a re-snapshotted one that could mismatch a
 * crash-interrupted intent (a mismatched candidate resolves to null → explicit recovery instead of
 * a guess). Resolution is:
 *
 * 1. the chain contains the exact [requestId] → the request's own job was admitted
 *    ([AdmittedWork.New]), even if it already finished by the time the chain is read — a
 *    direct-await post-enqueue snapshot captures the fast-finished request job exactly, and its
 *    terminal candidate can still be named by this request id without ambiguity;
 * 2. the chain contains the recorded [retainedCandidateId] → `KEEP` retained that exact live job
 *    ([AdmittedWork.Reused]) — it was live at the moment the request arrived;
 * 3. neither → null. An unrelated concurrent enqueue under the same unique name is never
 *    attributed to this request, and a terminal historical candidate alone cannot prove `KEEP`
 *    retained it for this request (recovery rather than a guess).
 */
fun resolveAdmittedWork(
    requestId: UUID,
    retainedCandidateId: UUID?,
    afterAllIds: Set<UUID>,
): AdmittedWork? {
    if (requestId in afterAllIds) return AdmittedWork.New(requestId)
    val candidate = retainedCandidateId
    if (candidate != null && candidate in afterAllIds) return AdmittedWork.Reused(candidate)
    return null
}