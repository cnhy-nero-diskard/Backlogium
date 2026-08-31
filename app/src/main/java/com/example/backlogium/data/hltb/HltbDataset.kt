package com.example.backlogium.data.hltb

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The four completion-length cells belonging to one HowLongToBeat entry. */
data class HltbDatasetLengths(
    val mainStoryMinutes: Int?,
    val mainExtraMinutes: Int?,
    val completionistMinutes: Int?,
    val allStylesMinutes: Int?,
) {
    val hasAnyKnownLength: Boolean
        get() = mainStoryMinutes != null || mainExtraMinutes != null ||
            completionistMinutes != null || allStylesMinutes != null
}

/** Strictly validated contents of one published completion-times dataset. */
data class HltbDataset(
    val schemaVersion: Int,
    val datasetVersion: Long,
    val gatheredAt: Long,
    val mappings: Map<Long, Long>,
    val lengths: Map<Long, HltbDatasetLengths>,
) {
    fun entryFor(appId: Long): HltbDatasetEntry? {
        val hltbId = mappings[appId] ?: return null
        return HltbDatasetEntry(
            appId = appId,
            hltbId = hltbId,
            lengths = lengths[hltbId],
            gatheredAt = gatheredAt,
        )
    }
}

data class HltbDatasetEntry(
    val appId: Long,
    val hltbId: Long,
    val lengths: HltbDatasetLengths?,
    val gatheredAt: Long,
)

/**
 * Decoder for the repository's exact schema-v1 tuple format.
 *
 * This deliberately does not use the application's lenient network [Json]: unknown keys and
 * malformed values must fail verification rather than being silently ignored or coerced.
 */
object HltbDatasetCodec {
    const val SCHEMA_VERSION = 1
    const val MAX_LENGTH_MINUTES = 600_000
    const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L

    private val strictJson = Json {
        ignoreUnknownKeys = false
        coerceInputValues = false
        isLenient = false
        explicitNulls = true
    }

    fun decode(payload: String): HltbDataset {
        val wire = strictJson.decodeFromString(HltbDatasetWire.serializer(), payload)
        require(wire.schemaVersion == SCHEMA_VERSION) {
            "unsupported schemaVersion ${wire.schemaVersion}"
        }
        require(wire.datasetVersion in 0..MAX_SAFE_INTEGER) {
            "invalid datasetVersion ${wire.datasetVersion}"
        }
        require(wire.gatheredAt in 0..MAX_SAFE_INTEGER) {
            "invalid gatheredAt ${wire.gatheredAt}"
        }
        require(wire.mappings.isEmpty() == wire.lengths.isEmpty() || wire.mappings.isNotEmpty()) {
            "lengths require at least one mapping"
        }
        if (wire.mappings.isNotEmpty()) {
            require(wire.gatheredAt > 0L) { "a non-empty dataset requires positive gatheredAt" }
        }

        val mappings = LinkedHashMap<Long, Long>(wire.mappings.size)
        var previousAppId = 0L
        wire.mappings.forEachIndexed { index, tuple ->
            require(tuple.size == MAPPING_ARITY) { "mappings[$index] must contain 2 values" }
            val appId = tuple[0]
            val hltbId = tuple[1]
            requireSafePositiveId(appId, "mappings[$index].appId")
            requireSafePositiveId(hltbId, "mappings[$index].hltbId")
            require(appId > previousAppId) { "mappings must be sorted with unique app ids" }
            mappings[appId] = hltbId
            previousAppId = appId
        }

        val referencedHltbIds = mappings.values.toSet()
        val lengths = LinkedHashMap<Long, HltbDatasetLengths>(wire.lengths.size)
        var previousHltbId = 0L
        wire.lengths.forEachIndexed { index, tuple ->
            require(tuple.size == LENGTH_ARITY) { "lengths[$index] must contain 5 values" }
            val hltbId = tuple[0] ?: error("lengths[$index].hltbId must not be null")
            requireSafePositiveId(hltbId, "lengths[$index].hltbId")
            require(hltbId > previousHltbId) { "lengths must be sorted with unique HLTB ids" }
            require(hltbId in referencedHltbIds) {
                "lengths[$index] references unmapped HLTB id $hltbId"
            }
            val cells = tuple.drop(1).mapIndexed { cellIndex, value ->
                value?.also {
                    require(it in 0..MAX_LENGTH_MINUTES.toLong()) {
                        "lengths[$index][${cellIndex + 1}] is outside 0..$MAX_LENGTH_MINUTES"
                    }
                }?.toInt()
            }
            val row = HltbDatasetLengths(
                mainStoryMinutes = cells[0],
                mainExtraMinutes = cells[1],
                completionistMinutes = cells[2],
                allStylesMinutes = cells[3],
            )
            require(row.hasAnyKnownLength) { "lengths[$index] contains no known length" }
            lengths[hltbId] = row
            previousHltbId = hltbId
        }

        return HltbDataset(
            schemaVersion = wire.schemaVersion,
            datasetVersion = wire.datasetVersion,
            gatheredAt = wire.gatheredAt,
            mappings = mappings,
            lengths = lengths,
        )
    }

    private fun requireSafePositiveId(value: Long, label: String) {
        require(value in 1..MAX_SAFE_INTEGER) { "$label must be a positive JSON safe integer" }
    }

    private const val MAPPING_ARITY = 2
    private const val LENGTH_ARITY = 5
}

@Serializable
private data class HltbDatasetWire(
    val schemaVersion: Int,
    val datasetVersion: Long,
    val gatheredAt: Long,
    val mappings: List<List<Long>>,
    val lengths: List<List<Long?>>,
)
