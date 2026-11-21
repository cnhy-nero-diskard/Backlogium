package com.example.backlogium.data.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * A portable identity for a session a recovery replacement retired: the session's natural key
 * (app id + start/end instants, ISO strings). The same key that `SessionDao.findByNaturalKey` and
 * the backup session merge use, so an older backup's full synthetic session can be recognised and
 * suppressed instead of landing again next to an upgraded placement.
 */
@Serializable
data class BackupSessionTombstone(
    val appId: Long,
    val startAt: String? = null,
    val endAt: String? = null,
)

/**
 * Stable JSON contract for portable session tombstones that travel in a replacement journal's free
 * text fields ([BackupReplacement.payloadJson] / [BackupReplacement.replacedRecipeJson]). No schema
 * change is needed for these: the journal already carries JSON blobs, and this object just defines
 * the shape both the recovery writer and the backup import read.
 */
object RecoveryBackupSessionTombstones {

    const val KEY = "tombstonedSessionKeys"

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    /** Encodes possibly-empty keys into a stable JSON object under [KEY]. */
    fun encode(keys: List<BackupSessionTombstone>): String = buildJsonObject {
        putJsonArray(KEY) {
            keys.forEach { key ->
                add(
                    buildJsonObject {
                        put("appId", key.appId)
                        key.startAt?.let { put("startAt", it) }
                        key.endAt?.let { put("endAt", it) }
                    },
                )
            }
        }
    }.toString()

    /** Extracts tombstoned session keys from either journal JSON field; absent/empty → empty. */
    fun decode(jsonText: String?): List<BackupSessionTombstone> {
        if (jsonText.isNullOrBlank()) return emptyList()
        val root = runCatching { json.parseToJsonElement(jsonText) as JsonObject }.getOrNull() ?: return emptyList()
        val array = root[KEY] ?: return emptyList()
        return runCatching {
            array.jsonArray.mapNotNull { element ->
                val obj = element.jsonObject
                val appId = obj["appId"]?.jsonPrimitive?.content?.toLongOrNull()
                    ?: return@mapNotNull null
                BackupSessionTombstone(
                    appId = appId,
                    startAt = obj["startAt"]?.jsonPrimitive?.contentOrNull(),
                    endAt = obj["endAt"]?.jsonPrimitive?.contentOrNull(),
                )
            }
        }.getOrElse { emptyList() }
    }
}

private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? =
    if (isString) content else null
