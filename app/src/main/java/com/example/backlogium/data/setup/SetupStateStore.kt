package com.example.backlogium.data.setup

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.backlogium.work.setup.FirstRunPhase
import com.example.backlogium.work.setup.SetupOutcome
import com.example.backlogium.work.setup.decodeSetupOutcome
import com.example.backlogium.work.setup.encodeSetupOutcome
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class SetupOwner(val steamId: String, val generation: Long)

@Serializable
data class SetupStageRecord(
    val requestId: String? = null,
    val workId: String? = null,
    val encodedOutcome: String? = null,
) {
    val outcome: SetupOutcome get() = decodeSetupOutcome(encodedOutcome)
    val pending: Boolean get() = requestId != null && encodedOutcome == null
}

@Serializable
data class SetupJournal(
    val owner: SetupOwner,
    val stages: Map<String, SetupStageRecord> = emptyMap(),
    val selected: Set<String> = emptySet(),
    val remaining: List<String> = emptyList(),
    val runOpen: Boolean = false,
    val settled: Boolean = false,
)

data class FirstRunSetupRecord(val phase: FirstRunPhase, val owner: SetupOwner?) {
    fun belongsTo(activeOwner: SetupOwner): Boolean = owner == activeOwner ||
        (owner == null && activeOwner.generation == 0L)
}

interface SetupStateStore {
    val firstRunRecordFlow: Flow<FirstRunSetupRecord?>
    val firstRunPhaseFlow get() = firstRunRecordFlow.map { it?.phase }
    val firstRunSetupActiveFlow get() = firstRunPhaseFlow.map { it != null }
    suspend fun advanceToHistory(owner: SetupOwner? = null)
    suspend fun setFirstRunSetupActive(active: Boolean, owner: SetupOwner? = null)

    suspend fun update(
        owner: SetupOwner,
        finishRun: Boolean = false,
        transform: (SetupJournal) -> SetupJournal,
    ): SetupJournal
}

private val Context.setupDataStore by preferencesDataStore(name = "setup")

@Singleton
class DataStoreSetupStateStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : SetupStateStore {
    override val firstRunRecordFlow: Flow<FirstRunSetupRecord?> = context.setupDataStore.data.map(::firstRunRecord)

    override suspend fun setFirstRunSetupActive(active: Boolean, owner: SetupOwner?) {
        context.setupDataStore.edit { prefs ->
            if (active) {
                prefs[FIRST_RUN_PHASE] = FirstRunPhase.SETUP.name
                owner?.let { prefs[FIRST_RUN_OWNER] = json.encodeToString(it) }
            } else {
                if (owner != null && firstRunRecord(prefs)?.belongsTo(owner) != true) return@edit
                prefs.remove(FIRST_RUN_PHASE)
                prefs.remove(FIRST_RUN_OWNER)
            }
            prefs.remove(LEGACY_FIRST_RUN_ACTIVE)
        }
    }

    override suspend fun advanceToHistory(owner: SetupOwner?) {
        context.setupDataStore.edit { prefs ->
            if (canAdvance(prefs, owner)) prefs[FIRST_RUN_PHASE] = FirstRunPhase.HISTORY.name
        }
    }

    override suspend fun update(
        owner: SetupOwner,
        finishRun: Boolean,
        transform: (SetupJournal) -> SetupJournal,
    ): SetupJournal {
        val updated = context.setupDataStore.edit { prefs ->
            val previous = prefs[JOURNAL]?.let { json.decodeFromString<SetupJournal>(it) }
                ?: migrateLegacy(prefs, owner)
            val snapshot = previous.takeIf { it.owner == owner } ?: SetupJournal(owner)
            prefs[JOURNAL] = json.encodeToString(transform(snapshot))
            val firstRun = firstRunRecord(prefs)
            if (firstRun?.owner == null && firstRun?.belongsTo(owner) == true) {
                prefs[FIRST_RUN_OWNER] = json.encodeToString(owner)
            }
            if (finishRun && canAdvance(prefs, owner)) {
                prefs[FIRST_RUN_PHASE] = FirstRunPhase.HISTORY.name
            }
        }
        return json.decodeFromString(checkNotNull(updated[JOURNAL]))
    }

    private fun migrateLegacy(prefs: Preferences, owner: SetupOwner): SetupJournal {
        if (owner.generation != 0L) return SetupJournal(owner)

        val stages = prefs.asMap().mapNotNull { (key, value) ->
            val id = key.name.removePrefix("stage_outcome_")
            if (id == key.name || value !is String) null else id to SetupStageRecord(encodedOutcome = value)
        }.toMap().toMutableMap()
        val optIns = prefs.asMap().filter { (key, value) ->
            key.name.startsWith("stage_opt_in_") && value == true
        }.keys.mapTo(mutableSetOf()) { it.name.removePrefix("stage_opt_in_") }
        val active = prefs[LEGACY_ACTIVE_STAGE]
        if (active == null) return SetupJournal(owner, stages, optIns)

        val workId = prefs[LEGACY_ACTIVE_WORK]?.takeIf { owner.generation == 0L }
        stages[active] = if (workId == null) {
            SetupStageRecord(encodedOutcome = encodeSetupOutcome(SetupOutcome.RecoveryRequired))
        } else {
            SetupStageRecord(requestId = UUID.randomUUID().toString(), workId = workId)
        }
        val selected = prefs[LEGACY_ACTIVE_SELECTION].orEmpty().ifEmpty { setOf(active) }
        val remaining = selected.filter { it != active && (stages[it]?.outcome ?: SetupOutcome.NeverRun) == SetupOutcome.NeverRun }
        return SetupJournal(owner, stages, selected, remaining, runOpen = true)
    }

    private fun firstRunRecord(prefs: Preferences): FirstRunSetupRecord? {
        val phase = restoredFirstRunPhase(prefs[FIRST_RUN_PHASE], prefs[LEGACY_FIRST_RUN_ACTIVE] == true)
            ?: return null
        return FirstRunSetupRecord(phase, prefs[FIRST_RUN_OWNER]?.let { json.decodeFromString<SetupOwner>(it) })
    }

    private fun canAdvance(prefs: Preferences, owner: SetupOwner?): Boolean {
        val record = firstRunRecord(prefs) ?: return false
        return record.phase == FirstRunPhase.SETUP && (owner == null || record.belongsTo(owner))
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        val JOURNAL = stringPreferencesKey("stage_journal")
        val LEGACY_ACTIVE_STAGE = stringPreferencesKey("active_stage_id")
        val LEGACY_ACTIVE_SELECTION = stringSetPreferencesKey("active_stage_selection")
        val LEGACY_ACTIVE_WORK = stringPreferencesKey("active_work_id")
        val LEGACY_FIRST_RUN_ACTIVE = booleanPreferencesKey("first_run_setup_active")
        val FIRST_RUN_OWNER = stringPreferencesKey("first_run_owner")
        val FIRST_RUN_PHASE = stringPreferencesKey("first_run_phase")
    }
}

internal fun restoredFirstRunPhase(stored: String?, legacyActive: Boolean): FirstRunPhase? =
    FirstRunPhase.entries.firstOrNull { it.name == stored }
        ?: if (legacyActive) FirstRunPhase.SETUP else null
