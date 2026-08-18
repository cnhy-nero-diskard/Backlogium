package com.example.backlogium.data.credentials

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.accountChangeDataStore by preferencesDataStore(name = "account_change")

/** Durable write-ahead marker for an account change that has not finished. */
@Singleton
class AccountChangeMarkerStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val pendingSteamIdKey = stringPreferencesKey("reset_pending_for")

    suspend fun pendingSteamId(): String? =
        context.accountChangeDataStore.data.first()[pendingSteamIdKey]
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    suspend fun markPending(steamId: String) {
        context.accountChangeDataStore.edit { prefs ->
            prefs[pendingSteamIdKey] = steamId.trim()
        }
    }

    suspend fun clear() {
        context.accountChangeDataStore.edit { prefs -> prefs.remove(pendingSteamIdKey) }
    }
}
