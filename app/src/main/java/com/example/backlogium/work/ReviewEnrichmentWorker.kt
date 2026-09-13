package com.example.backlogium.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.backlogium.data.repo.SteamReviewRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Network-constrained Steam review backfill: small sequential batches, with WorkManager backoff.
 *
 * A second chain alongside [GenreEnrichmentWorker] rather than one combined per-game Store worker
 * (add-gap-plan-suggestions). A combined worker would let one endpoint's failure stop durable
 * progress from the other, and the two endpoints fail independently often enough for that to
 * matter. The cost is stated rather than hidden: two chains against one host through one shared
 * OkHttp client double the app's worst-case Store request rate, from 25 per 15 minutes to 50.
 * Both stop a batch on the first transient failure, and an HTTP 429 arrives as an `HttpException`
 * and is already classified as transient, so a throttled client backs off rather than hammering.
 */
@HiltWorker
class ReviewEnrichmentWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: SteamReviewRepository,
    private val scheduler: ReviewEnrichmentScheduler,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = try {
        val batch = repository.enrichNextBatch()
        if (batch.transientFailure) Result.retry()
        else {
            if (batch.hasMoreEligible) scheduler.enqueueContinuation()
            Result.success()
        }
    } catch (_: Exception) {
        Result.retry()
    }

    companion object {
        const val UNIQUE_WORK_NAME = "steam_store_review_enrichment"
    }
}
