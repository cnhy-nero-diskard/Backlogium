package com.example.backlogium.ui.review

import com.example.backlogium.data.repo.HltbMatchState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** In-memory state owned by the reviewer route's ViewModel, never saved to disk or saved state. */
internal data class ReviewSessionState(
    val loading: Boolean = true,
    val games: List<MatchCenterGameUi> = emptyList(),
    val selection: MatchCenterSelection = MatchCenterSelection(0, null),
    val deferredAppIds: Set<Long> = emptySet(),
    val scopedAppId: Long? = null,
) {
    fun toUiState() = HltbMatchCenterUiState(
        loading = loading,
        ambiguous = games.filter { it.matchStatus == HltbMatchState.NEEDS_REVIEW },
        unmatched = games.filter { it.matchStatus == HltbMatchState.UNMATCHED },
        selectedIndex = if (selection.persistedAppId == null) -1 else selection.index,
        deferredAppIds = deferredAppIds,
        scopedAppId = scopedAppId,
        scopedAppMissing = !loading && isScopedAppMissing(scopedAppId, games),
    )

    fun reconciled() = copy(selection = resolveMatchCenterSelection(selection, games, deferredAppIds, scopedAppId))
}

internal class HltbReviewSession {
    private val mutableState = MutableStateFlow(ReviewSessionState())
    val state = mutableState.asStateFlow()

    fun updateQueue(games: List<MatchCenterGameUi>) {
        val displayOrder = games.filter { it.matchStatus == HltbMatchState.NEEDS_REVIEW } +
            games.filter { it.matchStatus == HltbMatchState.UNMATCHED }
        mutableState.update { it.copy(loading = false, games = displayOrder).reconciled() }
    }

    // Composition re-entry and configuration recreation must not restart a scoped pass.
    fun selectGame(appId: Long) {
        mutableState.update {
            if (it.scopedAppId == appId) it else it.copy(
                scopedAppId = appId,
                selection = MatchCenterSelection(0, appId),
            ).let { scoped -> if (scoped.loading) scoped else scoped.reconciled() }
        }
    }

    fun skip(expectedAppId: Long? = null) {
        mutableState.update { prior ->
            val appId = prior.selection.persistedAppId ?: return@update prior
            if (expectedAppId != null && expectedAppId != appId) return@update prior
            prior.copy(deferredAppIds = prior.deferredAppIds + appId).reconciled()
        }
    }

    fun navigate(direction: Int, expectedAppId: Long? = null) {
        mutableState.update { prior ->
            if (expectedAppId != null && expectedAppId != prior.selection.persistedAppId) return@update prior
            val ui = prior.toUiState()
            val target = if (direction > 0) ui.nextGame else ui.previousGame
            if (target == null) return@update prior
            prior.copy(
                deferredAppIds = prior.deferredAppIds + listOfNotNull(prior.selection.persistedAppId),
                selection = MatchCenterSelection(prior.games.indexOf(target), target.appId),
            ).reconciled()
        }
    }

    fun selectIndex(index: Int) {
        mutableState.update { prior ->
            val target = prior.games.getOrNull(index) ?: return@update prior
            if (target.appId in prior.deferredAppIds ||
                (prior.scopedAppId != null && target.appId != prior.scopedAppId) ||
                target.appId == prior.selection.persistedAppId
            ) return@update prior
            prior.copy(
                deferredAppIds = prior.deferredAppIds + listOfNotNull(prior.selection.persistedAppId),
                selection = MatchCenterSelection(index, target.appId),
            ).reconciled()
        }
    }

    fun reviewSkipped() {
        mutableState.update {
            it.copy(deferredAppIds = emptySet(), selection = MatchCenterSelection(0, null)).reconciled()
        }
    }
}
