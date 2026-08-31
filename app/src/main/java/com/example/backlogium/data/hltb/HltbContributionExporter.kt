package com.example.backlogium.data.hltb

import android.content.ContentResolver
import android.net.Uri
import com.example.backlogium.data.backup.DatabaseTransactionScope
import com.example.backlogium.data.local.dao.GameDao
import com.example.backlogium.data.local.dao.HltbDataDao
import com.example.backlogium.data.local.entity.HltbData
import com.example.backlogium.data.local.entity.HltbMatchStatus
import com.example.backlogium.data.repo.HltbDatasetLookup
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton

/** Result of preparing a shareable HLTB contribution before opening a SAF destination. */
sealed interface HltbContributionPreparation {
    data object NothingToContribute : HltbContributionPreparation

    /** Immutable, completely formatted payload safe to retain across the document picker. */
    class Ready internal constructor(
        val contents: String,
        val mappingCount: Int,
        val lengthCount: Int,
        val gatheredAt: Long,
    ) : HltbContributionPreparation
}

/**
 * Builds a privacy-minimal HLTB contribution and, only after preparation succeeds, writes it to
 * a caller-provided SAF destination. The writer accepts [HltbContributionPreparation.Ready]
 * rather than the broader preparation result, so an empty/no-op result cannot create a file.
 */
@Singleton
class HltbContributionExporter @Inject constructor(
    private val gameDao: GameDao,
    private val hltbDataDao: HltbDataDao,
    private val datasetLookup: HltbDatasetLookup,
    private val transaction: DatabaseTransactionScope,
) {
    suspend fun prepare(): HltbContributionPreparation = transaction.run {
        val ownedAppIds = gameDao.ownedGamesForDiffing()
            .mapTo(mutableSetOf()) { game -> game.appId }
        HltbContributionFormatter.prepare(
            cachedRows = hltbDataDao.getAll(),
            datasetRows = datasetLookup.getAll(),
            ownedAppIds = ownedAppIds,
        )
    }

    fun writeTo(
        prepared: HltbContributionPreparation.Ready,
        destination: Uri,
        contentResolver: ContentResolver,
    ) {
        // Materialize the exact bytes before asking the resolver for a destination stream. A
        // formatting/encoding failure therefore cannot leave a newly-created empty document.
        val bytes = prepared.contents.toByteArray(StandardCharsets.UTF_8)
        contentResolver.openOutputStream(destination)?.use { output ->
            output.write(bytes)
        } ?: error("Unable to open $destination for writing")
    }

    companion object {
        const val DEFAULT_FILE_NAME = "backlogium-hltb-contribution.json"
    }
}

/** Pure schema-v1 projection and canonical formatter shared by production and focused tests. */
internal object HltbContributionFormatter {
    private const val SCHEMA_VERSION = 1
    private const val CONTRIBUTION_DATASET_VERSION = 0
    private const val MAX_LENGTH_MINUTES = 600_000
    private const val MAX_JSON_SAFE_INTEGER = 9_007_199_254_740_991L

    fun prepare(
        cachedRows: Iterable<HltbData>,
        datasetRows: Iterable<HltbData>,
        ownedAppIds: Set<Long>,
    ): HltbContributionPreparation {
        requireUniqueAppIds(cachedRows, "cache")
        requireUniqueAppIds(datasetRows, "dataset")
        val overlaidRows = datasetRows.associateByTo(mutableMapOf(), HltbData::appId)
        cachedRows.forEach { row -> overlaidRows[row.appId] = row }

        val selected = overlaidRows.values
            .asSequence()
            .filter { row ->
                row.appId in ownedAppIds &&
                    row.matchStatus == HltbMatchStatus.RESOLVED &&
                    row.hltbId != null
            }
            .map(::validateAndSelect)
            .sortedBy(SelectedRow::appId)
            .toList()

        if (selected.isEmpty()) return HltbContributionPreparation.NothingToContribute

        selected.zipWithNext().forEach { (left, right) ->
            require(left.appId != right.appId) {
                "mappings contains duplicate appId ${left.appId}"
            }
        }

        val gatheredAt = selected.minOf(SelectedRow::fetchedAt)
        val lengths = selected
            .asSequence()
            .filter(SelectedRow::hasKnownLength)
            .groupBy(SelectedRow::hltbId)
            .mapValues { (_, candidates) ->
                candidates.maxWithOrNull(
                    compareBy<SelectedRow>(SelectedRow::fetchedAt)
                        .thenBy(SelectedRow::canonicalLengthTuple),
                )!!
            }
            .values
            .sortedBy(SelectedRow::hltbId)

        val contents = serialize(selected, lengths, gatheredAt)
        return HltbContributionPreparation.Ready(
            contents = contents,
            mappingCount = selected.size,
            lengthCount = lengths.size,
            gatheredAt = gatheredAt,
        )
    }

    private fun requireUniqueAppIds(rows: Iterable<HltbData>, source: String) {
        val appIds = mutableSetOf<Long>()
        rows.forEach { row ->
            require(appIds.add(row.appId)) {
                "$source contains duplicate appId ${row.appId}"
            }
        }
    }

    private fun validateAndSelect(row: HltbData): SelectedRow {
        val hltbId = requireNotNull(row.hltbId)
        requirePositiveSafeInteger(row.appId, "appId")
        requirePositiveSafeInteger(hltbId, "hltbId")
        val gatheredAt = normalizeGatheredAt(row.fetchedAt)
        validateLength(row.mainStoryMinutes, "mainStoryMinutes")
        validateLength(row.mainExtraMinutes, "mainExtraMinutes")
        validateLength(row.completionistMinutes, "completionistMinutes")
        validateLength(row.allStylesMinutes, "allStylesMinutes")
        return SelectedRow(
            appId = row.appId,
            hltbId = hltbId,
            mainStoryMinutes = row.mainStoryMinutes,
            mainExtraMinutes = row.mainExtraMinutes,
            completionistMinutes = row.completionistMinutes,
            allStylesMinutes = row.allStylesMinutes,
            fetchedAt = gatheredAt,
        )
    }

    private fun normalizeGatheredAt(value: Long): Long {
        require(value in 0..MAX_JSON_SAFE_INTEGER) {
            "fetchedAt must be a non-negative JSON safe integer; received $value"
        }
        // Old backup files had no fetchedAt field and import it as the epoch-zero sentinel.
        // Non-empty schema-v1 contributions require a positive gatheredAt, so preserve its
        // conservative meaning using the earliest representable observation time.
        return if (value == 0L) 1L else value
    }

    private fun requirePositiveSafeInteger(value: Long, label: String) {
        require(value in 1..MAX_JSON_SAFE_INTEGER) {
            "$label must be a positive JSON safe integer; received $value"
        }
    }

    private fun validateLength(value: Int?, label: String) {
        require(value == null || value in 0..MAX_LENGTH_MINUTES) {
            "$label must be null or an integer from 0 through $MAX_LENGTH_MINUTES; received $value"
        }
    }

    private fun serialize(
        mappings: List<SelectedRow>,
        lengths: List<SelectedRow>,
        gatheredAt: Long,
    ): String = buildString {
        append("{\n")
        append("  \"schemaVersion\": $SCHEMA_VERSION,\n")
        append("  \"datasetVersion\": $CONTRIBUTION_DATASET_VERSION,\n")
        append("  \"gatheredAt\": $gatheredAt,\n")
        append("  \"mappings\": [\n")
        mappings.forEachIndexed { index, row ->
            append("    [${row.appId},${row.hltbId}]")
            if (index != mappings.lastIndex) append(',')
            append('\n')
        }
        append("  ],\n")
        if (lengths.isEmpty()) {
            append("  \"lengths\": []\n")
        } else {
            append("  \"lengths\": [\n")
            lengths.forEachIndexed { index, row ->
                append("    ${row.canonicalLengthTuple()}")
                if (index != lengths.lastIndex) append(',')
                append('\n')
            }
            append("  ]\n")
        }
        append("}\n")
    }

    private data class SelectedRow(
        val appId: Long,
        val hltbId: Long,
        val mainStoryMinutes: Int?,
        val mainExtraMinutes: Int?,
        val completionistMinutes: Int?,
        val allStylesMinutes: Int?,
        val fetchedAt: Long,
    ) {
        fun hasKnownLength(): Boolean =
            mainStoryMinutes != null ||
                mainExtraMinutes != null ||
                completionistMinutes != null ||
                allStylesMinutes != null

        fun canonicalLengthTuple(): String = listOf(
            hltbId.toString(),
            mainStoryMinutes.jsonCell(),
            mainExtraMinutes.jsonCell(),
            completionistMinutes.jsonCell(),
            allStylesMinutes.jsonCell(),
        ).joinToString(separator = ",", prefix = "[", postfix = "]")
    }

    private fun Int?.jsonCell(): String = this?.toString() ?: "null"
}
