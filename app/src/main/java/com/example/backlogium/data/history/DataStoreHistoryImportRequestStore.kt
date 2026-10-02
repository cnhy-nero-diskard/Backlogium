package com.example.backlogium.data.history

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.historyImportRequestDataStore by preferencesDataStore(name = "history_import_request")

/**
 * An explicit, account-scoped history-import request as the user consented to it, persisted before
 * the import operation launches (stabilize-first-run-setup, task 5.5).
 *
 * `requestId` is an opaque durable identity for the consent (never a raw account secret);
 * [steamId] is the account the consent was given for. Merely visiting a surface never writes here —
 * only the explicit Import action does, so a kill before the operation launches can replay the
 * recorded request while an unanswered journey writes nothing at all.
 */
data class HistoryImportRequestRecord(
    /** The Steam account for which consent was given. */
    val steamId: String,
    /** Epoch millis when the explicit consent was recorded. */
    val requestedAt: Long,
    /** Opaque durable request identity for attribution/provenance. */
    val requestId: String,
)

/**
 * A durable RESET intent persisted **before** the reset's raw commit (stabilize-first-run-setup).
 *
 * It names the exact request identity the reset will void ([voidedRequestId]) for the account it
 * was recorded for, so a first-run-phase replay that re-submits the previously settled consent id
 * can never silently re-import a reset library. If the process dies between this intent and the
 * Room commit, startup recovery resumes the guarded reset from the intent; a blocked reset (cloud
 * transfer / account change) clears the intent without voiding the request.
 */
data class HistoryImportResetIntent(
    /** The account the reset was requested for. */
    val steamId: String,
    /** The last settled import request identity this reset invalidates. */
    val voidedRequestId: String,
    /** Epoch millis when the reset intent was durably recorded. */
    val requestedAt: Long,
)

/**
 * Single-slot durable store for the active account's explicit history-import request.
 *
 * One slot is correct because only one account is active at a time; a recorded request for a
 * different account is superseded at the commit boundary and cleared. The account-change reset
 * intentionally goes through [clear] (coordinator/reset path) so a discarded account's consent
 * cannot replay for the replacement account.
 */
@Singleton
class DataStoreHistoryImportRequestStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val STEAM_ID = stringPreferencesKey("import_request_steam_id")
        val REQUESTED_AT = longPreferencesKey("import_request_requested_at")
        val REQUEST_ID = stringPreferencesKey("import_request_request_id")
        // Single-slot last settled import identity: written when an import fully settles, so an
        // explicit reset can durably void the exact request id a phase replay might re-submit.
        val LAST_SETTLED_STEAM_ID = stringPreferencesKey("import_settled_steam_id")
        val LAST_SETTLED_REQUEST_ID = stringPreferencesKey("import_settled_request_id")
        // Durable reset intent, recorded before the reset's raw commit.
        val RESET_INTENT_STEAM_ID = stringPreferencesKey("reset_intent_steam_id")
        val RESET_INTENT_REQUEST_ID = stringPreferencesKey("reset_intent_request_id")
        val RESET_INTENT_AT = longPreferencesKey("reset_intent_at")
        // Single-slot last voided import identity: a phase replay of this id is refused.
        val VOIDED_STEAM_ID = stringPreferencesKey("import_voided_steam_id")
        val VOIDED_REQUEST_ID = stringPreferencesKey("import_voided_request_id")
    }

    val requestFlow: Flow<HistoryImportRequestRecord?> = context.historyImportRequestDataStore.data
        .map { prefs ->
            val steamId = prefs[Keys.STEAM_ID]?.trim()?.takeIf { it.isNotEmpty() }
            val requestedAt = prefs[Keys.REQUESTED_AT]
            val requestId = prefs[Keys.REQUEST_ID]?.trim()?.takeIf { it.isNotEmpty() }
            if (steamId != null && requestedAt != null && requestId != null) {
                HistoryImportRequestRecord(steamId, requestedAt, requestId)
            } else {
                null
            }
        }

    /** One-shot read of the stored request, or null when no explicit consent is recorded. */
    suspend fun request(): HistoryImportRequestRecord? = requestFlow.first()

    /**
     * Persist the explicit consent for [steamId] as one DataStore write. Replaces any prior
     * recorded request: the newest explicit consent is the one recovery replays.
     */
    suspend fun recordExplicitRequest(
        steamId: String,
        requestedAt: Long,
        requestId: String,
    ): HistoryImportRequestRecord {
        val normalized = steamId.trim().takeIf { it.isNotEmpty() }
            ?: error("A history-import request requires a non-blank account")
        val normalizedRequestId = requestId.trim().takeIf { it.isNotEmpty() }
            ?: error("A history-import request requires a non-blank request id")
        context.historyImportRequestDataStore.edit { prefs ->
            prefs[Keys.STEAM_ID] = normalized
            prefs[Keys.REQUESTED_AT] = requestedAt
            prefs[Keys.REQUEST_ID] = normalizedRequestId
        }
        return HistoryImportRequestRecord(normalized, requestedAt, normalizedRequestId)
    }

    /** Discard the recorded request (consumed by a completed import or a superseded account). */
    suspend fun clear() {
        context.historyImportRequestDataStore.edit { prefs ->
            prefs.remove(Keys.STEAM_ID)
            prefs.remove(Keys.REQUESTED_AT)
            prefs.remove(Keys.REQUEST_ID)
        }
    }

    /**
     * Discard the recorded request AND the settled/voided/reset bookkeeping. Account-bound: used at
     * account-reset boundaries (and test setup) where the previous account's identities must not
     * leak onto the replacement account. The normal import/reset paths use [clear] /
     * [clearResetIntent] and deliberately retain the settled/voided identities.
     */
    suspend fun clearAll() {
        context.historyImportRequestDataStore.edit { prefs ->
            prefs.remove(Keys.STEAM_ID)
            prefs.remove(Keys.REQUESTED_AT)
            prefs.remove(Keys.REQUEST_ID)
            prefs.remove(Keys.LAST_SETTLED_STEAM_ID)
            prefs.remove(Keys.LAST_SETTLED_REQUEST_ID)
            prefs.remove(Keys.RESET_INTENT_STEAM_ID)
            prefs.remove(Keys.RESET_INTENT_REQUEST_ID)
            prefs.remove(Keys.RESET_INTENT_AT)
            prefs.remove(Keys.VOIDED_STEAM_ID)
            prefs.remove(Keys.VOIDED_REQUEST_ID)
        }
    }

    /**
     * Durably record the last fully-settled import identity (single slot). Called when an import
     * settles as Imported/AlreadyImported, so an explicit reset later can void the exact request id
     * a first-run-phase replay might re-submit. Never unbounded: each settle replaces the previous.
     */
    suspend fun recordSettledImport(steamId: String, requestId: String) {
        context.historyImportRequestDataStore.edit { prefs ->
            prefs[Keys.LAST_SETTLED_STEAM_ID] = steamId.trim()
            prefs[Keys.LAST_SETTLED_REQUEST_ID] = requestId.trim()
        }
    }

    /** The last fully-settled import identity, or null when no import has ever settled. */
    suspend fun lastSettledImport(): HistoryImportRequestRecord? =
        context.historyImportRequestDataStore.data.first().let { prefs ->
            val steamId = prefs[Keys.LAST_SETTLED_STEAM_ID]?.trim()?.takeIf { it.isNotEmpty() }
            val requestId = prefs[Keys.LAST_SETTLED_REQUEST_ID]?.trim()?.takeIf { it.isNotEmpty() }
            if (steamId != null && requestId != null) {
                HistoryImportRequestRecord(steamId, requestedAt = 0L, requestId = requestId)
            } else {
                null
            }
        }

    /**
     * Record the durable reset intent BEFORE the reset's raw commit, naming the exact request id to
     * void. A kill between this write and the Room commit keeps the intent for startup recovery.
     */
    suspend fun recordResetIntent(
        steamId: String,
        voidedRequestId: String,
        requestedAt: Long,
    ): HistoryImportResetIntent {
        val normalizedSteamId = steamId.trim().takeIf { it.isNotEmpty() }
            ?: error("A reset intent requires a non-blank account")
        val normalizedVoided = voidedRequestId.trim().takeIf { it.isNotEmpty() }
            ?: error("A reset intent requires the request id it voids")
        context.historyImportRequestDataStore.edit { prefs ->
            prefs[Keys.RESET_INTENT_STEAM_ID] = normalizedSteamId
            prefs[Keys.RESET_INTENT_REQUEST_ID] = normalizedVoided
            prefs[Keys.RESET_INTENT_AT] = requestedAt
        }
        return HistoryImportResetIntent(normalizedSteamId, normalizedVoided, requestedAt)
    }

    /** The durable reset intent, or null when no reset is mid-flight. */
    suspend fun resetIntent(): HistoryImportResetIntent? =
        context.historyImportRequestDataStore.data.first().let { prefs ->
            val steamId = prefs[Keys.RESET_INTENT_STEAM_ID]?.trim()?.takeIf { it.isNotEmpty() }
            val voided = prefs[Keys.RESET_INTENT_REQUEST_ID]?.trim()?.takeIf { it.isNotEmpty() }
            val at = prefs[Keys.RESET_INTENT_AT]
            if (steamId != null && voided != null && at != null) {
                HistoryImportResetIntent(steamId, voided, at)
            } else {
                null
            }
        }

    /** Consume an abandoned or completed reset intent; never voids the request. */
    suspend fun clearResetIntent() {
        context.historyImportRequestDataStore.edit { prefs ->
            prefs.remove(Keys.RESET_INTENT_STEAM_ID)
            prefs.remove(Keys.RESET_INTENT_REQUEST_ID)
            prefs.remove(Keys.RESET_INTENT_AT)
        }
    }

    /**
     * Durably record the exact request identity an explicit reset invalidated (single slot). A
     * phase replay re-submitting this id is refused ([isRequestVoided]) and never re-imports.
     */
    suspend fun recordVoidedImport(steamId: String, requestId: String) {
        context.historyImportRequestDataStore.edit { prefs ->
            prefs[Keys.VOIDED_STEAM_ID] = steamId.trim()
            prefs[Keys.VOIDED_REQUEST_ID] = requestId.trim()
        }
    }

    /** The last voided import identity, or null when no explicit reset has invalidated one. */
    suspend fun lastVoidedImport(): HistoryImportRequestRecord? =
        context.historyImportRequestDataStore.data.first().let { prefs ->
            val steamId = prefs[Keys.VOIDED_STEAM_ID]?.trim()?.takeIf { it.isNotEmpty() }
            val requestId = prefs[Keys.VOIDED_REQUEST_ID]?.trim()?.takeIf { it.isNotEmpty() }
            if (steamId != null && requestId != null) {
                HistoryImportRequestRecord(steamId, requestedAt = 0L, requestId = requestId)
            } else {
                null
            }
        }

    /** Whether [requestId] was invalidated by an explicit reset for [accountSteamId]. */
    suspend fun isRequestVoided(accountSteamId: String, requestId: String): Boolean {
        val voided = lastVoidedImport() ?: return false
        return voided.steamId == accountSteamId.trim() && voided.requestId == requestId.trim()
    }
}