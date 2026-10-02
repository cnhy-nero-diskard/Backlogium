package com.example.backlogium.ui.gamedetail

import com.example.backlogium.domain.GameFavorite
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FavoriteActionState(
    val favorite: GameFavorite? = null,
    val pending: Boolean = false,
    val feedback: String? = null,
    val failed: Boolean = false,
)

/** Retains committed state and an in-flight action across composition/activity recreation. */
class FavoriteActionController(
    private val scope: CoroutineScope,
    private val observe: (Long) -> Flow<GameFavorite>,
    private val write: suspend (Long, Boolean, String) -> Unit,
) {
    private val mutableState = MutableStateFlow(FavoriteActionState())
    val state = mutableState.asStateFlow()
    private var appId: Long? = null
    private var observer: Job? = null

    fun show(id: Long) {
        if (appId == id) return
        appId = id
        observer?.cancel()
        mutableState.value = FavoriteActionState()
        observer = scope.launch {
            var boundIdentity: String? = null
            observe(id).collect { favorite ->
                if (boundIdentity == null) boundIdentity = favorite.steamId
                mutableState.update {
                    if (boundIdentity == favorite.steamId) it.copy(favorite = favorite)
                    else it.copy(favorite = null, feedback = "Account changed. Reopen the game.", failed = true)
                }
            }
        }
    }

    fun toggle() {
        val current = mutableState.value
        val favorite = current.favorite ?: return
        if (current.pending) return
        val desired = !favorite.isFavorite
        mutableState.update { it.copy(pending = true, feedback = null, failed = false) }
        scope.launch {
            try {
                write(favorite.appId, desired, favorite.steamId)
                if (appId == favorite.appId) mutableState.update {
                    if (it.favorite?.steamId == favorite.steamId) {
                        it.copy(
                            favorite = favorite.copy(isFavorite = desired),
                            feedback = if (desired) "Added to favorites" else "Removed from favorites",
                        )
                    } else it
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (appId == favorite.appId) mutableState.update {
                    it.copy(feedback = "Could not update favorites. Retry.", failed = true)
                }
            } finally {
                if (appId == favorite.appId) mutableState.update { it.copy(pending = false) }
            }
        }
    }
}
