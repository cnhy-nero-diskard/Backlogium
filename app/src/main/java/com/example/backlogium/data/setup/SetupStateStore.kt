package com.example.backlogium.data.setup

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.backlogium.work.setup.SetupOutcome
import com.example.backlogium.work.setup.decodeSetupOutcome
import com.example.backlogium.work.setup.encodeSetupOutcome
import com.example.backlogium.work.setup.outcomeOfOperation
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Durable first-run-setup state: whether setup has been run, each stage's latest recorded outcome,
 * and — since 3.2 — each stage's versioned latest-attempt record plus the running cohort.
 */

/**
 * The legacy single-active-stage marker, kept only for migration. The pre-3.2 setup store keyed one
 * active stage at a time; [SetupStateStore.readLegacyActiveMarker] converts it into the equivalent
 * versioned attempt records when the association can be established, and leaves terminal historical
 * outcomes historical when it cannot (a backoff failure without a job id is never guessed into a
 * live job).
 */
data class LegacyActiveMarker(
    val stageId: String,
    val workId: String?,
    val selectedStageIds: Set<String>,
)

interface SetupStateStore {
    /** True once setup has been completed or declined at least once. Gates nothing; informational. */
    val completedFlow: Flow<Boolean>

    /**
     * True from the moment a first configuration persists its credentials until the first-run setup
     * surface is dismissed. This is the durable half of the onboarding takeover; milestone B's
     * first-run phases are layered on top of this flag by the parent after this change.
     *
     * Absent for every install that predates this flag, which is the right answer for them: they are
     * configured and were never shown a setup step, so they must not be sent to one.
     */
    val firstRunSetupActiveFlow: Flow<Boolean>

    /**
     * Every stored historical outcome, keyed by stage id, including ids this build may not know.
     *
     * This is the legacy projection: staged terminal outcomes plus the terminal projection of every
     * stage's latest attempt record. Pending operation states are not representable here and read as
     * [SetupOutcome.NeverRun], matching the pre-3.x "in progress" interpretation.
     */
    suspend fun storedOutcomes(): Map<String, SetupOutcome>

    /**
     * What the user last opted into, keyed by stage id. Read when the coordinator is constructed so
     * a setup surface recreated later — including in a new process, after the one that started
     * setup was killed — still reports which stages the run covered rather than an empty selection.
     */
    suspend fun storedOptIns(): Map<String, Boolean>

    /**
     * Every stage's versioned latest-attempt record, keyed by stage id. Unknown stage ids are
     * retained by the store (the registry projection drops them) but never fabricated: a stage with
     * no attempt has no entry, and a record never gains an admitted job it did not have.
     */
    suspend fun storedAttemptRecords(): Map<String, StageAttemptRecord>

    /** One stage's latest attempt record, or null when the stage has never been admitted. */
    suspend fun attemptRecord(stageId: String): StageAttemptRecord?

    /** Atomically replace the stage's latest attempt record. Idempotent per stage. */
    suspend fun upsertAttempt(record: StageAttemptRecord)

    /**
     * Atomically replace the stage's attempt **only if** the stored record still matches
     * [expectedGeneration], [expectedAdmittedWorkId], and [expectedAccountMarker]. Returns false
     * when a replacement (a newer generation, a different exact work, or a different account) owns
     * the stage — a superseded observation callback must never regress the replacement. The
     * generation/work/account check and the write are one atomic edit.
     */
    suspend fun compareAndSwapAttempt(
        stageId: String,
        expectedGeneration: Long,
        expectedAdmittedWorkId: String?,
        expectedAccountMarker: String?,
        newRecord: StageAttemptRecord,
    ): Boolean

    /** Remove the stage's attempt record (used when a replacement account owns the stage). */
    suspend fun removeAttempt(stageId: String)

    /** The persisted run context: cohort id, its immutable selection, and its account owner. */
    suspend fun cohort(): SetupCohort

    /** Persist the run context before any admission intent is written. */
    suspend fun setCohort(cohortId: String, selectedStageIds: Set<String>, accountMarker: String?)

    /** A run's admission round ended (or was declined); nothing remains to resume. */
    suspend fun clearCohort()

    /** Write one stage's historical outcome. Pre-3.x callers keep writing here for compat. */
    suspend fun writeOutcome(stageId: String, outcome: SetupOutcome)

    suspend fun writeOptIn(stageId: String, optIn: Boolean)

    suspend fun markCompleted()

    suspend fun setFirstRunSetupActive(active: Boolean)

    /** The pre-3.2 active-stage marker, if an older process left one. */
    suspend fun readLegacyActiveMarker(): LegacyActiveMarker?

    /** Consume the pre-3.2 active-stage marker after it was converted or left historical. */
    suspend fun consumeLegacyActiveMarker()
}

private val Context.setupDataStore by preferencesDataStore(name = "setup")

/**
 * Preferences-DataStore-backed [SetupStateStore].
 *
 * A file of its own rather than keys in `SettingsDataStore`: attempt records and outcomes are keyed
 * by stage id, so the key set is open-ended, and `SettingsDataStore`'s value is that its `Keys`
 * object enumerates every field it round-trips. An open-ended prefix scan inside it would quietly
 * break that property.
 *
 * The absence of every key means "setup never run", which is both true and harmless for an existing
 * install: nothing gates on it.
 *
 * An attempt record is stored as a single JSON string per stage; `stageAttemptJson` tolerates
 * unknown fields, and a value this build cannot decode is dropped rather than failing setup to
 * render. The pre-3.2 keys (`stage_outcome_*`, the single active-staged marker) remain readable and
 * are converted by the coordinator on reconcile.
 */
@Singleton
class DataStoreSetupStateStore internal constructor(
    private val dataStore: DataStore<Preferences>,
) : SetupStateStore {

    @Inject constructor(@ApplicationContext context: Context) : this(context.setupDataStore)

    override val completedFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[COMPLETED_KEY] ?: false
    }

    override val firstRunSetupActiveFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[FIRST_RUN_ACTIVE_KEY] ?: false
    }

    override suspend fun storedOutcomes(): Map<String, SetupOutcome> {
        val prefs = dataStore.data.first()
        val legacy = prefs.asMap()
            .mapNotNull { (key, value) ->
                val id = key.name.stageIdAfter(OUTCOME_PREFIX) ?: return@mapNotNull null
                val encoded = value as? String ?: return@mapNotNull null
                id to decodeSetupOutcome(encoded)
            }
            .toMap()
        val fromAttempts = prefs.asMap()
            .mapNotNull { (key, value) ->
                val id = key.name.stageIdAfter(ATTEMPT_PREFIX) ?: return@mapNotNull null
                val encoded = value as? String ?: return@mapNotNull null
                decodeStageAttempt(encoded)?.let { id to outcomeOfOperation(it.operation) }
            }
            .toMap()
        return legacy + fromAttempts
    }

    override suspend fun storedOptIns(): Map<String, Boolean> {
        val prefs = dataStore.data.first()
        return prefs.asMap()
            .mapNotNull { (key, value) ->
                val id = key.name.stageIdAfter(OPT_IN_PREFIX) ?: return@mapNotNull null
                val optIn = value as? Boolean ?: return@mapNotNull null
                id to optIn
            }
            .toMap()
    }

    override suspend fun storedAttemptRecords(): Map<String, StageAttemptRecord> {
        val prefs = dataStore.data.first()
        return prefs.asMap()
            .mapNotNull { (key, value) ->
                val id = key.name.stageIdAfter(ATTEMPT_PREFIX) ?: return@mapNotNull null
                val encoded = value as? String ?: return@mapNotNull null
                decodeStageAttempt(encoded)?.takeIf { it.stageId == id }?.let { id to it }
            }
            .toMap()
    }

    override suspend fun attemptRecord(stageId: String): StageAttemptRecord? =
        storedAttemptRecords()[stageId]

    override suspend fun upsertAttempt(record: StageAttemptRecord) {
        dataStore.edit { prefs ->
            prefs[attemptKey(record.stageId)] = encodeStageAttempt(record)
        }
    }

    override suspend fun compareAndSwapAttempt(
        stageId: String,
        expectedGeneration: Long,
        expectedAdmittedWorkId: String?,
        expectedAccountMarker: String?,
        newRecord: StageAttemptRecord,
    ): Boolean {
        var swapped = false
        dataStore.edit { prefs ->
            val current = prefs[attemptKey(stageId)]?.let(::decodeStageAttempt)
            if (current != null &&
                current.generation == expectedGeneration &&
                current.admittedWorkId == expectedAdmittedWorkId &&
                current.accountMarker == expectedAccountMarker
            ) {
                prefs[attemptKey(stageId)] = encodeStageAttempt(newRecord)
                swapped = true
            }
        }
        return swapped
    }

    override suspend fun removeAttempt(stageId: String) {
        dataStore.edit { prefs -> prefs.remove(attemptKey(stageId)) }
    }

    override suspend fun cohort(): SetupCohort {
        val prefs = dataStore.data.first()
        val cohortId = prefs[COHORT_ID_KEY] ?: return SetupCohort()
        return SetupCohort(
            cohortId = cohortId,
            selectedStageIds = prefs[COHORT_SELECTION_KEY].orEmpty(),
            accountMarker = prefs[COHORT_ACCOUNT_KEY],
        )
    }

    override suspend fun setCohort(
        cohortId: String,
        selectedStageIds: Set<String>,
        accountMarker: String?,
    ) {
        dataStore.edit { prefs ->
            prefs[COHORT_ID_KEY] = cohortId
            prefs[COHORT_SELECTION_KEY] = selectedStageIds
            if (accountMarker == null) {
                prefs.remove(COHORT_ACCOUNT_KEY)
            } else {
                prefs[COHORT_ACCOUNT_KEY] = accountMarker
            }
        }
    }

    override suspend fun clearCohort() {
        dataStore.edit { prefs ->
            prefs.remove(COHORT_ID_KEY)
            prefs.remove(COHORT_SELECTION_KEY)
            prefs.remove(COHORT_ACCOUNT_KEY)
        }
    }

    override suspend fun writeOutcome(stageId: String, outcome: SetupOutcome) {
        dataStore.edit { prefs ->
            prefs[outcomeKey(stageId)] = encodeSetupOutcome(outcome)
        }
    }

    override suspend fun writeOptIn(stageId: String, optIn: Boolean) {
        dataStore.edit { prefs -> prefs[optInKey(stageId)] = optIn }
    }

    override suspend fun markCompleted() {
        dataStore.edit { prefs -> prefs[COMPLETED_KEY] = true }
    }

    override suspend fun setFirstRunSetupActive(active: Boolean) {
        dataStore.edit { prefs ->
            if (active) prefs[FIRST_RUN_ACTIVE_KEY] = true else prefs.remove(FIRST_RUN_ACTIVE_KEY)
        }
    }

    override suspend fun readLegacyActiveMarker(): LegacyActiveMarker? {
        val prefs = dataStore.data.first()
        val stageId = prefs[ACTIVE_STAGE_KEY] ?: return null
        return LegacyActiveMarker(
            stageId = stageId,
            workId = prefs[ACTIVE_WORK_KEY],
            selectedStageIds = prefs[ACTIVE_SELECTION_KEY].orEmpty(),
        )
    }

    override suspend fun consumeLegacyActiveMarker() {
        dataStore.edit { prefs ->
            prefs.remove(ACTIVE_STAGE_KEY)
            prefs.remove(ACTIVE_SELECTION_KEY)
            prefs.remove(ACTIVE_WORK_KEY)
        }
    }

    private companion object {
        const val OUTCOME_PREFIX = "stage_outcome_"
        const val OPT_IN_PREFIX = "stage_opt_in_"
        const val ATTEMPT_PREFIX = "stage_attempt_"
        val ACTIVE_STAGE_KEY = stringPreferencesKey("active_stage_id")
        val ACTIVE_SELECTION_KEY = stringSetPreferencesKey("active_stage_selection")
        val ACTIVE_WORK_KEY = stringPreferencesKey("active_work_id")
        val COHORT_ID_KEY = stringPreferencesKey("setup_cohort_id")
        val COHORT_SELECTION_KEY = stringSetPreferencesKey("setup_cohort_selection")
        val COHORT_ACCOUNT_KEY = stringPreferencesKey("setup_cohort_account")
        val COMPLETED_KEY = booleanPreferencesKey("setup_completed")
        val FIRST_RUN_ACTIVE_KEY = booleanPreferencesKey("first_run_setup_active")

        fun outcomeKey(stageId: String): Preferences.Key<String> =
            stringPreferencesKey(OUTCOME_PREFIX + stageId)

        fun optInKey(stageId: String): Preferences.Key<Boolean> =
            booleanPreferencesKey(OPT_IN_PREFIX + stageId)

        fun attemptKey(stageId: String): Preferences.Key<String> =
            stringPreferencesKey(ATTEMPT_PREFIX + stageId)

        /** The stage id a prefixed key names, or null when the key isn't one of ours. */
        fun String.stageIdAfter(prefix: String): String? =
            if (startsWith(prefix)) removePrefix(prefix).takeIf { it.isNotEmpty() } else null
    }
}
