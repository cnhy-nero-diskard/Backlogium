package com.example.backlogium.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.backlogium.data.notifications.ProgressAlerts
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

@HiltWorker
class ProgressAlertWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val alerts: ProgressAlerts,
    private val recap: com.example.backlogium.data.notifications.PreviousDayRecap,
    private val scheduler: ProgressAlertScheduler,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        recap.recordCurrentDay()
        alerts.dispatch()
        scheduler.scheduleNextRecap()
        Result.success()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        Result.retry()
    }
}
