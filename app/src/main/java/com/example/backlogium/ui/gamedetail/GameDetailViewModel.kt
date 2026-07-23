package com.example.backlogium.ui.gamedetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.backlogium.data.local.SettingsDataStore
import com.example.backlogium.data.local.entity.Achievement
import com.example.backlogium.data.repo.AchievementRepository
import com.example.backlogium.data.repo.GameRepository
import com.example.backlogium.gamification.AchievementInput
import com.example.backlogium.gamification.Gamification
import com.example.backlogium.gamification.RarityTier
import com.example.backlogium.gamification.RuleConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** One achievement row, pre-resolved against the engine's tier/XP rules for display. */
data class AchievementUi(
    val apiName: String,
    val displayName: String,
    val iconUrl: String?,
    val unlocked: Boolean,
    val tier: RarityTier?,
    val xp: Int,
)

data class GameDetailUiState(
    val loading: Boolean = true,
    val gameName: String = "",
    val achievements: List<AchievementUi> = emptyList(),
)

/**
 * Drives the per-game detail screen: this game's achievements, each resolved to a rarity tier
 * and XP contribution via the engine's own `tierFor`/`achievementXp` (using the persisted
 * rarity snapshot, never the live percent — same rule the recompute uses).
 */
@HiltViewModel
class GameDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    achievementRepository: AchievementRepository,
    gameRepository: GameRepository,
    settings: SettingsDataStore,
) : ViewModel() {

    private val appId: Long = checkNotNull(savedStateHandle["appId"])

    val uiState: StateFlow<GameDetailUiState> = combine(
        gameRepository.library,
        achievementRepository.observeForGame(appId),
        settings.ruleConfigFlow,
    ) { games, achievements, config ->
        GameDetailUiState(
            loading = false,
            gameName = games.firstOrNull { it.appId == appId }?.name ?: "",
            achievements = achievements.map { it.toUi(config) },
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = GameDetailUiState(),
    )
}

private fun Achievement.toUi(config: RuleConfig): AchievementUi {
    val percent = snapshotPercent
    val tierable = unlocked && percent != null
    return AchievementUi(
        apiName = apiName,
        displayName = displayName?.takeIf { it.isNotBlank() } ?: apiName,
        iconUrl = iconUrl,
        unlocked = unlocked,
        tier = if (tierable) Gamification.tierFor(percent!!) else null,
        xp = if (tierable) {
            Gamification.achievementXp(listOf(AchievementInput(apiName, true, percent)), config)
        } else {
            0
        },
    )
}
