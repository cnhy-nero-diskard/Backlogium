package com.example.backlogium.data.repo

import com.example.backlogium.data.local.dao.GameDao
import com.example.backlogium.data.local.entity.Game
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** Read/write access to the game library, exposing Room as observable [Flow]s. */
@Singleton
class GameRepository @Inject constructor(
    private val gameDao: GameDao,
    private val hltbRepository: HltbRepository,
) {
    val library: Flow<List<Game>> = gameDao.observeLibrary()
    val goalGames: Flow<List<Game>> = gameDao.observeGoalGames()
    val backlog: Flow<List<Game>> = gameDao.observeBacklog()

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
}
