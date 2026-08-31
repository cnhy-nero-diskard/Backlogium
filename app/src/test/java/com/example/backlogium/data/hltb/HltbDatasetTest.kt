package com.example.backlogium.data.hltb

import com.example.backlogium.data.updates.GitHubReleaseAssetDto
import com.example.backlogium.data.updates.GitHubReleaseDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HltbDatasetTest {
    @Test
    fun correspondenceWithoutLengthsStillParsesAsMatchedUnknown() {
        val dataset = HltbDatasetCodec.decode(
            """
            {
              "schemaVersion": 1,
              "datasetVersion": 4,
              "gatheredAt": 1700000000000,
              "mappings": [[620,42]],
              "lengths": []
            }
            """.trimIndent(),
        )

        val entry = dataset.entryFor(620L)
        assertEquals(42L, entry?.hltbId)
        assertNull(entry?.lengths)
    }

    @Test
    fun strictDecoderRejectsUnknownFieldsInvalidTuplesAndNonCanonicalRelations() {
        val invalidPayloads = listOf(
            validPayload().replace("\n}", ",\n  \"playtime\": 99\n}"),
            validPayload().replace("[620,42]", "[620]"),
            validPayload().replace("[42,10,null,30,40]", "[42,10,null,30,600001]"),
            validPayload().replace("[620,42]", "[620,42],[620,43]"),
            validPayload().replace("[42,10,null,30,40]", "[99,10,null,30,40]"),
        )

        invalidPayloads.forEach { payload ->
            assertTrue(runCatching { HltbDatasetCodec.decode(payload) }.isFailure)
        }
    }

    @Test
    fun releaseSelectionUsesNewestValidDatasetSeriesOnly() {
        val releases = listOf(
            release("v9.9.9"),
            release("hltb-dataset-v3"),
            release("hltb-dataset-v7", prerelease = true),
            release("hltb-dataset-v6"),
            release("hltb-dataset-v8", includeChecksum = false),
        )

        val selected = releases.newestHltbDatasetRelease()

        assertEquals("hltb-dataset-v6", selected?.tag)
        assertEquals(6L, selected?.version)
        assertEquals(HLTB_DATASET_ASSET_NAME, selected?.datasetUrl?.substringAfterLast('/'))
    }

    private fun validPayload() = """
        {
          "schemaVersion": 1,
          "datasetVersion": 4,
          "gatheredAt": 1700000000000,
          "mappings": [[620,42]],
          "lengths": [[42,10,null,30,40]]
        }
    """.trimIndent()

    private fun release(
        tag: String,
        prerelease: Boolean = false,
        includeChecksum: Boolean = true,
    ) = GitHubReleaseDto(
        tagName = tag,
        prerelease = prerelease,
        assets = buildList {
            add(
                GitHubReleaseAssetDto(
                    HLTB_DATASET_ASSET_NAME,
                    "https://example.test/$HLTB_DATASET_ASSET_NAME",
                    100L,
                ),
            )
            if (includeChecksum) {
                add(
                    GitHubReleaseAssetDto(
                        HLTB_DATASET_CHECKSUM_ASSET_NAME,
                        "https://example.test/$HLTB_DATASET_CHECKSUM_ASSET_NAME",
                        64L,
                    ),
                )
            }
        },
    )
}
