package com.example.backlogium.data.repo

import com.example.backlogium.data.diagnostics.SyncRunRecorder
import com.example.backlogium.data.hltb.HltbCandidate
import com.example.backlogium.data.hltb.HltbDataSource
import com.example.backlogium.data.local.dao.HiddenGameDao
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.HiddenGame
import com.example.backlogium.data.remote.SteamApi
import com.example.backlogium.data.remote.SteamStoreApi
import com.example.backlogium.data.remote.dto.CurrentPlayersResponse
import com.example.backlogium.data.remote.dto.GameSchemaResponse
import com.example.backlogium.data.remote.dto.GlobalAchievementPercentagesResponse
import com.example.backlogium.data.remote.dto.OwnedGamesResponse
import com.example.backlogium.data.remote.dto.PlayerAchievementsResponse
import com.example.backlogium.data.remote.dto.PlayerSummariesResponse
import com.example.backlogium.data.remote.dto.ResolveVanityResponse
import com.example.backlogium.data.remote.dto.SteamLevelResponse
import com.example.backlogium.data.remote.dto.StoreAppDetails
import com.example.backlogium.domain.FakeGameDao
import com.example.backlogium.domain.TimeProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import retrofit2.Response
import java.time.LocalDate
import java.time.ZoneId

/**
 * In-memory stand-ins for the hidden set, shared by the tests of every repository that now
 * excludes hidden games. Observable, so a test can hide a game and assert the surfaces react
 * rather than only asserting a fixed starting state.
 */
internal class FakeHiddenGameDao(hidden: Set<Long> = emptySet()) : HiddenGameDao {
    private val rows = MutableStateFlow(
        hidden.associateWith { HiddenGame(appId = it, hiddenAt = 0L) },
    )

    override suspend fun upsertAll(hidden: List<HiddenGame>) {
        rows.value = rows.value + hidden.associateBy { it.appId }
    }

    override fun observeAll(): Flow<List<HiddenGame>> =
        rows.map { it.values.sortedByDescending(HiddenGame::hiddenAt) }

    override suspend fun getAll(): List<HiddenGame> =
        rows.value.values.sortedByDescending(HiddenGame::hiddenAt)

    override suspend fun hiddenAppIds(): List<Long> = rows.value.keys.toList()

    override suspend fun isHidden(appId: Long): Boolean = appId in rows.value

    override suspend fun delete(appIds: List<Long>) {
        rows.value = rows.value - appIds.toSet()
    }

    override suspend fun deleteAll() {
        rows.value = emptyMap()
    }
}

/** A [HiddenGamesRepository] over [FakeHiddenGameDao], for tests that only need the hidden set. */
internal fun fakeHiddenGamesRepository(
    hidden: Set<Long> = emptySet(),
    games: List<Game> = emptyList(),
    dao: FakeHiddenGameDao = FakeHiddenGameDao(hidden),
): HiddenGamesRepository = HiddenGamesRepository(
    hiddenGameDao = dao,
    gameDao = FakeGameDao(games),
    time = HiddenGamesTestTime,
)

internal object HiddenGamesTestTime : TimeProvider {
    override fun nowMillis(): Long = 1_700_000_000_000L
    override fun zone(): ZoneId = ZoneId.of("UTC")
    override fun today(): LocalDate = LocalDate.parse("2026-08-22")
}

/**
 * Network doubles for the hidden-games tests. Exclusion is a property of local data, so a request
 * from any of these is a test failure rather than a slow test.
 */
internal object OfflineHltbSource : HltbDataSource {
    override suspend fun search(name: String): List<HltbCandidate> =
        error("exclusion must not reach HowLongToBeat")
}

internal object OfflineStoreApi : SteamStoreApi {
    override suspend fun appDetails(
        appId: Long,
        language: String,
    ): Response<Map<String, StoreAppDetails>> = error("exclusion must not reach the Steam Store")
}

internal object OfflineSteamApiDouble : SteamApi {
    override suspend fun getOwnedGames(
        key: String,
        steamId: String,
        includeAppInfo: Int,
        includePlayedFreeGames: Int,
        scope: SyncRunRecorder.RunScope?,
    ): OwnedGamesResponse = error("exclusion must not reach Steam")

    override suspend fun getSteamLevel(
        key: String,
        steamId: String,
        scope: SyncRunRecorder.RunScope?,
    ): SteamLevelResponse = error("exclusion must not reach Steam")

    override suspend fun getPlayerSummaries(
        key: String,
        steamIds: String,
        scope: SyncRunRecorder.RunScope?,
    ): PlayerSummariesResponse = error("exclusion must not reach Steam")

    override suspend fun getPlayerAchievements(
        key: String,
        steamId: String,
        appId: Long,
        scope: SyncRunRecorder.RunScope?,
    ): PlayerAchievementsResponse = error("exclusion must not reach Steam")

    override suspend fun getGlobalAchievementPercentages(
        gameId: Long,
        scope: SyncRunRecorder.RunScope?,
    ): GlobalAchievementPercentagesResponse = error("exclusion must not reach Steam")

    override suspend fun getSchemaForGame(
        key: String,
        appId: Long,
        scope: SyncRunRecorder.RunScope?,
    ): GameSchemaResponse = error("exclusion must not reach Steam")

    override suspend fun resolveVanityUrl(
        key: String,
        vanityUrl: String,
    ): ResolveVanityResponse = error("exclusion must not reach Steam")

    override suspend fun getNumberOfCurrentPlayers(appId: Long): CurrentPlayersResponse =
        error("exclusion must not reach Steam")
}
