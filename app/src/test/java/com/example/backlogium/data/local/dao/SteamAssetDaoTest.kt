package com.example.backlogium.data.local.dao

import androidx.room.Room
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.entity.Achievement
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.PlayerProfile
import com.example.backlogium.data.local.entity.SteamAssetDownloadState
import com.example.backlogium.data.local.entity.SteamAssetManifest
import com.example.backlogium.data.steamassets.SteamAssetManifestState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * DAO/integration coverage for the offline Steam asset index (add-offline-steam-assets,
 * task 2.7). Runs against a real in-memory Room schema under Robolectric, matching
 * [GameGenreCacheDaoTest] and [CollectionDaoTest]'s style.
 */
@RunWith(RobolectricTestRunner::class)
class SteamAssetDaoTest {

    private lateinit var db: BacklogiumDatabase
    private lateinit var dao: SteamAssetDao
    private lateinit var gameDao: GameDao
    private lateinit var playerProfileDao: PlayerProfileDao
    private lateinit var achievementDao: AchievementDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            BacklogiumDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.steamAssetDao()
        gameDao = db.gameDao()
        playerProfileDao = db.playerProfileDao()
        achievementDao = db.achievementDao()
    }

    @After
    fun tearDown() = db.close()

    private fun manifest(
        url: String = "https://example.test/a.jpg",
        kind: String = "GAME_ICON",
        relativePath: String? = "file-a.img",
        byteCount: Long = 100L,
        checksum: String? = "checksum-a",
        state: String = SteamAssetManifestState.STORED.name,
        lastSuccessAt: Long? = 1_000L,
        lastCheckedAt: Long = 1_000L,
    ) = SteamAssetManifest(
        normalizedUrl = url,
        kind = kind,
        relativePath = relativePath,
        byteCount = byteCount,
        checksum = checksum,
        state = state,
        lastSuccessAt = lastSuccessAt,
        lastCheckedAt = lastCheckedAt,
    )

    @Test
    fun upsertAndGet_roundTripsAllFields() = runBlocking {
        val row = manifest()
        dao.upsert(row)

        val stored = dao.get(row.normalizedUrl)
        assertEquals(row, stored)
    }

    @Test
    fun upsert_overwritesExistingRowForSameUrl() = runBlocking {
        dao.upsert(manifest(url = "https://example.test/a.jpg", byteCount = 100L))
        dao.upsert(manifest(url = "https://example.test/a.jpg", byteCount = 200L, checksum = "checksum-b"))

        val stored = dao.get("https://example.test/a.jpg")
        assertEquals(200L, stored?.byteCount)
        assertEquals("checksum-b", stored?.checksum)
    }

    @Test
    fun observeAll_reflectsInsertedRows() = runBlocking {
        assertEquals(emptyList<SteamAssetManifest>(), dao.observeAll().first())

        dao.upsert(manifest(url = "https://example.test/a.jpg"))
        dao.upsert(manifest(url = "https://example.test/b.jpg"))

        val rows = dao.observeAll().first()
        assertEquals(2, rows.size)
        assertEquals(setOf("https://example.test/a.jpg", "https://example.test/b.jpg"), rows.map { it.normalizedUrl }.toSet())
    }

    @Test
    fun getAll_matchesObserveAll() = runBlocking {
        dao.upsert(manifest(url = "https://example.test/a.jpg"))
        dao.upsert(manifest(url = "https://example.test/b.jpg"))

        assertEquals(dao.observeAll().first().toSet(), dao.getAll().toSet())
    }

    @Test
    fun invalidate_deletesOnlyTheTargetedRow() = runBlocking {
        dao.upsert(manifest(url = "https://example.test/a.jpg"))
        dao.upsert(manifest(url = "https://example.test/b.jpg"))

        dao.invalidate("https://example.test/a.jpg")

        assertNull(dao.get("https://example.test/a.jpg"))
        assertEquals(listOf("https://example.test/b.jpg"), dao.getAll().map { it.normalizedUrl })
        assertEquals(listOf("https://example.test/b.jpg"), dao.observeAll().first().map { it.normalizedUrl })
    }

    @Test
    fun observeStoredSummary_aggregatesOnlyStoredRows() = runBlocking {
        dao.upsert(manifest(url = "https://example.test/stored-1.jpg", byteCount = 100L, state = SteamAssetManifestState.STORED.name))
        dao.upsert(manifest(url = "https://example.test/stored-2.jpg", byteCount = 250L, state = SteamAssetManifestState.STORED.name))
        dao.upsert(
            manifest(
                url = "https://example.test/unavailable.jpg",
                byteCount = 999L,
                relativePath = null,
                checksum = null,
                state = SteamAssetManifestState.UNAVAILABLE.name,
            ),
        )

        val summary = dao.observeStoredSummary().first()
        assertEquals(2, summary.count)
        assertEquals(350L, summary.bytes)
    }

    @Test
    fun observeStoredSummary_isZeroWhenNoRowsAreStored() = runBlocking {
        val summary = dao.observeStoredSummary().first()
        assertEquals(0, summary.count)
        assertEquals(0L, summary.bytes)
    }

    @Test
    fun observeHasInventory_isFalseWhenAllSourceTablesAreEmptyOrBlank() = runBlocking {
        assertFalse(dao.observeHasInventory().first())

        playerProfileDao.insertIfMissing()
        assertFalse(dao.observeHasInventory().first())

        // Achievement rows carry a foreign key to `games` (CASCADE), so an achievements-only
        // fixture can't be seeded here without a games row — and a games row alone already
        // trips this query (see observeHasInventory_reactsToAnyGameRowRegardlessOfIconUrl).
        // The achievements branch's own filtering is covered directly by
        // observeHasInventory_achievementsBranchHonorsRetiredAndBlankIconFilters below.
    }

    @Test
    fun observeHasInventory_reactsToAnyGameRowRegardlessOfIconUrl() = runBlocking {
        assertFalse(dao.observeHasInventory().first())

        // The underlying query has no WHERE clause for `games`: any row (even a blank
        // iconUrl) is enough to flip inventory presence to true.
        gameDao.upsert(Game(appId = 1L, name = "Game", iconUrl = "", playtimeForever = 0, playtime2Weeks = 0, lastPlaytime = 0))

        assertTrue(dao.observeHasInventory().first())
    }

    @Test
    fun observeHasInventory_reactsToNonBlankProfileAvatar() = runBlocking {
        playerProfileDao.insertIfMissing()
        assertFalse(dao.observeHasInventory().first())

        playerProfileDao.upsert(PlayerProfile(avatarUrl = "https://example.test/avatar.jpg"))
        assertTrue(dao.observeHasInventory().first())
    }

    @Test
    fun observeHasInventory_achievementsBranchHonorsRetiredAndBlankIconFilters() = runBlocking {
        // Achievement rows carry a foreign key to `games`, so a game row is always present
        // once an achievement exists — meaning the parameterless `games` branch of the UNION
        // already trips observeHasInventory on its own. What's independently verifiable here
        // is that the achievements branch's own WHERE clause (retired = 0, non-blank iconUrl)
        // is applied by achievementIconUrls(), the projection observeHasInventory shares logic
        // with: retired and blank-icon rows are excluded regardless of the games-row effect.
        gameDao.upsert(Game(appId = 1L, name = "Game", iconUrl = "hash", playtimeForever = 0, playtime2Weeks = 0, lastPlaytime = 0))
        achievementDao.upsertAll(
            listOf(
                achievement(appId = 1L, apiName = "RETIRED", retired = true, iconUrl = "icon.jpg"),
                achievement(appId = 1L, apiName = "BLANK", retired = false, iconUrl = ""),
            ),
        )

        assertTrue(dao.observeHasInventory().first()) // true regardless, because of the games row
        assertEquals(emptyList<String>(), dao.achievementIconUrls())
    }

    @Test
    fun observeLastRun_reflectsSingletonRowAndOverwritesOnSubsequentSave() = runBlocking {
        assertNull(dao.observeLastRun().first())

        dao.saveLastRun(
            SteamAssetDownloadState(
                mode = "DOWNLOAD_MISSING",
                completedAt = 1_000L,
                storedCount = 3,
                alreadyPresentCount = 1,
                unavailableCount = 2,
                failedCount = 0,
            ),
        )
        var last = dao.observeLastRun().first()
        assertEquals("DOWNLOAD_MISSING", last?.mode)
        assertEquals(3, last?.storedCount)

        dao.saveLastRun(
            SteamAssetDownloadState(
                mode = "REFRESH_ALL",
                completedAt = 2_000L,
                storedCount = 0,
                alreadyPresentCount = 5,
                unavailableCount = 0,
                failedCount = 1,
            ),
        )
        last = dao.observeLastRun().first()
        assertEquals("REFRESH_ALL", last?.mode)
        assertEquals(5, last?.alreadyPresentCount)
        assertEquals(1, last?.failedCount)
    }

    @Test
    fun gameImageSources_excludesBlankAndWhitespaceOnlyIconUrls() = runBlocking {
        gameDao.upsertAll(
            listOf(
                Game(appId = 1L, name = "Empty", iconUrl = "", playtimeForever = 0, playtime2Weeks = 0, lastPlaytime = 0),
                Game(appId = 2L, name = "Whitespace", iconUrl = "   ", playtimeForever = 0, playtime2Weeks = 0, lastPlaytime = 0),
                Game(appId = 3L, name = "Valid", iconUrl = "hash-3", playtimeForever = 0, playtime2Weeks = 0, lastPlaytime = 0),
            ),
        )

        val sources = dao.gameImageSources()
        assertEquals(listOf(3L to "hash-3"), sources.map { it.appId to it.iconUrl })
    }

    @Test
    fun profileAvatarUrl_isNullForMissingBlankOrWhitespaceAvatar() = runBlocking {
        assertNull(dao.profileAvatarUrl())

        playerProfileDao.upsert(PlayerProfile(avatarUrl = null))
        assertNull(dao.profileAvatarUrl())

        playerProfileDao.upsert(PlayerProfile(avatarUrl = ""))
        assertNull(dao.profileAvatarUrl())

        playerProfileDao.upsert(PlayerProfile(avatarUrl = "   "))
        assertNull(dao.profileAvatarUrl())

        playerProfileDao.upsert(PlayerProfile(avatarUrl = "https://example.test/avatar.jpg"))
        assertEquals("https://example.test/avatar.jpg", dao.profileAvatarUrl())
    }

    @Test
    fun achievementIconUrls_excludesRetiredAndBlankIcons() = runBlocking {
        gameDao.upsert(Game(appId = 1L, name = "Game", iconUrl = "hash", playtimeForever = 0, playtime2Weeks = 0, lastPlaytime = 0))
        achievementDao.upsertAll(
            listOf(
                achievement(appId = 1L, apiName = "BLANK", retired = false, iconUrl = ""),
                achievement(appId = 1L, apiName = "WHITESPACE", retired = false, iconUrl = "   "),
                achievement(appId = 1L, apiName = "RETIRED", retired = true, iconUrl = "icon-retired.jpg"),
                achievement(appId = 1L, apiName = "NULL_ICON", retired = false, iconUrl = null),
                achievement(appId = 1L, apiName = "VALID", retired = false, iconUrl = "icon-valid.jpg"),
            ),
        )

        assertEquals(listOf("icon-valid.jpg"), dao.achievementIconUrls())
    }

    private fun achievement(
        appId: Long = 1L,
        apiName: String = "ACH_TEST",
        retired: Boolean = false,
        iconUrl: String?,
    ) = Achievement(
        appId = appId,
        apiName = apiName,
        iconUrl = iconUrl,
        retired = retired,
        fetchedAt = 1_000L,
    )
}
