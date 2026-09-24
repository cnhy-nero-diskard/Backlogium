package com.example.backlogium.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf
import com.example.backlogium.data.credentials.CloudCredentialsStore
import com.example.backlogium.data.repo.CredentialsProvider
import com.example.backlogium.data.repo.CloudRoutinePolicy
import com.example.backlogium.data.repo.SettingsRepository
import com.example.backlogium.data.repo.CloudPresenceRepository
import com.example.backlogium.data.repo.PlaySessionEnd
import com.example.backlogium.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CloudRoutineScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val reader: CloudPresenceRepository,
    private val settings: SettingsRepository,
    private val credentials: CredentialsProvider,
    private val cloudCredentials: CloudCredentialsStore,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val manager: WorkManager get() = WorkManager.getInstance(context)

    /** Observes local configuration only; a policy change updates periodic work without a read. */
    fun observeConfiguration() {
        scope.launch {
            try {
                combine(reader.configuration, settings.cloudRoutineState, settings.cloudReaderGeneration) {
                        configuration, state, generation -> Triple(configuration, state.policy, generation)
                    }
                    .distinctUntilChanged()
                    .collect { (configuration, policy, generation) ->
                        if (configuration == null || policy == null) {
                            manager.cancelUniqueWork(CloudRoutineCatchUpWorker.PERIODIC_NAME)
                        } else {
                            val account = credentials.currentCredentials()?.steamId
                            if (account == null) {
                                manager.cancelUniqueWork(CloudRoutineCatchUpWorker.PERIODIC_NAME)
                            } else {
                                ensurePeriodicOpportunity(account, generation, policy)
                            }
                        }
                    }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // Failed local setup must not crash unrelated app work; retry on next startup.
                Timber.w(error, "Cloud routine scheduling failed")
            }
        }
    }

    private suspend fun ensurePeriodicOpportunity(account: String, generation: Long, policy: CloudRoutinePolicy) {
        enqueuePeriodic(manager, account, generation, policy)
    }

    /** Called after a local observed play end; WorkManager waits for connectivity. */
    suspend fun enqueueAfterPlayEnd(end: PlaySessionEnd) {
        if (credentials.currentCredentials()?.steamId != end.steamId ||
            cloudCredentials.readCloudCredentials() == null ||
            settings.cloudRoutineState.first().policy == null
        ) return
        enqueueOpportunity(end.steamId, settings.cloudReaderGeneration.first())
    }

    /** Unique one-time work absorbs duplicate opportunities, including one already running. */
    suspend fun enqueueOpportunity(account: String, generation: Long) {
        enqueueOneTime(manager, account, generation)
    }

    companion object {
        internal suspend fun enqueuePeriodic(
            manager: WorkManager, account: String, generation: Long, policy: CloudRoutinePolicy,
        ) {
            val request = PeriodicWorkRequestBuilder<CloudRoutineCatchUpWorker>(
                policy.periodicHours, TimeUnit.HOURS,
            )
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf(
                    CloudRoutineCatchUpWorker.KEY_ACCOUNT to account,
                    CloudRoutineCatchUpWorker.KEY_GENERATION to generation,
                ))
                .build()
            manager.enqueueUniquePeriodicWork(
                CloudRoutineCatchUpWorker.PERIODIC_NAME, ExistingPeriodicWorkPolicy.UPDATE, request,
            ).await()
        }

        internal suspend fun enqueueOneTime(manager: WorkManager, account: String, generation: Long) {
            val request = OneTimeWorkRequestBuilder<CloudRoutineCatchUpWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf(
                    CloudRoutineCatchUpWorker.KEY_ACCOUNT to account,
                    CloudRoutineCatchUpWorker.KEY_GENERATION to generation,
                ))
                .build()
            manager.enqueueUniqueWork(
                CloudRoutineCatchUpWorker.ONE_TIME_NAME, ExistingWorkPolicy.KEEP, request,
            ).await()
        }
    }
}
