package com.example.backlogium.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.backlogium.data.hltb.HltbCandidate
import com.example.backlogium.data.repo.GameRepository
import com.example.backlogium.data.repo.HltbRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ReviewGameUi(
    val appId: Long,
    val name: String,
    val candidates: List<HltbCandidate>,
)

data class HltbReviewUiState(
    val loading: Boolean = true,
    val games: List<ReviewGameUi> = emptyList(),
)

/**
 * Drives the match-review surface: lists games flagged `NEEDS_REVIEW` with their retained
 * candidates (joined with the library for display names). Selecting a candidate resolves the
 * match and removes the game from the list.
 */
@HiltViewModel
class HltbReviewViewModel @Inject constructor(
    private val hltbRepository: HltbRepository,
    private val gameRepository: GameRepository,
) : ViewModel() {

    val uiState: StateFlow<HltbReviewUiState> = combine(
        hltbRepository.needsReview,
        gameRepository.library,
    ) { review, games ->
        val namesByAppId = games.associate { it.appId to it.name }
        HltbReviewUiState(
            loading = false,
            games = review.map { data ->
                ReviewGameUi(
                    appId = data.appId,
                    name = namesByAppId[data.appId] ?: "Unknown game",
                    candidates = hltbRepository.candidatesOf(data),
                )
            },
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HltbReviewUiState(),
    )

    fun resolve(appId: Long, candidate: HltbCandidate) = viewModelScope.launch {
        hltbRepository.resolveMatch(appId, candidate)
    }
}
