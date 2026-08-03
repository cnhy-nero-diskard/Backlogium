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

    suspend fun finish(scope: RunScope, outcome: String, errorMessage: String?, gamesExamined: Int, gamesUpdated: Int) {
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
                    outcome = outcome,
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

@Singleton
class PresenceDecisionRecorder @Inject constructor(
    private val dao: DiagnosticsDao,
    private val time: TimeProvider,
) {
    suspend fun record(trigger: String, outcome: String, appId: Long? = null, retainedPriorState: Boolean = false) {
        runCatching {
            dao.insertPresenceDecision(PresenceDecision(0, time.nowMillis(), trigger, outcome, appId, retainedPriorState))
            dao.prunePresenceDecisions(RETAINED_DECISIONS)
        }
    }

    companion object { const val RETAINED_DECISIONS = 1_000 }
}
