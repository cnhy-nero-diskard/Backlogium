package com.example.backlogium.data.hltb

import com.example.backlogium.data.local.entity.HltbData
import com.example.backlogium.data.local.entity.HltbDataOrigin
import com.example.backlogium.data.local.entity.HltbMatchStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HltbContributionExporterTest {
    @Test
    fun noResolvedCorrespondenceProducesNoPayload() {
        val result = prepare(
            listOf(
                row(appId = 1, hltbId = 101, status = HltbMatchStatus.NEEDS_REVIEW),
                row(appId = 2, hltbId = null, status = HltbMatchStatus.UNMATCHED),
                row(appId = 3, hltbId = null, status = HltbMatchStatus.RESOLVED),
            ),
        )

        assertSame(HltbContributionPreparation.NothingToContribute, result)
    }

    @Test
    fun resolvedRowsAcrossEveryOriginMatchTheCanonicalGolden() {
        val result = prepare(goldenRows())

        assertTrue(result is HltbContributionPreparation.Ready)
        val ready = result as HltbContributionPreparation.Ready
        assertEquals(goldenText(), ready.contents)
        assertEquals(3, ready.mappingCount)
        assertEquals(1, ready.lengthCount)
        assertEquals(1_756_684_800_000L, ready.gatheredAt)
        listOf(
            "origin",
            "playtime",
            "session",
            "achievement",
            "streak",
            "account",
        ).forEach { personalField ->
            assertFalse(ready.contents.contains(personalField, ignoreCase = true))
        }
        assertFalse(ready.contents.contains('\r'))
        assertTrue(ready.contents.endsWith("\n"))
        assertFalse(ready.contents.endsWith("\n\n"))
    }

    @Test
    fun duplicateLengthSelectionIsNewestAndIndependentOfInputOrder() {
        val first = row(
            appId = 11,
            hltbId = 500,
            mainStoryMinutes = 100,
            fetchedAt = 2_000,
        )
        val newer = row(
            appId = 10,
            hltbId = 500,
            mainStoryMinutes = 200,
            fetchedAt = 3_000,
        )

        val forward = prepare(listOf(first, newer)).ready()
        val reverse = prepare(listOf(newer, first)).ready()

        assertEquals(forward.contents, reverse.contents)
        assertTrue(forward.contents.indexOf("[10,500]") < forward.contents.indexOf("[11,500]"))
        assertTrue(forward.contents.contains("[500,200,null,null,null]"))
        // The oldest selected mapping timestamp is deliberately used for the whole file.
        assertEquals(2_000L, forward.gatheredAt)
    }

    @Test
    fun allNullLengthsKeepTheirMappingButDoNotCreateALengthTuple() {
        val ready = prepare(
            listOf(row(appId = 10, hltbId = 500, fetchedAt = 1_000)),
        ).ready()

        assertTrue(ready.contents.contains("[10,500]"))
        assertTrue(ready.contents.contains("\"lengths\": []"))
        assertEquals(0, ready.lengthCount)
    }

    @Test
    fun legacyEpochZeroFetchedAtUsesEarliestPositiveGatheredAt() {
        val ready = prepare(
            listOf(row(appId = 10, hltbId = 500, fetchedAt = 0)),
        ).ready()

        assertEquals(1L, ready.gatheredAt)
        assertTrue(ready.contents.contains("gatheredAt"))
        assertTrue(ready.contents.contains(": 1,"))
    }

    @Test
    fun cacheOverlaysDatasetBeforeStrictOwnedGameScoping() {
        val ready = HltbContributionFormatter.prepare(
            cachedRows = listOf(
                row(
                    appId = 10,
                    hltbId = 101,
                    fetchedAt = 3_000,
                    origin = HltbDataOrigin.MANUAL,
                ),
                row(
                    appId = 40,
                    hltbId = null,
                    fetchedAt = 4_000,
                    status = HltbMatchStatus.UNMATCHED,
                ),
            ),
            datasetRows = listOf(
                row(appId = 10, hltbId = 100, fetchedAt = 1_000, origin = HltbDataOrigin.DATASET),
                row(appId = 20, hltbId = 200, fetchedAt = 2_000, origin = HltbDataOrigin.DATASET),
                row(appId = 30, hltbId = 300, fetchedAt = 2_500, origin = HltbDataOrigin.DATASET),
                row(appId = 40, hltbId = 400, fetchedAt = 2_750, origin = HltbDataOrigin.DATASET),
            ),
            ownedAppIds = setOf(10, 30, 40),
        ).ready()

        assertTrue(ready.contents.contains("[10,101]"))
        assertTrue(ready.contents.contains("[30,300]"))
        assertFalse(ready.contents.contains("[10,100]"))
        assertFalse(ready.contents.contains("[20,200]"))
        assertFalse(ready.contents.contains("[40,400]"))
        assertEquals(2, ready.mappingCount)
    }

    @Test
    fun includedValuesAreStrictlyValidatedBeforeFormatting() {
        val invalidRows = listOf(
            row(appId = 0, hltbId = 100),
            row(appId = 1, hltbId = 0),
            row(appId = 1, hltbId = 100, fetchedAt = -1),
            row(appId = 1, hltbId = 100, fetchedAt = Long.MAX_VALUE),
            row(appId = 1, hltbId = 100, mainStoryMinutes = -1),
            row(appId = 1, hltbId = 100, mainStoryMinutes = 600_001),
            row(appId = Long.MAX_VALUE, hltbId = 100),
        )

        invalidRows.forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                prepare(listOf(invalid))
            }
        }
    }

    @Test
    fun duplicateAppIdsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            prepare(
                listOf(
                    row(appId = 10, hltbId = 100),
                    row(appId = 10, hltbId = 200),
                ),
            )
        }
    }

    private fun goldenRows(): List<HltbData> = listOf(
        row(
            appId = 30,
            hltbId = 300,
            fetchedAt = 1_756_684_800_000L,
            origin = HltbDataOrigin.MANUAL,
        ),
        row(
            appId = 10,
            hltbId = 100,
            mainStoryMinutes = 120,
            mainExtraMinutes = 180,
            completionistMinutes = 300,
            allStylesMinutes = 210,
            fetchedAt = 1_756_684_900_000L,
            origin = HltbDataOrigin.DATASET,
        ),
        row(
            appId = 20,
            hltbId = 100,
            mainStoryMinutes = 125,
            completionistMinutes = 305,
            allStylesMinutes = 215,
            fetchedAt = 1_756_685_000_000L,
            origin = HltbDataOrigin.AUTOMATIC,
        ),
        row(
            appId = 1,
            hltbId = 999,
            mainStoryMinutes = 999,
            fetchedAt = 1_756_685_100_000L,
            status = HltbMatchStatus.NEEDS_REVIEW,
        ),
        row(
            appId = 2,
            hltbId = null,
            fetchedAt = 1_756_685_200_000L,
            status = HltbMatchStatus.UNMATCHED,
        ),
    )

    private fun goldenText(): String = requireNotNull(
        javaClass.getResourceAsStream(
            "/com/example/backlogium/data/hltb/backlogium-hltb-contribution.json",
        ),
    ).bufferedReader(Charsets.UTF_8).use { it.readText().replace("\r\n", "\n") }

    private fun HltbContributionPreparation.ready(): HltbContributionPreparation.Ready {
        assertTrue(this is HltbContributionPreparation.Ready)
        return this as HltbContributionPreparation.Ready
    }

    private fun prepare(rows: List<HltbData>): HltbContributionPreparation =
        HltbContributionFormatter.prepare(
            cachedRows = rows,
            datasetRows = emptyList(),
            ownedAppIds = rows.mapTo(mutableSetOf(), HltbData::appId),
        )

    private fun row(
        appId: Long,
        hltbId: Long?,
        mainStoryMinutes: Int? = null,
        mainExtraMinutes: Int? = null,
        completionistMinutes: Int? = null,
        allStylesMinutes: Int? = null,
        fetchedAt: Long = 1_000,
        status: HltbMatchStatus = HltbMatchStatus.RESOLVED,
        origin: HltbDataOrigin = HltbDataOrigin.AUTOMATIC,
    ) = HltbData(
        appId = appId,
        hltbId = hltbId,
        mainStoryMinutes = mainStoryMinutes,
        mainExtraMinutes = mainExtraMinutes,
        completionistMinutes = completionistMinutes,
        allStylesMinutes = allStylesMinutes,
        fetchedAt = fetchedAt,
        matchStatus = status,
        origin = origin,
    )
}
