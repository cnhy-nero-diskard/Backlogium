package com.example.backlogium.data.hltb

import com.example.backlogium.data.updates.GitHubReleaseAssetDto
import com.example.backlogium.data.updates.GitHubReleaseDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HltbDatasetReleaseTest {
    @Test
    fun picksTheNewestDatasetTagAmongSeveral() {
        val newest = listOf(
            datasetRelease(version = 1),
            datasetRelease(version = 3),
            datasetRelease(version = 2),
        ).newestHltbDatasetRelease()

        assertEquals(3L, newest?.version)
        assertEquals("hltb-dataset-v3", newest?.tag)
    }

    @Test
    fun ignoresTheAppsOwnVersionSeries() {
        val releases = listOf(
            GitHubReleaseDto(tagName = "v1.2.3", assets = datasetAssets()),
            GitHubReleaseDto(tagName = "v1.0.0-rc1", assets = datasetAssets()),
        )

        assertNull(releases.newestHltbDatasetRelease())
    }

    @Test
    fun ignoresDraftsAndPrereleases() {
        val releases = listOf(
            GitHubReleaseDto(tagName = "hltb-dataset-v1", draft = true, assets = datasetAssets()),
            GitHubReleaseDto(tagName = "hltb-dataset-v2", prerelease = true, assets = datasetAssets()),
        )

        assertNull(releases.newestHltbDatasetRelease())
    }

    @Test
    fun ignoresMalformedOrNonPositiveVersions() {
        val releases = listOf(
            GitHubReleaseDto(tagName = "hltb-dataset-v0", assets = datasetAssets()),
            GitHubReleaseDto(tagName = "hltb-dataset-vabc", assets = datasetAssets()),
            GitHubReleaseDto(tagName = "hltb-dataset-v01", assets = datasetAssets()),
        )

        assertNull(releases.newestHltbDatasetRelease())
    }

    @Test
    fun requiresBothDatasetAndChecksumAssets() {
        val missingChecksum = GitHubReleaseDto(
            tagName = "hltb-dataset-v1",
            assets = listOf(
                GitHubReleaseAssetDto(HLTB_DATASET_ASSET_NAME, "https://example.test/dataset", 10L),
            ),
        )
        val missingDataset = GitHubReleaseDto(
            tagName = "hltb-dataset-v1",
            assets = listOf(
                GitHubReleaseAssetDto(HLTB_DATASET_CHECKSUM_ASSET_NAME, "https://example.test/checksum", 10L),
            ),
        )

        assertNull(listOf(missingChecksum).newestHltbDatasetRelease())
        assertNull(listOf(missingDataset).newestHltbDatasetRelease())
    }

    private fun datasetRelease(version: Long) = GitHubReleaseDto(
        tagName = "hltb-dataset-v$version",
        assets = datasetAssets(),
    )

    private fun datasetAssets() = listOf(
        GitHubReleaseAssetDto(HLTB_DATASET_ASSET_NAME, "https://example.test/dataset", 10L),
        GitHubReleaseAssetDto(HLTB_DATASET_CHECKSUM_ASSET_NAME, "https://example.test/checksum", 10L),
    )
}
