package com.example.backlogium.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.backlogium.data.repo.GameGenreRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/** Network-constrained Store backfill: small sequential batches, with WorkManager backoff. */
@HiltWorker
class GenreEnrichmentWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: GameGenreRepository,
    private val scheduler: GenreEnrichmentScheduler,
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
        const val UNIQUE_WORK_NAME = "steam_store_genre_enrichment"
    }
}
