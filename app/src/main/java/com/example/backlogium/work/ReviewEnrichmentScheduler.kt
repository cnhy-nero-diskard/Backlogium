package com.example.backlogium.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Enqueues one unique Steam review chain, independent of authenticated Steam sync and of the
 * Store genre chain.
 *
 * Its own unique work name is what keeps the two chains independent: a review batch that fails
 * must not cancel or replace a genre batch that would have succeeded, and a genre chain that has
 * run out of work must not stop review enrichment from continuing (add-gap-plan-suggestions).
 */
@Singleton
class ReviewEnrichmentScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    fun ensureEnqueued() = enqueue(ExistingWorkPolicy.KEEP, 0)

    /** Called from the worker while the chain is active, appending a deliberately delayed batch. */
    fun enqueueContinuation() =
        enqueue(ExistingWorkPolicy.APPEND_OR_REPLACE, CONTINUATION_DELAY_MINUTES)

    private fun enqueue(policy: ExistingWorkPolicy, delayMinutes: Long) {
        val request = OneTimeWorkRequestBuilder<ReviewEnrichmentWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS,
            )
            .build()
        workManager.enqueueUniqueWork(ReviewEnrichmentWorker.UNIQUE_WORK_NAME, policy, request)
    }

    private companion object {
        /** Matches the Store genre chain's spacing between batches. */
        const val CONTINUATION_DELAY_MINUTES = 15L
    }
}
