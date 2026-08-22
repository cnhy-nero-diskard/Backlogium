package com.example.backlogium.data.repo

import com.example.backlogium.data.diagnostics.SyncRunRecorder
import com.example.backlogium.data.hltb.HltbCandidate
import com.example.backlogium.data.hltb.HltbDataSource
import com.example.backlogium.data.local.dao.GameGenreCacheDao
import com.example.backlogium.data.local.dao.NonGameCandidateRow
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.GameGenreCache
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
import com.example.backlogium.domain.FakeHiddenGameDao
import com.example.backlogium.domain.TimeProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import retrofit2.Response
import java.time.LocalDate
import java.time.ZoneId

/** A [HiddenGamesRepository] over [FakeHiddenGameDao], for tests that only need the hidden set. */
internal fun fakeHiddenGamesRepository(
    hidden: Set<Long> = emptySet(),
    games: List<Game> = emptyList(),
    dao: FakeHiddenGameDao = FakeHiddenGameDao(hidden),
    nonGameCandidates: List<NonGameCandidateRow> = emptyList(),
): HiddenGamesRepository = HiddenGamesRepository(
    hiddenGameDao = dao,
    gameDao = FakeGameDao(games),
    storeCacheDao = FakeStoreCacheDao(nonGameCandidates),
    time = HiddenGamesTestTime,
)

/** Only the non-game candidate projection is exercised through this fake; the rest is inert. */
internal class FakeStoreCacheDao(
    private val candidates: List<NonGameCandidateRow> = emptyList(),
) : GameGenreCacheDao {
    override suspend fun upsert(cache: GameGenreCache) = Unit
    override suspend fun deleteAll() = Unit
    override fun observeAll(): Flow<List<GameGenreCache>> = flowOf(emptyList())
    override suspend fun eligibleAppIds(staleBefore: Long, limit: Int): List<Long> = emptyList()
    override suspend fun eligibleCount(staleBefore: Long): Int = 0
    override fun observeNonGameCandidates(): Flow<List<NonGameCandidateRow>> = flowOf(candidates)
}

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
