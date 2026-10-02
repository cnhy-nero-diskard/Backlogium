package com.example.backlogium.data.repo

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.backlogium.data.setup.SetupStateStore
import com.example.backlogium.domain.FirstRunJourney
import com.example.backlogium.domain.FirstRunPhase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.firstRunJourneyDataStore by preferencesDataStore(name = "first_run_journey")

/** Account-owned decisions only. A worker completing can never dismiss an unanswered choice. */
@Singleton
class FirstRunJourneyRepository internal constructor(
    private val store: DataStore<Preferences>,
) {
    @Inject constructor(@ApplicationContext context: Context) : this(context.firstRunJourneyDataStore)

    val journey: Flow<FirstRunJourney?> = store.data.map(::decode)

    suspend fun current(): FirstRunJourney? = decode(store.data.first())

    /** One-way upgrade: an absent claim never creates an owed journey on a configured install. */
    suspend fun migrateLegacy(accountSteamId: String?, oldClaim: Boolean) {
        store.edit { prefs ->
            if (prefs[MIGRATED] == true) return@edit
            if (oldClaim && !accountSteamId.isNullOrBlank() && decode(prefs) == null) {
                write(prefs, FirstRunJourney(accountSteamId, FirstRunPhase.SETUP))
            }
            prefs[MIGRATED] = true
        }
    }

    suspend fun restoreLegacy(credentials: CredentialsProvider, setup: SetupStateStore) =
        migrateLegacy(credentials.currentCredentials()?.steamId, setup.firstRunSetupActiveFlow.first())

    /** Called only after verified first-time credential persistence, before showing setup. */
    suspend fun claim(accountSteamId: String) {
        require(accountSteamId.isNotBlank())
        store.edit { prefs ->
            prefs[MIGRATED] = true
            write(prefs, FirstRunJourney(accountSteamId, FirstRunPhase.SETUP))
        }
    }

    /** Compare ownership and phase atomically; late callbacks cannot advance a replacement account. */
    suspend fun transition(
        accountSteamId: String,
        expected: Set<FirstRunPhase>,
        next: FirstRunPhase,
        requestId: String? = null,
    ): Boolean {
        var changed = false
        store.edit { prefs ->
            val current = decode(prefs) ?: return@edit
            if (current.accountSteamId != accountSteamId || current.phase !in expected) return@edit
            write(prefs, current.copy(phase = next, importRequestId = requestId ?: current.importRequestId))
            changed = true
        }
        return changed
    }

    /** Successful recovery may complete only the exact unanswered request, never an explicit exit. */
    suspend fun completeImport(accountSteamId: String, requestId: String): Boolean {
        var changed = false
        store.edit { prefs ->
            val current = decode(prefs) ?: return@edit
            if (current.accountSteamId != accountSteamId || current.phase != FirstRunPhase.IMPORT_REQUESTED ||
                current.importRequestId != requestId) return@edit
            write(prefs, current.copy(phase = FirstRunPhase.COMPLETE))
            changed = true
        }
        return changed
    }

    /**
     * Atomically clear an explicitly-requested import's retained request pointer on an exact
     * DEFERRED phase whose admitted import settled fully successfully (Imported/AlreadyImported).
     * The phase stays DEFERRED — never reopened, never owed; only the pointer is dropped so a
     * later Settings reset can never be silently re-imported from a stale pointer at startup. Any
     * other account, phase, or request id is untouched.
     */
    suspend fun settleDeferredRequest(accountSteamId: String, requestId: String): Boolean {
        var changed = false
        store.edit { prefs ->
            val current = decode(prefs) ?: return@edit
            if (current.accountSteamId != accountSteamId ||
                current.phase != FirstRunPhase.DEFERRED ||
                current.importRequestId != requestId
            ) return@edit
            write(prefs, current.copy(importRequestId = null))
            changed = true
        }
        return changed
    }

    /**
     * Atomically drop a retained import request pointer for [requestId] when the current phase is
     * one of [phases] — used to un-stick a phase whose request a durable reset invalidated, so a
     * stale consent can never be replayed. Account-guarded; other phases and requests are
     * untouched. The phase is never moved by this method.
     */
    suspend fun clearImportRequest(
        accountSteamId: String,
        requestId: String,
        phases: Set<FirstRunPhase>,
    ): Boolean {
        var changed = false
        store.edit { prefs ->
            val current = decode(prefs) ?: return@edit
            if (current.accountSteamId != accountSteamId || current.phase !in phases ||
                current.importRequestId != requestId
            ) return@edit
            write(prefs, current.copy(importRequestId = null))
            changed = true
        }
        return changed
    }

    private fun decode(prefs: Preferences): FirstRunJourney? {
        val account = prefs[ACCOUNT]?.takeIf { it.isNotBlank() } ?: return null
        val phase = prefs[PHASE]?.let { runCatching { FirstRunPhase.valueOf(it) }.getOrNull() } ?: return null
        return FirstRunJourney(account, phase, prefs[REQUEST])
    }

    private fun write(prefs: androidx.datastore.preferences.core.MutablePreferences, value: FirstRunJourney) {
        prefs[ACCOUNT] = value.accountSteamId
        prefs[PHASE] = value.phase.name
        if (value.importRequestId == null) prefs.remove(REQUEST) else prefs[REQUEST] = value.importRequestId
    }

    private companion object {
        val MIGRATED = booleanPreferencesKey("legacy_claim_migrated_v1")
        val ACCOUNT = stringPreferencesKey("account")
        val PHASE = stringPreferencesKey("phase")
        val REQUEST = stringPreferencesKey("import_request_id")
    }
}
