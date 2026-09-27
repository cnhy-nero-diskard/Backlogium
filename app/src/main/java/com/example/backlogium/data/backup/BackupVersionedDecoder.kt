package com.example.backlogium.data.backup

import android.util.JsonReader
import android.util.JsonToken
import java.io.File
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream

/** One streaming preflight and decode path for picked files and retained snapshots. */
internal object BackupVersionedDecoder {
    @OptIn(ExperimentalSerializationApi::class)
    fun decode(json: Json, source: File): BackupFile? = runCatching {
        if (source.length() > BackupRepository.MAX_IMPORT_BYTES) return null
        val version = source.inputStream().bufferedReader().use { input ->
            JsonReader(input).use(::inspect)
        }
        source.inputStream().use { json.decodeFromStream(BackupFile.serializer(), it) }
            .takeIf { it.formatVersion == version }
    }.getOrNull()

    private fun inspect(reader: JsonReader): Int {
        var version: Int? = null
        var sessionsSeen = false
        var missingContribution = false
        var hasContribution = false
        reader.beginObject()
        while (reader.hasNext()) when (reader.nextName()) {
            "formatVersion" -> {
                require(version == null && reader.peek() == JsonToken.NUMBER)
                version = reader.nextInt()
            }
            "sessions" -> {
                require(!sessionsSeen)
                sessionsSeen = true
                reader.beginArray()
                while (reader.hasNext()) {
                    var present = false
                    reader.beginObject()
                    while (reader.hasNext()) when (reader.nextName()) {
                        "cloudContribution" -> {
                            require(!present)
                            present = true
                            hasContribution = true
                            inspectContribution(reader)
                        }
                        else -> reader.skipValue()
                    }
                    reader.endObject()
                    if (!present) missingContribution = true
                }
                reader.endArray()
            }
            else -> reader.skipValue()
        }
        reader.endObject()
        require(reader.peek() == JsonToken.END_DOCUMENT)
        val resolved = version ?: 1
        require(resolved == 1 || resolved == 2)
        require(if (resolved == 1) !hasContribution else !missingContribution)
        return resolved
    }

    private fun inspectContribution(reader: JsonReader) {
        require(reader.peek() == JsonToken.BEGIN_OBJECT)
        var recovered = false
        var timed = false
        reader.beginObject()
        while (reader.hasNext()) when (reader.nextName()) {
            "recoveredSharedPlay" -> {
                require(!recovered)
                readState(reader)
                recovered = true
            }
            "timingInformedSteamPlay" -> {
                require(!timed)
                readState(reader)
                timed = true
            }
            else -> reader.skipValue()
        }
        reader.endObject()
        require(recovered && timed)
    }

    private fun readState(reader: JsonReader) {
        require(reader.peek() == JsonToken.STRING)
        val value = reader.nextString()
        require(BackupContributionState.entries.any { it.name == value })
    }
}
