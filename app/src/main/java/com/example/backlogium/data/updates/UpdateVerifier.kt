package com.example.backlogium.data.updates

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UpdateVerifier @Inject constructor(
    private val packageInfo: InstalledPackageInfoProvider,
) {
    /**
     * The digest proves transfer integrity only; authenticity still rests on the signing key that
     * Android enforces when the package is installed.
     */
    suspend fun hasMatchingDigest(apk: File, checksumAsset: String): Boolean =
        withContext(Dispatchers.IO) {
            val expected = checksumAsset
                .lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() }
                ?.split(Regex("\\s+"), limit = 2)
                ?.firstOrNull()
                ?.lowercase()
                ?.takeIf { it.matches(HEX_DIGEST) }
                ?: return@withContext false

            val digest = MessageDigest.getInstance("SHA-256")
            apk.inputStream().use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) } == expected
        }

    fun hasMatchingSigner(apk: File): Boolean {
        val installed = packageInfo.installed().signerDigests
        val downloaded = packageInfo.archiveSignerDigests(apk) ?: return false
        return installed == downloaded
    }

    private companion object {
        val HEX_DIGEST = Regex("[0-9a-f]{64}")
        const val BUFFER_SIZE = 16 * 1024
    }
}
