package com.example.backlogium.data.updates

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

interface UpdateDownloader {
    suspend fun download(
        url: String,
        destination: File,
        onProgress: suspend (bytesRead: Long, totalBytes: Long?) -> Unit,
    )

    suspend fun fetchText(url: String): String
}

@Singleton
class OkHttpUpdateDownloader @Inject constructor(
    private val client: OkHttpClient,
) : UpdateDownloader {
    override suspend fun download(
        url: String,
        destination: File,
        onProgress: suspend (bytesRead: Long, totalBytes: Long?) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val partial = File(destination.absolutePath + UPDATE_ARTIFACT_PARTIAL_SUFFIX)
        destination.parentFile?.mkdirs()
        partial.delete()
        var moved = false
        try {
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Download failed with HTTP ${response.code}")
                }
                val body = response.body ?: throw IOException("Download had no response body")
                val total = body.contentLength().takeIf { it >= 0L }
                body.byteStream().use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var bytesRead = 0L
                        while (true) {
                            ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            bytesRead += count
                            onProgress(bytesRead, total)
                        }
                        output.flush()
                    }
                }
            }
            if (!partial.renameTo(destination)) {
                throw IOException("Could not finalize downloaded update")
            }
            moved = true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } finally {
            if (!moved) destination.delete()
            partial.delete()
        }
    }

    override suspend fun fetchText(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Checksum download failed with HTTP ${response.code}")
            }
            response.body?.string() ?: throw IOException("Checksum download had no response body")
        }
    }

    private companion object {
        const val BUFFER_SIZE = 16 * 1024
    }
}
