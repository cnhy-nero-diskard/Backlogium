package com.example.backlogium.data.credentials

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

private val Context.credentialsDataStore by preferencesDataStore(name = "credentials")

/**
 * At-rest store for the Steam credentials (API key + SteamID64). Each value is encrypted with an
 * `AES/GCM/NoPadding` key held in the [AndroidKeyStore][ANDROID_KEYSTORE] and persisted to a
 * Preferences DataStore as a base64 blob of `[12-byte IV || ciphertext+GCM tag]`. The Keystore
 * key never leaves secure hardware where present ([minSdk 33][KeyGenParameterSpec]).
 *
 * Decrypt failure (e.g. the Keystore key was invalidated by a lock-screen/backup change) is
 * treated as "value absent": [read] returns null rather than throwing, so callers fall back to
 * the unconfigured state and re-onboarding instead of crashing.
 */
@Singleton
class EncryptedCredentialStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val API_KEY = stringPreferencesKey("api_key")
        val STEAM_ID = stringPreferencesKey("steam_id")
    }

    suspend fun readApiKey(): String? = read(Keys.API_KEY)

    suspend fun readSteamId(): String? = read(Keys.STEAM_ID)

    /** True when both credential values decrypt to non-blank strings. */
    suspend fun hasCredentials(): Boolean =
        !readApiKey().isNullOrBlank() && !readSteamId().isNullOrBlank()

    suspend fun write(apiKey: String, steamId: String) {
        val encApiKey = encrypt(apiKey)
        val encSteamId = encrypt(steamId)
        context.credentialsDataStore.edit { prefs ->
            prefs[Keys.API_KEY] = encApiKey
            prefs[Keys.STEAM_ID] = encSteamId
        }
    }

    private suspend fun read(key: androidx.datastore.preferences.core.Preferences.Key<String>): String? {
        val blob = context.credentialsDataStore.data.first()[key] ?: return null
        // A key-invalidation or corrupt blob yields null, not a crash: credentials are "lost".
        return runCatching { decrypt(blob) }.getOrNull()
    }

    private fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decrypt(blob: String): String {
        val combined = Base64.decode(blob, Base64.NO_WRAP)
        val iv = combined.copyOfRange(0, IV_LENGTH)
        val ciphertext = combined.copyOfRange(IV_LENGTH, combined.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    /** The AES-GCM key from the Keystore, generated (StrongBox best-effort) on first use. */
    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return generateKey()
    }

    private fun generateKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "backlogium_credentials"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val GCM_TAG_BITS = 128
    }
}
