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
) {
    val library: Flow<List<Game>> = gameDao.observeLibrary()
    val goalGames: Flow<List<Game>> = gameDao.observeGoalGames()
    val backlog: Flow<List<Game>> = gameDao.observeBacklog()

    /**
     * Mark a game as a goal. No user-entered target is required (restyle-fixes): the manual
     * minutes target is retired until HowLongToBeat-sourced completion lengths arrive, so the
     * dormant [Game.targetMinutes] column is left untouched here.
     */
    suspend fun tagGoal(appId: Long) =
        gameDao.setGoalFlag(appId, isGoal = true)

    /** Remove a game's goal tag and clear its target. */
    suspend fun untagGoal(appId: Long) =
        gameDao.setGoal(appId, isGoal = false, targetMinutes = null)
}
