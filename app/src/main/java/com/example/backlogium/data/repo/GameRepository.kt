package com.example.backlogium.data.repo

import com.example.backlogium.data.local.dao.GameDao
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.HltbData
import com.example.backlogium.data.local.entity.HltbMatchStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

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
 * [completionistMinutes] is null until a match resolves; [hltbMatchState] is null when no
 * lookup has been stored for this game yet.
 */
data class LibraryGame(
    val appId: Long,
    val name: String,
    val iconUrl: String,
    val playtimeForever: Int,
    val completionistMinutes: Int? = null,
    val mainStoryMinutes: Int? = null,
    val hltbMatchState: HltbMatchState? = null,
)

/** Read/write access to the game library, exposing domain models as observable [Flow]s. */
@Singleton
class GameRepository @Inject constructor(
    private val gameDao: GameDao,
    private val hltbRepository: HltbRepository,
) {
    val library: Flow<List<LibraryGame>> = gameDao.observeLibrary().withHltb()
    val goalGames: Flow<List<LibraryGame>> = gameDao.observeGoalGames().withHltb()
    val backlog: Flow<List<LibraryGame>> = gameDao.observeBacklog().withHltb()

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
    playtimeForever = playtimeForever,
    completionistMinutes = hltb?.completionistMinutes,
    mainStoryMinutes = hltb?.mainStoryMinutes,
    hltbMatchState = hltb?.matchStatus?.toDomain(),
)

private fun HltbMatchStatus.toDomain() = when (this) {
    HltbMatchStatus.RESOLVED -> HltbMatchState.RESOLVED
    HltbMatchStatus.NEEDS_REVIEW -> HltbMatchState.NEEDS_REVIEW
    HltbMatchStatus.UNMATCHED -> HltbMatchState.UNMATCHED
}
