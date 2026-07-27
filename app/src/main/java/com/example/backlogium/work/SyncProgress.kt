package com.example.backlogium.work

import androidx.work.WorkInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.withIndex
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi

/** How long the sync indicator stays up after a sync actually finishes. */
const val SYNC_INDICATOR_HOLD_MS = 700L

/**
 * Whether a Steam sync is genuinely in flight, given the [WorkInfo.State]s of the two unique
 * works that can run one.
 *
 * The two are read differently on purpose. A `PeriodicWorkRequest` sits in
 * [WorkInfo.State.ENQUEUED] for the *entire interval between* runs, so treating enqueued
 * periodic work as in-flight would pin the indicator on forever — the single most likely way to
 * ship a header that spins for fifteen minutes at a time. Periodic work therefore counts only
 * while [WorkInfo.State.RUNNING]. One-time work is the opposite case: a manual "Sync now" is
 * expedited and deserves feedback from the moment it is enqueued, so enqueued counts there.
 */
internal fun isSyncInProgress(
    oneTimeStates: List<WorkInfo.State>,
    periodicStates: List<WorkInfo.State>,
): Boolean =
    oneTimeStates.any { it == WorkInfo.State.ENQUEUED || it == WorkInfo.State.RUNNING } ||
        periodicStates.any { it == WorkInfo.State.RUNNING }

/**
 * Hold a `true` for at least [holdMillis] past the moment it would otherwise fall to `false`,
 * so a sync that completes in a few hundred milliseconds still produces a perceptible cue
 * rather than a flicker.
 *
 * Deliberately an operator on the flow rather than logic in a composable: the indicator stays a
 * dumb renderer, and the timing is unit-testable with a test dispatcher instead of needing a
 * Compose test. The very first emission is never delayed — an app that opens with no sync
 * running should not spend [holdMillis] undecided. A `true` arriving during the hold cancels it,
 * so back-to-back syncs read as one continuous run rather than blinking between them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun Flow<Boolean>.holdTrue(holdMillis: Long = SYNC_INDICATOR_HOLD_MS): Flow<Boolean> =
    distinctUntilChanged()
        .withIndex()
        .transformLatest { (index, inProgress) ->
            if (!inProgress && index > 0) delay(holdMillis)
            emit(inProgress)
        }
        .distinctUntilChanged()
