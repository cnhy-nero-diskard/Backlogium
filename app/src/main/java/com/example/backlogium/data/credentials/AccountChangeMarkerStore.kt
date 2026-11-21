package com.example.backlogium.data.credentials

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

private val Context.accountChangeDataStore by preferencesDataStore(name = "account_change")

/** Durable write-ahead marker for an account change that has not finished. */
@Singleton
class AccountChangeMarkerStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val pendingSteamIdKey = stringPreferencesKey("reset_pending_for")
    private val generationKey = longPreferencesKey("account_generation")
    private val commitMutex = Mutex()

    suspend fun generation(): Long = context.accountChangeDataStore.data.first()[generationKey] ?: 0L

    /** Excludes a new reset intent from the checked commit, without holding a lock over fetches. */
    suspend fun <T> withAccountState(block: suspend () -> T): T = commitMutex.withLock { block() }

    suspend fun pendingSteamId(): String? =
        context.accountChangeDataStore.data.first()[pendingSteamIdKey]
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    suspend fun markPending(steamId: String) = commitMutex.withLock {
        context.accountChangeDataStore.edit { prefs ->
            prefs[generationKey] = (prefs[generationKey] ?: 0L) + 1L
            prefs[pendingSteamIdKey] = steamId.trim()
        }
    }

    suspend fun clear() = commitMutex.withLock {
        context.accountChangeDataStore.edit { prefs -> prefs.remove(pendingSteamIdKey) }
    }
}
