package com.example.backlogium.work

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.backlogium.domain.OperationCounts
import com.example.backlogium.domain.OperationPhase
import com.example.backlogium.domain.OperationProgress
import com.example.backlogium.domain.OperationStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.operationReportDataStore by preferencesDataStore(name = "operation_reports")

internal enum class OperationChannel(val key: String) {
    STEAM("steam"), ACHIEVEMENTS("achievements"),
}

internal enum class OperationSource(val key: String) {
    MANUAL("manual"), PERIODIC("periodic"), LEGACY("legacy"),
}

internal data class StoredOperationReport(
    val workId: String,
    val generation: Long,
    val startedAt: Long,
    val progress: OperationProgress,
    val source: OperationSource = OperationSource.PERIODIC,
)

/** Keeps one-time and periodic executions independent from WorkManager's sleeping requests. */
@Singleton
class OperationReportStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val store = context.operationReportDataStore

    internal fun observe(channel: OperationChannel): Flow<List<StoredOperationReport>> = store.data.map { prefs ->
        val reports = listOf(OperationSource.MANUAL, OperationSource.PERIODIC)
            .mapNotNull { source -> prefs.report(channel, source) }
        val legacy = prefs.report(channel, OperationSource.LEGACY)
        if (legacy == null || reports.any { it.workId == legacy.workId }) reports else reports + legacy
    }

    internal suspend fun begin(
        channel: OperationChannel,
        source: OperationSource,
        workId: String,
        generation: Long,
        retry: Boolean,
    ): StoredOperationReport {
        var result: StoredOperationReport? = null
        store.edit { prefs ->
            val prior = prefs.report(channel, source)
                ?: prefs.report(channel, OperationSource.LEGACY)?.takeIf {
                    it.workId == workId && it.generation == generation
                }
            val resume = retry && prior != null && prior.workId == workId && prior.generation == generation &&
                prior.progress.status in setOf(OperationStatus.RUNNING, OperationStatus.RETRYING)
            val executionId = if (resume) prior!!.progress.id else "$generation:${UUID.randomUUID()}"
            val latestStart = (listOf(OperationSource.MANUAL, OperationSource.PERIODIC)
                .mapNotNull { prefs.report(channel, it) } + listOfNotNull(prefs.report(channel, OperationSource.LEGACY)))
                .filter { it.generation == generation }
                .maxOfOrNull { it.startedAt }
            val startedAt = if (resume) prior!!.startedAt
                else maxOf(System.currentTimeMillis(), latestStart?.plus(1) ?: 0L)
            result = StoredOperationReport(
                workId = workId,
                generation = generation,
                startedAt = startedAt,
                progress = OperationProgress(executionId, OperationStatus.RUNNING, OperationPhase.PREPARING),
                source = source,
            )
            prefs.write(channel, result)
        }
        return checkNotNull(result)
    }

    internal suspend fun save(channel: OperationChannel, report: StoredOperationReport) {
        store.edit { prefs ->
            val prior = prefs.report(channel, report.source)
                ?: prefs.report(channel, OperationSource.LEGACY)?.takeIf {
                    it.workId == report.workId && it.generation == report.generation
                }
            if (prior == null || report.generation > prior.generation ||
                (report.generation == prior.generation &&
                    ((report.progress.id == prior.progress.id && prior.progress.active) ||
                        report.startedAt > prior.startedAt))
            ) {
                prefs.write(channel, report)
            }
        }
    }

    internal suspend fun clearForTest() = store.edit { it.clear() }
}

private fun Preferences.report(channel: OperationChannel, source: OperationSource): StoredOperationReport? {
    val prefix = if (source == OperationSource.LEGACY) channel.key else "${channel.key}_${source.key}"
    val workId = this[stringPreferencesKey("${prefix}_work_id")] ?: return null
    val executionId = this[stringPreferencesKey("${prefix}_execution_id")] ?: return null
    val generation = this[longPreferencesKey("${prefix}_generation")] ?: return null
    val startedAt = this[longPreferencesKey("${prefix}_started_at")] ?: return null
    val status = this[stringPreferencesKey("${prefix}_status")]?.let {
        OperationStatus.entries.firstOrNull { status -> status.name == it }
    } ?: return null
    val phase = this[stringPreferencesKey("${prefix}_phase")]?.let { name ->
        OperationPhase.entries.firstOrNull { it.name == name }
    }
    val total = this[intPreferencesKey("${prefix}_total")] ?: -1
    val counts = total.takeIf { it >= 0 }?.let {
        OperationCounts(
            processed = this[intPreferencesKey("${prefix}_processed")] ?: 0,
            total = it,
            skipped = this[intPreferencesKey("${prefix}_skipped")] ?: 0,
            failed = this[intPreferencesKey("${prefix}_failed")] ?: 0,
        )
    }
    return StoredOperationReport(
        workId,
        generation,
        startedAt,
        OperationProgress(executionId, status, phase, counts,
            (this[intPreferencesKey("${prefix}_updated")] ?: -1).takeIf { it >= 0 }),
        source,
    )
}

private fun androidx.datastore.preferences.core.MutablePreferences.write(
    channel: OperationChannel,
    report: StoredOperationReport,
) {
    val prefix = if (report.source == OperationSource.LEGACY) channel.key
        else "${channel.key}_${report.source.key}"
    val progress = report.progress
    this[stringPreferencesKey("${prefix}_work_id")] = report.workId
    this[stringPreferencesKey("${prefix}_execution_id")] = progress.id
    this[longPreferencesKey("${prefix}_generation")] = report.generation
    this[longPreferencesKey("${prefix}_started_at")] = report.startedAt
    this[stringPreferencesKey("${prefix}_status")] = progress.status.name
    progress.phase?.let { this[stringPreferencesKey("${prefix}_phase")] = it.name }
        ?: remove(stringPreferencesKey("${prefix}_phase"))
    val counts = progress.counts
    this[intPreferencesKey("${prefix}_processed")] = counts?.processed ?: -1
    this[intPreferencesKey("${prefix}_total")] = counts?.total ?: -1
    this[intPreferencesKey("${prefix}_skipped")] = counts?.skipped ?: 0
    this[intPreferencesKey("${prefix}_failed")] = counts?.failed ?: 0
    this[intPreferencesKey("${prefix}_updated")] = progress.updated ?: -1
}
