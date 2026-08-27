package com.example.backlogium.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.backlogium.data.repo.PlaySessionEnd
import com.example.backlogium.data.repo.PlaySessionEndPublisher
import com.example.backlogium.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Enqueues the post-play playtime fetch: one attempt at a time, under one unique work name per
 * app id, for as long as the game's live schedule has attempts left.
 *
 * The schedule is expressed as absolute offsets from the session end ([ATTEMPT_OFFSETS_MILLIS]) and
 * the inter-attempt delays are *derived* from them. Writing the delays out by hand is the mistake
 * this arrangement exists to prevent: `1m, 3m, 8m` as delays would place attempts at T+0, T+1m,
 * T+4m, T+12m rather than the intended T+0, T+1m, T+3m, T+8m.
 *
 * Because attempts chain with an `APPEND`-family policy, a successor's delay is measured from when
 * its prerequisite finished rather than from the session end, so the offsets are nominal: attempts
 * land a few seconds later than named, and nothing here depends on them being exact instants.
 */
@Singleton
class PostPlaySyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val coordinator: PostPlayGenerationCoordinator,
    private val sessionEnds: PlaySessionEndPublisher,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    /**
     * Act on every observed session end for as long as the process lives. Application-scoped
     * rather than tied to [PresenceService]: that service stops itself the moment a poll reports
     * not-in-game, which is the same observation that ends a session — a subscription living there
     * would be torn down by the very event it exists to hear.
     */
    fun observeSessionEnds() {
        scope.launch {
            sessionEnds.events.collect { schedule(it) }
        }
    }

    /**
     * Start a new schedule for the game that just stopped, replacing any pending one for it.
     *
     * `REPLACE` is deliberate here, and cancelling a running attempt of an *older* schedule is the
     * wanted behaviour: quitting the same game again supersedes the earlier schedule rather than
     * running two. Cancellation is only cleanup, though — [PostPlayGenerationCoordinator] holds the
     * generation that actually stops a superseded attempt from committing or extending itself.
     */
    suspend fun schedule(sessionEnd: PlaySessionEnd) {
        coordinator.startSchedule(sessionEnd.appId) { generation ->
            enqueue(
                appId = sessionEnd.appId,
                attempt = 0,
                sessionEndAt = sessionEnd.endedAt,
                generation = generation,
                delayMillis = 0,
                policy = ExistingWorkPolicy.REPLACE,
            )
        }
    }

    /**
     * Append the attempt after [attempt] to the schedule already running under this app's name.
     *
     * Never `REPLACE`: that policy cancels all unfinished work under the name, and the worker
     * calling this is unfinished — a successor would cancel the predecessor that created it, mid
     * execution. `APPEND_OR_REPLACE` rather than plain `APPEND` because a previous schedule under
     * this name may have ended cancelled by a supersede, and `APPEND` would leave the successor
     * blocked behind those cancelled prerequisites forever.
     */
    suspend fun enqueueSuccessor(appId: Long, attempt: Int, sessionEndAt: Long, generation: Long) {
        val next = attempt + 1
        require(next in ATTEMPT_OFFSETS_MILLIS.indices) { "attempt $next is past the schedule" }
        enqueue(
            appId = appId,
            attempt = next,
            sessionEndAt = sessionEndAt,
            generation = generation,
            delayMillis = delayBefore(next),
            policy = ExistingWorkPolicy.APPEND_OR_REPLACE,
        )
    }

    private fun enqueue(
        appId: Long,
        attempt: Int,
        sessionEndAt: Long,
        generation: Long,
        delayMillis: Long,
        policy: ExistingWorkPolicy,
    ) {
        val request = OneTimeWorkRequestBuilder<PostPlaySyncWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .setInputData(
                workDataOf(
                    PostPlaySyncWorker.KEY_APP_ID to appId,
                    PostPlaySyncWorker.KEY_ATTEMPT to attempt,
                    PostPlaySyncWorker.KEY_SESSION_END_AT to sessionEndAt,
                    PostPlaySyncWorker.KEY_GENERATION to generation,
                ),
            )
            // No expedited quota and no foreground service: the schedule's own delays make
            // expedited execution meaningless, and a playtime read is not worth either.
            .build()
        workManager.enqueueUniqueWork(uniqueWorkName(appId), policy, request)
    }

    companion object {
        /**
         * Absolute offsets from the observed session end. Front-loaded because Steam usually
         * publishes a session's playtime within a couple of minutes, with a longer tail — linear
         * retries would spend every attempt inside the window where the answer is already known.
         */
        val ATTEMPT_OFFSETS_MILLIS: List<Long> = listOf(
            0L,
            TimeUnit.MINUTES.toMillis(1),
            TimeUnit.MINUTES.toMillis(3),
            TimeUnit.MINUTES.toMillis(8),
        )

        /** How many attempts one schedule makes before it is exhausted. */
        val ATTEMPT_COUNT: Int = ATTEMPT_OFFSETS_MILLIS.size

        /** One unique work name per app id, so two games' schedules never replace each other. */
        fun uniqueWorkName(appId: Long): String = "post-play-sync-$appId"

        /** The delay to enqueue [attempt] with: the gap from its predecessor, not its own offset. */
        fun delayBefore(attempt: Int): Long =
            ATTEMPT_OFFSETS_MILLIS[attempt] - ATTEMPT_OFFSETS_MILLIS[attempt - 1]

        /** True when [attempt] is the schedule's last, and therefore enqueues no successor. */
        fun isLastAttempt(attempt: Int): Boolean = attempt >= ATTEMPT_COUNT - 1
    }
}
