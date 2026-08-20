package com.example.backlogium.data.steamassets

import android.content.Context
import android.graphics.BitmapFactory
import com.example.backlogium.data.local.entity.SteamAssetManifest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** App-private, validated file store. Room only points at a file after its replacement succeeds. */
@Singleton
class SteamAssetStore @Inject constructor(@ApplicationContext context: Context) {
    private val directory = File(context.filesDir, DIRECTORY).also { it.mkdirs() }

    fun normalizedUrl(url: String): String = url.trim().substringBefore('#')

    fun fileFor(manifest: SteamAssetManifest): File? = manifest.relativePath?.let(::fileForPath)

    suspend fun isValid(manifest: SteamAssetManifest): Boolean = withContext(Dispatchers.IO) {
        if (manifest.state != SteamAssetManifestState.STORED.name) return@withContext false
        val file = fileFor(manifest) ?: return@withContext false
        file.isFile && file.length() == manifest.byteCount && checksum(file) == manifest.checksum
    }

    suspend fun write(url: String, contentType: String?, bytes: ByteArray): StoredFile? = withContext(Dispatchers.IO) {
        if (bytes.isEmpty() || contentType?.substringBefore(';')?.trim()?.startsWith("image/") != true) return@withContext null
        val name = sha256(normalizedUrl(url)) + ".img"
        val destination = File(directory, name)
        val temporary = File(directory, "$name.tmp")
        try {
            temporary.outputStream().use { it.write(bytes); it.fd.sync() }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(temporary.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
            try {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: Exception) {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            StoredFile(name, destination.length(), checksum(destination))
        } finally {
            temporary.delete()
        }
    }

    suspend fun deleteTemporaryFiles() = withContext(Dispatchers.IO) {
        directory.listFiles { file -> file.name.endsWith(".tmp") }?.forEach(File::delete)
    }

    suspend fun delete(relativePath: String) = withContext(Dispatchers.IO) {
        fileForPath(relativePath)
            ?.takeIf { it.extension == "img" }
            ?.delete()
    }

    suspend fun deleteOrphanFiles(referencedPaths: Set<String>) = withContext(Dispatchers.IO) {
        directory.listFiles { file ->
            file.isFile && file.extension == "img" && file.name !in referencedPaths
        }?.forEach(File::delete)
    }

    private fun fileForPath(relativePath: String): File? {
        if (relativePath.isBlank()) return null
        return runCatching {
            val root = directory.canonicalFile
            File(root, relativePath).canonicalFile.takeIf { it.parentFile == root }
        }.getOrNull()
    }

    private fun checksum(file: File): String = file.inputStream().use { stream ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = stream.read(buffer)
            if (count <= 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    data class StoredFile(val relativePath: String, val bytes: Long, val checksum: String)

    companion object { const val DIRECTORY = "steam_assets" }
}
