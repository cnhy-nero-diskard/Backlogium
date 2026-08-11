package com.example.backlogium.data.diagnostics

import com.example.backlogium.data.local.dao.DiagnosticsDao
import com.example.backlogium.data.local.entity.PresenceDecision
import com.example.backlogium.data.local.entity.RequestBreakdown
import com.example.backlogium.data.local.entity.SyncRun
import com.example.backlogium.domain.TimeProvider
import javax.inject.Inject
import javax.inject.Singleton

object DiagnosticRedaction {
    private val secretParameters = setOf("key", "steamids")

    fun requestIdentifier(url: okhttp3.HttpUrl): String = url.newBuilder().apply {
        secretParameters.forEach { parameter ->
            if (url.queryParameter(parameter) != null) {
                removeAllQueryParameters(parameter)
                addQueryParameter(parameter, "[redacted]")
            }
        }
    }.build().toString()
}

data class RequestMetrics(val count: Int = 0, val durationMs: Long = 0)

/**
 * The fixed set of ways a sync run can end. A `String` in [SyncRun.outcome] would let any call
 * site invent a new, unlisted value; this enum is where the fixed set is enforced. The column
 * itself stays a plain `String` ([value]) so introducing this needed no Room migration — see
 * design.md's "Outcome is enforced in code, not in the schema".
 */
enum class SyncOutcome(val value: String) {
    SUCCESS("success"),
    FAILED("failed"),
    INCOMPLETE("incomplete"),
    SKIPPED_NO_CREDENTIALS("skipped:no_credentials"),
    SKIPPED_EMPTY_OWNED_GAMES("skipped:empty_owned_games"),
}

/**
 * Records one sync/reconciliation run as an independent [RunScope] rather than tracking a single
 * ambient "current" run: [SteamSyncWorker][com.example.backlogium.work.SteamSyncWorker] and
 * [ReconciliationWorker][com.example.backlogium.work.ReconciliationWorker] share this singleton but
 * can genuinely run concurrently — reconciliation takes minutes and runs on its own schedule
 * independent of the periodic sync — so there is no single "the current run" to be ambient about.
 * A scope from [begin] is threaded explicitly through the calls that make up that run (as
 * [SteamApi][com.example.backlogium.data.remote.SteamApi]'s `@Tag scope` parameter) and
 * [RedactingTimingInterceptor] reads it back off the request to attribute each one correctly, so
 * two overlapping runs' metrics cannot cross-contaminate and neither can silently fail to persist
 * because the other one "became the active one" in the meantime.
 */
@Singleton
class SyncRunRecorder @Inject constructor(
    private val dao: DiagnosticsDao,
    private val time: TimeProvider,
) {
    fun begin(trigger: String): RunScope = RunScope(trigger, time.nowMillis())

    suspend fun finish(scope: RunScope, outcome: SyncOutcome, errorMessage: String?, gamesExamined: Int, gamesUpdated: Int) {
        val endedAt = time.nowMillis()
        dao.insertRun(
            SyncRun(
                startedAt = scope.startedAt,
                durationMs = (endedAt - scope.startedAt).coerceAtLeast(0),
                trigger = scope.trigger,
                requestCount = scope.metrics.values.sumOf { it.count },
                requestMillis = scope.metrics.values.sumOf { it.durationMs },
                gamesExamined = gamesExamined,
                gamesUpdated = gamesUpdated,
                outcome = outcome.value,
                errorMessage = errorMessage,
                hotCount = scope.tiers.hot,
                warmCount = scope.tiers.warm,
                coldCount = scope.tiers.cold,
                neverCount = scope.tiers.never,
            ),
            scope.metrics.map { (key, value) ->
                RequestBreakdown(0, 0, key.endpoint, key.status, value.count, value.durationMs)
            },
        )
        runCatching { dao.pruneRuns(RETAINED_RUNS) }
    }

    /**
     * One run's own metrics, held directly rather than in any shared/ambient state — safe to
     * mutate without synchronization because each worker issues its requests serially (see
     * `AchievementRepository.fetchGames`), so a single scope is never written from two coroutines
     * at once even though two *different* scopes can be in flight at the same time.
     */
    class RunScope internal constructor(val trigger: String, val startedAt: Long) {
        internal data class Key(val endpoint: String, val status: Int?)
        internal data class TierCounts(var hot: Int = 0, var warm: Int = 0, var cold: Int = 0, var never: Int = 0)
        internal val metrics = linkedMapOf<Key, RequestMetrics>()
        internal val tiers = TierCounts()

        internal fun recordRequest(endpoint: String, status: Int?, durationMs: Long) {
            val key = Key(endpoint, status)
            val prior = metrics[key] ?: RequestMetrics()
            metrics[key] = prior.copy(count = prior.count + 1, durationMs = prior.durationMs + durationMs)
        }

        fun recordTiers(hot: Int, warm: Int, cold: Int, never: Int) {
            tiers.hot = hot
            tiers.warm = warm
            tiers.cold = cold
            tiers.never = never
        }
    }

    companion object { const val RETAINED_RUNS = 200 }
}

/**
 * The fixed set of branches presence detection can take, mirroring [LiveStatusRepository.fetch]'s
 * branches one-to-one — the six-way ambiguity that motivated this change in the first place. As
 * with [SyncOutcome], the enum is the enforcement point; [PresenceDecision.outcome] stays a plain
 * `String` ([value]) to avoid a schema migration.
 */
enum class PresenceOutcome(val value: String) {
    IN_GAME("in_game"),
    NOT_PLAYING("not_playing"),
    NO_CREDENTIALS("no_credentials"),
    NO_PLAYER("no_player"),
    FAILED("failed"),
}

@Singleton
class PresenceDecisionRecorder @Inject constructor(
    private val dao: DiagnosticsDao,
    private val time: TimeProvider,
) {
    suspend fun record(trigger: String, outcome: PresenceOutcome, appId: Long? = null, retainedPriorState: Boolean = false) {
        runCatching {
            dao.insertPresenceDecision(PresenceDecision(0, time.nowMillis(), trigger, outcome.value, appId, retainedPriorState))
            dao.prunePresenceDecisions(RETAINED_DECISIONS)
        }
    }

    companion object { const val RETAINED_DECISIONS = 1_000 }
}
