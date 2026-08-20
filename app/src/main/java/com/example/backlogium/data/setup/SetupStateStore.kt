package com.example.backlogium.data.setup

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.backlogium.work.setup.SetupOutcome
import com.example.backlogium.work.setup.decodeSetupOutcome
import com.example.backlogium.work.setup.encodeSetupOutcome
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Durable first-run-setup state: whether setup has been run, and each stage's last recorded
 * outcome.
 *
 * Read and written only through [SetupStateRepository] — no UI touches this type, per the
 * repository boundary the app keeps everywhere else.
 */
data class ActiveSetupStage(
    val stageId: String,
    val workId: String?,
    val selectedStageIds: Set<String> = emptySet(),
)

interface SetupStateStore {
    /** True once setup has been completed or declined at least once. Gates nothing; informational. */
    val completedFlow: Flow<Boolean>

    /** Every stored per-stage outcome, keyed by stage id, including ids this build may not know. */
    suspend fun storedOutcomes(): Map<String, SetupOutcome>

    /**
     * What the user last opted into, keyed by stage id. Read when the coordinator is constructed so
     * a setup surface recreated later — including in a new process, after the one that started
     * setup was killed — still reports which stages the run covered rather than an empty selection.
     */
    suspend fun storedOptIns(): Map<String, Boolean>
    /** The stage whose WorkManager job may still be running after process death. */
    suspend fun storedActiveStage(): ActiveSetupStage?

    /** Atomically marks a new stage attempt and clears any previous WorkManager id. */
    suspend fun markStageStarted(
        stageId: String,
        selectedStageIds: Set<String> = setOf(stageId),
    )

    /** Associates the exact WorkManager job observed for the active stage. */
    suspend fun markStageWorkStarted(stageId: String, workId: String)

    suspend fun clearActiveStage()

    suspend fun writeOutcome(stageId: String, outcome: SetupOutcome)

    suspend fun writeOptIn(stageId: String, optIn: Boolean)

    suspend fun markCompleted()
}

private val Context.setupDataStore by preferencesDataStore(name = "setup")

/**
 * Preferences-DataStore-backed [SetupStateStore].
 *
 * A file of its own rather than keys in `SettingsDataStore`: outcomes are keyed by stage id, so the
 * key set is open-ended, and `SettingsDataStore`'s value is that its `Keys` object enumerates every
 * field it round-trips. An open-ended prefix scan inside it would quietly break that property.
 *
 * The absence of every key means "setup never run", which is both true and harmless for an existing
 * install: nothing gates on it.
 */
@Singleton
class DataStoreSetupStateStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : SetupStateStore {

    override val completedFlow: Flow<Boolean> = context.setupDataStore.data.map { prefs ->
        prefs[COMPLETED_KEY] ?: false
    }

    override suspend fun storedOutcomes(): Map<String, SetupOutcome> {
        val prefs = context.setupDataStore.data.first()
        return prefs.asMap()
            .mapNotNull { (key, value) ->
                val id = key.name.stageIdAfter(OUTCOME_PREFIX) ?: return@mapNotNull null
                val encoded = value as? String ?: return@mapNotNull null
                id to decodeSetupOutcome(encoded)
            }
            .toMap()
    }

    override suspend fun storedOptIns(): Map<String, Boolean> {
        val prefs = context.setupDataStore.data.first()
        return prefs.asMap()
            .mapNotNull { (key, value) ->
                val id = key.name.stageIdAfter(OPT_IN_PREFIX) ?: return@mapNotNull null
                val optIn = value as? Boolean ?: return@mapNotNull null
                id to optIn
            }
            .toMap()
    }

    override suspend fun storedActiveStage(): ActiveSetupStage? {
        val prefs = context.setupDataStore.data.first()
        val stageId = prefs[ACTIVE_STAGE_KEY] ?: return null
        return ActiveSetupStage(
            stageId = stageId,
            workId = prefs[ACTIVE_WORK_KEY],
            selectedStageIds = prefs[ACTIVE_SELECTION_KEY].orEmpty(),
        )
    }

    override suspend fun markStageStarted(
        stageId: String,
        selectedStageIds: Set<String>,
    ) {
        context.setupDataStore.edit { prefs ->
            prefs[ACTIVE_STAGE_KEY] = stageId
            prefs[ACTIVE_SELECTION_KEY] = selectedStageIds
            prefs.remove(ACTIVE_WORK_KEY)
            prefs[outcomeKey(stageId)] = encodeSetupOutcome(SetupOutcome.NeverRun)
        }
    }

    override suspend fun markStageWorkStarted(stageId: String, workId: String) {
        context.setupDataStore.edit { prefs ->
            if (prefs[ACTIVE_STAGE_KEY] == stageId) prefs[ACTIVE_WORK_KEY] = workId
        }
    }

    override suspend fun clearActiveStage() {
        context.setupDataStore.edit { prefs ->
            prefs.remove(ACTIVE_STAGE_KEY)
            prefs.remove(ACTIVE_SELECTION_KEY)
            prefs.remove(ACTIVE_WORK_KEY)
        }
    }

    override suspend fun writeOutcome(stageId: String, outcome: SetupOutcome) {
        context.setupDataStore.edit { prefs ->
            prefs[outcomeKey(stageId)] = encodeSetupOutcome(outcome)
        }
    }

    override suspend fun writeOptIn(stageId: String, optIn: Boolean) {
        context.setupDataStore.edit { prefs -> prefs[optInKey(stageId)] = optIn }
    }

    override suspend fun markCompleted() {
        context.setupDataStore.edit { prefs -> prefs[COMPLETED_KEY] = true }
    }

    private companion object {
        const val OUTCOME_PREFIX = "stage_outcome_"
        const val OPT_IN_PREFIX = "stage_opt_in_"
        val ACTIVE_STAGE_KEY = stringPreferencesKey("active_stage_id")
        val ACTIVE_SELECTION_KEY = stringSetPreferencesKey("active_stage_selection")
        val ACTIVE_WORK_KEY = stringPreferencesKey("active_work_id")
        val COMPLETED_KEY = booleanPreferencesKey("setup_completed")

        fun outcomeKey(stageId: String): Preferences.Key<String> =
            stringPreferencesKey(OUTCOME_PREFIX + stageId)

        fun optInKey(stageId: String): Preferences.Key<Boolean> =
            booleanPreferencesKey(OPT_IN_PREFIX + stageId)

        /** The stage id a prefixed key names, or null when the key isn't one of ours. */
        fun String.stageIdAfter(prefix: String): String? =
            if (startsWith(prefix)) removePrefix(prefix).takeIf { it.isNotEmpty() } else null
    }
}
