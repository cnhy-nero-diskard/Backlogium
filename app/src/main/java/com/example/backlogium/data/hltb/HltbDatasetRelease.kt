package com.example.backlogium.data.hltb

import com.example.backlogium.data.updates.GitHubReleaseDto

const val HLTB_DATASET_ASSET_NAME = "hltb-dataset.json"
const val HLTB_DATASET_CHECKSUM_ASSET_NAME = "hltb-dataset.json.sha256"

data class HltbDatasetRelease(
    val tag: String,
    val version: Long,
    val datasetUrl: String,
    val checksumUrl: String,
    val declaredSize: Long,
)

fun Iterable<GitHubReleaseDto>.newestHltbDatasetRelease(): HltbDatasetRelease? =
    asSequence()
        .mapNotNull(GitHubReleaseDto::toHltbDatasetRelease)
        .maxByOrNull(HltbDatasetRelease::version)

private fun GitHubReleaseDto.toHltbDatasetRelease(): HltbDatasetRelease? {
    if (draft || prerelease) return null
    val match = DATASET_TAG.matchEntire(tagName) ?: return null
    val version = match.groupValues[1].toLongOrNull()?.takeIf { it > 0L } ?: return null
    val dataset = assets.firstOrNull {
        it.name == HLTB_DATASET_ASSET_NAME && it.browserDownloadUrl.isNotBlank()
    } ?: return null
    val checksum = assets.firstOrNull {
        it.name == HLTB_DATASET_CHECKSUM_ASSET_NAME && it.browserDownloadUrl.isNotBlank()
    } ?: return null
    return HltbDatasetRelease(
        tag = tagName,
        version = version,
        datasetUrl = dataset.browserDownloadUrl,
        checksumUrl = checksum.browserDownloadUrl,
        declaredSize = dataset.size,
    )
}

private val DATASET_TAG = Regex("^hltb-dataset-v([1-9]\\d*)$")
