package com.example.backlogium.data.diagnostics

import com.example.backlogium.data.local.dao.DiagnosticsDao
import com.example.backlogium.data.local.entity.PresenceDecision
import com.example.backlogium.data.local.entity.RequestBreakdown
import com.example.backlogium.data.local.entity.SyncRun
import com.example.backlogium.domain.TimeProvider
import java.util.concurrent.atomic.AtomicReference
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

@Singleton
class SyncRunRecorder @Inject constructor(
    private val dao: DiagnosticsDao,
    private val time: TimeProvider,
) {
    private val active = AtomicReference<RunScope?>(null)

    fun begin(trigger: String): RunScope = RunScope(trigger, time.nowMillis()).also { active.set(it) }

    fun recordRequest(endpoint: String, status: Int?, durationMs: Long) {
        active.get()?.recordRequest(endpoint, status, durationMs)
    }

    suspend fun finish(scope: RunScope, outcome: SyncOutcome, errorMessage: String?, gamesExamined: Int, gamesUpdated: Int) {
        if (active.get() !== scope) return
        try {
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
                ),
                scope.metrics.map { (key, value) ->
                    RequestBreakdown(0, 0, key.endpoint, key.status, value.count, value.durationMs)
                },
            )
            runCatching { dao.pruneRuns(RETAINED_RUNS) }
        } finally {
            active.compareAndSet(scope, null)
        }
    }

    class RunScope internal constructor(val trigger: String, val startedAt: Long) {
        internal data class Key(val endpoint: String, val status: Int?)
        internal val metrics = linkedMapOf<Key, RequestMetrics>()
        internal fun recordRequest(endpoint: String, status: Int?, durationMs: Long) {
            val key = Key(endpoint, status)
            val prior = metrics[key] ?: RequestMetrics()
            metrics[key] = prior.copy(count = prior.count + 1, durationMs = prior.durationMs + durationMs)
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
