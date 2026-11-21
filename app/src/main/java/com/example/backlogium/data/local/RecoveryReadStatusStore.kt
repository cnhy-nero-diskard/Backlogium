package com.example.backlogium.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.backlogium.domain.PlaytimeRecoveryStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.recoveryReadStatusDataStore by preferencesDataStore(name = "playtime_recovery_read_status")

/**
 * Durable *disclosure* state for recovery reads. This store never authorizes minutes or changes a
 * Steam baseline: only the committer's trusted Steam DTO path can do that. A late local play cue is
 * retained until a later positive cumulative Steam delta resolves it.
 */
@Singleton
class RecoveryReadStatusStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        fun status(account: String) = stringPreferencesKey("$account.status")
        fun lastOutcomeAt(account: String) = longPreferencesKey("$account.last_outcome_at")
        fun lastSuccessAt(account: String) = longPreferencesKey("$account.last_success_at")
        fun pendingPlays(account: String) = stringSetPreferencesKey("$account.pending_plays")
        fun resolvedPlays(account: String) = stringSetPreferencesKey("$account.resolved_plays")
    }

    fun observe(account: String): Flow<PlaytimeRecoveryStatus> =
        context.recoveryReadStatusDataStore.data.map { prefs ->
            prefs[Keys.status(account)]?.let { value ->
                runCatching { PlaytimeRecoveryStatus.valueOf(value) }.getOrNull()
            } ?: PlaytimeRecoveryStatus.KNOWN_GOOD
        }

    suspend fun successfulRead(
        account: String,
        observedAt: Long,
        creditedIncreaseAppIds: Set<Long> = emptySet(),
        baselineEstablishedAppIds: Set<Long> = emptySet(),
    ) {
        if (account.isBlank() || observedAt < 0L) return
        context.recoveryReadStatusDataStore.edit { prefs ->
            if (observedAt < (prefs[Keys.lastOutcomeAt(account)] ?: Long.MIN_VALUE)) return@edit
            val pendingKey = Keys.pendingPlays(account)
            val resolvedKey = Keys.resolvedPlays(account)
            val pending = decodePlays(prefs[pendingKey]).toMutableMap()
            val resolved = decodePlays(prefs[resolvedKey]).toMutableMap()
            (baselineEstablishedAppIds + creditedIncreaseAppIds).filter { it > 0L }.forEach { appId ->
                val pendingAt = pending[appId]
                if (pendingAt != null && pendingAt <= observedAt) {
                    resolved[appId] = maxOf(resolved[appId] ?: Long.MIN_VALUE, pendingAt)
                    pending.remove(appId)
                }
            }
            val status = when {
                pending.isNotEmpty() -> PlaytimeRecoveryStatus.OBSERVED_AWAITING_STEAM
                creditedIncreaseAppIds.any { it > 0L } || baselineEstablishedAppIds.any { it > 0L } ->
                    PlaytimeRecoveryStatus.KNOWN_GOOD
                else -> PlaytimeRecoveryStatus.UNCHANGED_NO_KNOWN_PLAY
            }
                prefs[pendingKey] = encodePlays(pending)
                prefs[resolvedKey] = encodePlays(resolved)
            prefs[Keys.status(account)] = status.name
            prefs[Keys.lastOutcomeAt(account)] = observedAt
            prefs[Keys.lastSuccessAt(account)] = observedAt
        }
    }

    suspend fun unavailable(account: String, observedAt: Long) {
        if (account.isBlank() || observedAt < 0L) return
        context.recoveryReadStatusDataStore.edit { prefs ->
            if (observedAt < (prefs[Keys.lastOutcomeAt(account)] ?: Long.MIN_VALUE)) return@edit
            prefs[Keys.status(account)] = PlaytimeRecoveryStatus.REMOTE_UNAVAILABLE.name
            prefs[Keys.lastOutcomeAt(account)] = observedAt
        }
    }

    /** Record that local monitoring saw play; it never writes any Steam quantity or session. */
    suspend fun observedPlay(account: String, appId: Long, observedAt: Long, force: Boolean = false) {
        if (account.isBlank() || appId <= 0L || observedAt < 0L) return
        context.recoveryReadStatusDataStore.edit { prefs ->
            val lastSuccessAt = prefs[Keys.lastSuccessAt(account)] ?: Long.MIN_VALUE
            val pendingKey = Keys.pendingPlays(account)
            val pending = decodePlays(prefs[pendingKey]).toMutableMap()
            val resolvedAt = decodePlays(prefs[Keys.resolvedPlays(account)])[appId] ?: Long.MIN_VALUE
            if (force || observedAt > lastSuccessAt) {
                if (observedAt > maxOf(resolvedAt, pending[appId] ?: Long.MIN_VALUE)) {
                    pending[appId] = observedAt
                }
            }
            if (pending.isNotEmpty()) {
                prefs[pendingKey] = encodePlays(pending)
                prefs[Keys.status(account)] = PlaytimeRecoveryStatus.OBSERVED_AWAITING_STEAM.name
                prefs[Keys.lastOutcomeAt(account)] = maxOf(
                    observedAt,
                    prefs[Keys.lastOutcomeAt(account)] ?: Long.MIN_VALUE,
                )
            }
        }
    }

    private fun decodePlays(values: Set<String>?): Map<Long, Long> = values.orEmpty().mapNotNull { encoded ->
        val split = encoded.split(':', limit = 2)
        val appId = split.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
        val at = split.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
        appId to at
    }.toMap()

    private fun encodePlays(values: Map<Long, Long>): Set<String> =
        values.mapTo(mutableSetOf()) { (appId, at) -> "$appId:$at" }
}
