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

    /** Tag a game as a goal with a target (minutes), or update an existing goal's target. */
    suspend fun tagGoal(appId: Long, targetMinutes: Int) =
        gameDao.setGoal(appId, isGoal = true, targetMinutes = targetMinutes)

    /** Remove a game's goal tag and clear its target. */
    suspend fun untagGoal(appId: Long) =
        gameDao.setGoal(appId, isGoal = false, targetMinutes = null)
}
