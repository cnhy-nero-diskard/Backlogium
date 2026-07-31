package com.example.backlogium.data.repo

import com.example.backlogium.data.local.dao.GameDao
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.HltbData
import com.example.backlogium.data.local.entity.HltbMatchStatus
import com.example.backlogium.data.remote.SteamApi
import com.example.backlogium.data.remote.SteamIconMapper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

/** Steam's success code for `GetNumberOfCurrentPlayers`; any other value means no count. */
private const val CURRENT_PLAYERS_SUCCESS = 1

/**
 * Outcome of matching a game to HowLongToBeat, as consumers above `data/` see it. Mirrors the
 * stored [HltbMatchStatus] so no consumer depends on the storage enum.
 */
enum class HltbMatchState {
    /** A single confident match was resolved automatically (or confirmed via review). */
    RESOLVED,

    /** Multiple/low-confidence candidates; awaits user selection in the review surface. */
    NEEDS_REVIEW,

    /** The search returned no entries; the game carries no completion lengths. */
    UNMATCHED,
}

/**
 * A library game as consumers see it: the Steam facts they render plus the resolved
 * HowLongToBeat state, joined here so no consumer has to read the HLTB cache itself.
 *
 * The four HLTB lengths are null until a match resolves; [hltbMatchState] is null when no
 * lookup has been stored for this game yet. All four are carried (not just the two the Library
 * itself renders) so the game detail screen can present the full set from this one join rather
 * than opening a second read path into the HLTB cache — see enhance-game-detail.
 *
 * [playtime2Weeks] and [backfillMinutes] exist on the `Game` entity and were previously dropped
 * here; they are carried through because the Library sorts by recent activity and derives each
 * game's XP contribution from the engine's own inputs (backfill + tracked minutes), neither of
 * which `playtimeForever` can stand in for.
 */
data class LibraryGame(
    val appId: Long,
    val name: String,
    val iconUrl: String,
    /** Store header art, derived from the appId — the Library's faint card backdrop. */
    val headerUrl: String = "",
    val playtimeForever: Int,
    /** Steam's rolling two-week playtime — the "recently played" ordering. */
    val playtime2Weeks: Int = 0,
    /** Frozen historical playtime from the opt-in Steam-history import; 0 when not imported. */
    val backfillMinutes: Int = 0,
    val completionistMinutes: Int? = null,
    val mainStoryMinutes: Int? = null,
    val mainExtraMinutes: Int? = null,
    val allStylesMinutes: Int? = null,
    val hltbMatchState: HltbMatchState? = null,
    /** Tagged as a "Focus" game — drives History's per-day Focus-minutes breakdown. */
    val isGoal: Boolean = false,
)

/** Read/write access to the game library, exposing domain models as observable [Flow]s. */
@Singleton
class GameRepository @Inject constructor(
    private val gameDao: GameDao,
    private val hltbRepository: HltbRepository,
    private val steamApi: SteamApi,
) {
    val library: Flow<List<LibraryGame>> = gameDao.observeLibrary().withHltb()
    val goalGames: Flow<List<LibraryGame>> = gameDao.observeGoalGames().withHltb()
    val backlog: Flow<List<LibraryGame>> = gameDao.observeBacklog().withHltb()

    /**
     * The game's current Steam concurrent-player count, or `null` on any failure — network error,
     * a non-success `result` (an invalid or delisted app id), or a missing count in an otherwise
     * successful response. Never persisted: this is a live fact, fetched fresh by the caller each
     * time it's needed, the same posture [LiveStatusRepository] takes with the player's own
     * presence.
     */
    suspend fun currentPlayerCount(appId: Long): Int? = runCatching {
        steamApi.getNumberOfCurrentPlayers(appId).response
    }.getOrNull()
        ?.takeIf { it.result == CURRENT_PLAYERS_SUCCESS }
        ?.playerCount

    /**
     * Mark a game as a goal. No user-entered target is required (restyle-fixes): the manual
     * minutes target is retired, so the dormant [Game.targetMinutes] column is left untouched.
     *
     * Tagging also triggers a cache-first HowLongToBeat fetch so the game's Main Story length
     * becomes available for goal progress. The fetch is best-effort — a lookup failure never
     * blocks the tag nor clears cached data.
     */
    suspend fun tagGoal(appId: Long) {
        gameDao.setGoalFlag(appId, isGoal = true)
        val game = gameDao.getById(appId) ?: return
        runCatching { hltbRepository.fetchForGame(appId, game.name) }
    }

    /** Remove a game's goal tag and clear its target. */
    suspend fun untagGoal(appId: Long) =
        gameDao.setGoal(appId, isGoal = false, targetMinutes = null)

    /** Joins each game with its cached HLTB row and maps both into [LibraryGame]. */
    private fun Flow<List<Game>>.withHltb(): Flow<List<LibraryGame>> =
        combine(hltbRepository.allData) { games, hltb ->
            val rowByAppId = hltb.associateBy(HltbData::appId)
            games.map { it.toDomain(rowByAppId[it.appId]) }
        }
}

private fun Game.toDomain(hltb: HltbData?) = LibraryGame(
    appId = appId,
    name = name,
    iconUrl = iconUrl,
    headerUrl = SteamIconMapper.headerUrl(appId),
    playtimeForever = playtimeForever,
    playtime2Weeks = playtime2Weeks,
    backfillMinutes = backfillMinutes,
    completionistMinutes = hltb?.completionistMinutes,
    mainStoryMinutes = hltb?.mainStoryMinutes,
    mainExtraMinutes = hltb?.mainExtraMinutes,
    allStylesMinutes = hltb?.allStylesMinutes,
    hltbMatchState = hltb?.matchStatus?.toDomain(),
    isGoal = isGoal,
)

/** Storage → domain status mapping; internal so [HltbRepository] can report batch outcomes. */
internal fun HltbMatchStatus.toDomain() = when (this) {
    HltbMatchStatus.RESOLVED -> HltbMatchState.RESOLVED
    HltbMatchStatus.NEEDS_REVIEW -> HltbMatchState.NEEDS_REVIEW
    HltbMatchStatus.UNMATCHED -> HltbMatchState.UNMATCHED
}
