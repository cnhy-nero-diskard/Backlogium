package com.example.backlogium.ui.settings.hidden

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.backlogium.data.repo.HiddenGameEntry
import com.example.backlogium.data.repo.HiddenGamesRepository
import com.example.backlogium.data.repo.NonGameCandidate
import com.example.backlogium.domain.GameVisibilityUseCase
import com.example.backlogium.domain.VisibilityChangeEffect
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * What the hidden-games section shows.
 *
 * [hidden] is the recoverability guarantee made visible: a game that can be hidden and not found
 * again is a trap, so this list exists for exactly the games every other surface omits.
 */
data class HiddenGamesUiState(
    val loading: Boolean = true,
    val hidden: List<HiddenGameEntry> = emptyList(),
    /** Library items the store reports as non-games; empty when there is nothing to propose. */
    val nonGameCandidates: List<NonGameCandidate> = emptyList(),
    /** True while the non-game review is open. Nothing is hidden by opening it. */
    val reviewOpen: Boolean = false,
    /** Which reviewed items the player has kept selected; store types are sometimes wrong. */
    val selectedCandidates: Set<Long> = emptySet(),
    /** The disclosed effect awaiting confirmation, for any hide or unhide from this screen. */
    val pendingEffect: VisibilityChangeEffect? = null,
    /** True while a preview's real recompute is running. */
    val previewing: Boolean = false,
) {
    val nothingHidden: Boolean get() = !loading && hidden.isEmpty()
}

/**
 * Drives the hidden-games section: the list of what is hidden, individual and bulk unhiding, and
 * the non-game bulk review (add-hidden-games).
 *
 * Every mutation goes through [GameVisibilityUseCase], so each one discloses its concrete XP and
 * level effect first and none can skip the recompute that must follow it.
 */
@HiltViewModel
class HiddenGamesViewModel @Inject constructor(
    private val hiddenGames: HiddenGamesRepository,
    private val visibility: GameVisibilityUseCase,
) : ViewModel() {

    private val reviewOpen = MutableStateFlow(false)
    private val selected = MutableStateFlow<Set<Long>>(emptySet())
    private val pendingEffect = MutableStateFlow<VisibilityChangeEffect?>(null)
    private val previewing = MutableStateFlow(false)

    private val local = combine(reviewOpen, selected, pendingEffect, previewing) { open, sel, pending, isPreviewing ->
        Local(open, sel, pending, isPreviewing)
    }

    private data class Local(
        val reviewOpen: Boolean,
        val selected: Set<Long>,
        val pendingEffect: VisibilityChangeEffect?,
        val previewing: Boolean,
    )

    val uiState: StateFlow<HiddenGamesUiState> = combine(
        hiddenGames.hiddenGames,
        hiddenGames.nonGameCandidates,
        local,
    ) { hidden, candidates, state ->
        HiddenGamesUiState(
            loading = false,
            hidden = hidden,
            nonGameCandidates = candidates,
            reviewOpen = state.reviewOpen,
            // A candidate that has since been hidden elsewhere drops out of the offer and out of
            // the selection with it, so a confirm can never act on a stale list.
            selectedCandidates = state.selected.intersect(candidates.map { it.appId }.toSet()),
            pendingEffect = state.pendingEffect,
            previewing = state.previewing,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HiddenGamesUiState(),
    )

    /** Open the review with every proposed item selected; the player deselects what is a game. */
    fun openNonGameReview() {
        selected.value = uiState.value.nonGameCandidates.map { it.appId }.toSet()
        reviewOpen.value = true
    }

    fun closeNonGameReview() {
        reviewOpen.value = false
        selected.value = emptySet()
    }

    fun toggleCandidate(appId: Long) {
        selected.value = if (appId in selected.value) selected.value - appId else selected.value + appId
    }

    /** Disclose the combined effect of hiding everything still selected in the review. */
    fun requestBulkHide() {
        val appIds = uiState.value.selectedCandidates.toList()
        if (appIds.isEmpty()) return
        preview { visibility.previewHide(appIds) }
    }

    fun requestUnhide(appId: Long) = preview { visibility.previewUnhide(listOf(appId)) }

    fun requestUnhideAll() {
        val appIds = uiState.value.hidden.map { it.appId }
        if (appIds.isEmpty()) return
        preview { visibility.previewUnhide(appIds) }
    }

    /** Apply the disclosed change. A bulk hide is recorded as one, so the list can say so. */
    fun confirm() {
        val effect = pendingEffect.value ?: return
        val bulk = effect.hiding && reviewOpen.value
        pendingEffect.value = null
        viewModelScope.launch {
            if (effect.hiding) {
                visibility.hide(effect.appIds, fromBulkAction = bulk)
            } else {
                visibility.unhide(effect.appIds)
            }
            if (bulk) closeNonGameReview()
        }
    }

    /** Decline: nothing is hidden or unhidden, and no derived value moves. */
    fun dismiss() {
        pendingEffect.value = null
    }

    private fun preview(compute: suspend () -> VisibilityChangeEffect) {
        if (previewing.value || pendingEffect.value != null) return
        previewing.value = true
        viewModelScope.launch {
            try {
                pendingEffect.value = compute()
            } finally {
                previewing.value = false
            }
        }
    }
}
