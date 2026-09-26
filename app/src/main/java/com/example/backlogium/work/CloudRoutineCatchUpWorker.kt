package com.example.backlogium.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.backlogium.data.repo.CloudPresenceRepository
import com.example.backlogium.data.repo.CloudPresenceSessionIngestor
import com.example.backlogium.data.repo.CloudRoutineAttempt
import com.example.backlogium.data.repo.CloudCatchUpResult
import com.example.backlogium.data.repo.CloudReadSummaryOutcome
import com.example.backlogium.data.repo.SettingsRepository
import kotlinx.coroutines.flow.first
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import timber.log.Timber

@HiltWorker
class CloudRoutineCatchUpWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val reader: CloudPresenceRepository,
    private val ingestor: CloudPresenceSessionIngestor,
    private val settings: SettingsRepository,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val account = inputData.getString(KEY_ACCOUNT)?.takeIf { it.isNotBlank() }
            ?: return Result.success()
        val generation = inputData.getLong(KEY_GENERATION, -1L)
        if (generation < 0L) return Result.success()
        var admittedAt: Long? = null
        return try {
            val attempt = reader.runRoutineCatchUp(
                account = account,
                generation = generation,
                consume = { ingestor.ingest(it) },
                onAdmitted = { admittedAt = it },
            )
            if (attempt is CloudRoutineAttempt.Admitted &&
                settings.cloudReaderGeneration.first() == generation
            ) {
                val summary = when (val outcome = attempt.outcome) {
                    is CloudCatchUpResult.Complete -> if (outcome.noNewData) {
                        CloudReadSummaryOutcome.NO_NEW_DATA
                    } else CloudReadSummaryOutcome.COMPLETE
                    is CloudCatchUpResult.Partial -> CloudReadSummaryOutcome.PARTIAL
                    is CloudCatchUpResult.Failed -> CloudReadSummaryOutcome.FAILED
                    CloudCatchUpResult.Unavailable -> null
                }
                if (summary != null) settings.recordCloudRoutineOutcome(attempt.at, summary)
            }
            // A failed attempt has already consumed its shared cooldown. A future opportunity
            // resumes from its durable page position; WorkManager retries must not burst reads.
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Timber.w(error, "Routine cloud catch-up failed")
            if (admittedAt != null && settings.cloudReaderGeneration.first() == generation) {
                settings.recordCloudRoutineOutcome(admittedAt, CloudReadSummaryOutcome.FAILED)
            }
            Result.success()
        }
    }

    companion object {
        const val KEY_ACCOUNT = "cloud_routine_account"
        const val KEY_GENERATION = "cloud_routine_generation"
        const val ONE_TIME_NAME = "cloud-routine-once"
        const val PERIODIC_NAME = "cloud-routine-periodic"
    }
}
