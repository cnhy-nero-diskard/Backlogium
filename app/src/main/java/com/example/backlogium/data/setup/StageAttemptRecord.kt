package com.example.backlogium.data.setup

import com.example.backlogium.work.setup.AdmissionKind
import com.example.backlogium.work.setup.ForegroundSettledReason
import com.example.backlogium.work.setup.SetupOperationState
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The durable, versioned latest-attempt record for one setup stage (design Decision 2).
 *
 * Exactly one record is retained per stage — the latest association — so a result from an older
 * attempt or another account can never overwrite the current attempt, and the store never grows an
 * unbounded second work history. It carries:
 *
 * - [generation]: opaque attempt generation. A new request for the same stage bumps it; callbacks
 *   from a superseded generation are discarded, which is what stops an old completion from
 *   overwriting a replacement attempt.
 * - [cohortId]: the setup run this admission belongs to, so an interrupted run can resume the
 *   remaining selected admissions by cohort rather than by guessing.
 * - [accountMarker]: opaque owning-account identity; a completion from another account is fenced
 *   out, never attributed.
 * - [requestId] / [uniqueWorkName] / [candidateWorkId] / [admittedWorkId] / [admissionKind]: the
 *   exact request/work association — [requestId] and the [candidateWorkId] it expected `KEEP` to
 *   retain are persisted *before* enqueue, and the exact admitted job (whether `NEW` or `REUSED`)
 *   is recorded after.
 * - [operation]: the stage's latest real operation state.
 * - [foregroundSettledReason]: why this stage's foreground observation settled (per-stage, never a
 *   work outcome).
 */
@Serializable
data class StageAttemptRecord(
    val stageId: String = "",
    val generation: Long = 0L,
    val cohortId: String = "",
    val accountMarker: String? = null,
    val requestId: String? = null,
    val uniqueWorkName: String? = null,
    /** The live job this request expected `KEEP` to retain, recorded before enqueue (exact recovery). */
    val candidateWorkId: String? = null,
    val admittedWorkId: String? = null,
    val admissionKind: AdmissionKind? = null,
    val operation: SetupOperationState = SetupOperationState.NeverRun,
    val foregroundSettledReason: ForegroundSettledReason? = null,
    val schemaVersion: Int = 1,
) {
    /** Whether the exact admitted job is durably associated with this attempt. */
    val hasDurableAdmission: Boolean get() = admittedWorkId != null && uniqueWorkName != null

    /** Whether this stage's foreground observation has settled (a foreground fact, never a verdict). */
    val isForegroundSettled: Boolean get() = foregroundSettledReason != null
}

/**
 * The cohort (run) context persisted beside the per-stage records. [selectedStageIds] is the run's
 * immutable selection, including stages that have not been admitted yet — that unadmitted intent is
 * what recovery uses to admit the rest of a run in order after process death or an explicit exit.
 */
data class SetupCohort(
    val cohortId: String? = null,
    val selectedStageIds: Set<String> = emptySet(),
    val accountMarker: String? = null,
)

/**
 * Wire codec for [StageAttemptRecord]. Unknown fields are tolerated (`ignoreUnknownKeys`); a value
 * this build cannot decode (a future attempt state, a corrupted row) is dropped by callers rather
 * than failing setup to render.
 */
internal val stageAttemptJson = Json { ignoreUnknownKeys = true }

/** Encode one attempt record to its stored string form. */
fun encodeStageAttempt(record: StageAttemptRecord): String =
    stageAttemptJson.encodeToString(record)

/** Decode one attempt record; unknown or malformed values yield null. */
fun decodeStageAttempt(encoded: String): StageAttemptRecord? =
    runCatching { stageAttemptJson.decodeFromString<StageAttemptRecord>(encoded) }
        .getOrNull()?.takeIf { it.schemaVersion == 1 }
