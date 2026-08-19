package com.example.backlogium.data.updates

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.updateDataStore by preferencesDataStore(name = "app_updates")

interface UpdateStateStore {
    val state: Flow<AppUpdateState>

    suspend fun recordAttempt(atMillis: Long)

    suspend fun recordCheck(atMillis: Long, seenTag: String?, available: AvailableUpdate?)

    suspend fun setDeclinedTag(tag: String)

    suspend fun clearAvailable()
}

/** Small, absence-tolerant persistence for the update surface. */
@Singleton
class UpdateDataStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : UpdateStateStore {
    private object Keys {
        val LAST_CHECK_AT = longPreferencesKey("last_check_at")
        val LAST_SEEN_TAG = stringPreferencesKey("last_seen_tag")
        val DECLINED_TAG = stringPreferencesKey("declined_tag")
        val AVAILABLE_TAG = stringPreferencesKey("available_tag")
        val AVAILABLE_VERSION_NAME = stringPreferencesKey("available_version_name")
        val AVAILABLE_VERSION_CODE = longPreferencesKey("available_version_code")
        val AVAILABLE_RELEASE_NAME = stringPreferencesKey("available_release_name")
        val AVAILABLE_RELEASE_NOTES = stringPreferencesKey("available_release_notes")
        val AVAILABLE_APK_NAME = stringPreferencesKey("available_apk_name")
        val AVAILABLE_APK_URL = stringPreferencesKey("available_apk_url")
        val AVAILABLE_CHECKSUM_URL = stringPreferencesKey("available_checksum_url")
    }

    override val state: Flow<AppUpdateState> = context.updateDataStore.data.map(::decode)

    override suspend fun recordAttempt(atMillis: Long) {
        context.updateDataStore.edit { it[Keys.LAST_CHECK_AT] = atMillis }
    }

    override suspend fun recordCheck(
        atMillis: Long,
        seenTag: String?,
        available: AvailableUpdate?,
    ) {
        context.updateDataStore.edit { prefs ->
            prefs[Keys.LAST_CHECK_AT] = atMillis
            writeNullable(prefs, Keys.LAST_SEEN_TAG, seenTag)
            if (available == null) {
                clearAvailable(prefs)
            } else {
                prefs[Keys.AVAILABLE_TAG] = available.tag
                prefs[Keys.AVAILABLE_VERSION_NAME] = available.versionName
                prefs[Keys.AVAILABLE_VERSION_CODE] = available.versionCode
                prefs[Keys.AVAILABLE_RELEASE_NAME] = available.releaseName
                prefs[Keys.AVAILABLE_RELEASE_NOTES] = available.releaseNotes
                prefs[Keys.AVAILABLE_APK_NAME] = available.apkName
                prefs[Keys.AVAILABLE_APK_URL] = available.apkUrl
                prefs[Keys.AVAILABLE_CHECKSUM_URL] = available.checksumUrl
            }
        }
    }

    override suspend fun setDeclinedTag(tag: String) {
        context.updateDataStore.edit { it[Keys.DECLINED_TAG] = tag }
    }

    override suspend fun clearAvailable() {
        context.updateDataStore.edit(::clearAvailable)
    }

    private fun decode(prefs: Preferences): AppUpdateState {
        val tag = prefs[Keys.AVAILABLE_TAG]
        val available = if (tag == null) {
            null
        } else {
            val versionName = prefs[Keys.AVAILABLE_VERSION_NAME]
            val versionCode = prefs[Keys.AVAILABLE_VERSION_CODE]
            val releaseName = prefs[Keys.AVAILABLE_RELEASE_NAME]
            val releaseNotes = prefs[Keys.AVAILABLE_RELEASE_NOTES]
            val apkName = prefs[Keys.AVAILABLE_APK_NAME]
            val apkUrl = prefs[Keys.AVAILABLE_APK_URL]
            val checksumUrl = prefs[Keys.AVAILABLE_CHECKSUM_URL]
            if (versionName == null || versionCode == null || releaseName == null ||
                releaseNotes == null || apkName == null || apkUrl == null || checksumUrl == null
            ) {
                null
            } else {
                AvailableUpdate(
                    tag = tag,
                    versionName = versionName,
                    versionCode = versionCode,
                    releaseName = releaseName,
                    releaseNotes = releaseNotes,
                    apkName = apkName,
                    apkUrl = apkUrl,
                    checksumUrl = checksumUrl,
                )
            }
        }
        return AppUpdateState(
            available = available,
            lastCheckAtMillis = prefs[Keys.LAST_CHECK_AT],
            lastSeenTag = prefs[Keys.LAST_SEEN_TAG],
            declinedTag = prefs[Keys.DECLINED_TAG],
        )
    }

    private fun clearAvailable(prefs: MutablePreferences) {
        prefs.remove(Keys.AVAILABLE_TAG)
        prefs.remove(Keys.AVAILABLE_VERSION_NAME)
        prefs.remove(Keys.AVAILABLE_VERSION_CODE)
        prefs.remove(Keys.AVAILABLE_RELEASE_NAME)
        prefs.remove(Keys.AVAILABLE_RELEASE_NOTES)
        prefs.remove(Keys.AVAILABLE_APK_NAME)
        prefs.remove(Keys.AVAILABLE_APK_URL)
        prefs.remove(Keys.AVAILABLE_CHECKSUM_URL)
    }

    private fun <T> writeNullable(
        prefs: MutablePreferences,
        key: Preferences.Key<T>,
        value: T?,
    ) {
        if (value == null) prefs.remove(key) else prefs[key] = value
    }
}
