package com.example.backlogium.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.backlogium.BuildConfig
import com.example.backlogium.data.local.SettingsDataStore
import com.example.backlogium.data.repo.GameRepository
import com.example.backlogium.gamification.Gamification
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class GoalGameUi(
    val appId: Long,
    val name: String,
    val iconUrl: String,
    val playtimeForever: Int,
    val targetMinutes: Int,
    val progress: Float,
)

data class BacklogGameUi(
    val appId: Long,
    val name: String,
    val iconUrl: String,
    val playtimeForever: Int,
)

data class LibraryUiState(
    val loading: Boolean = true,
    val configured: Boolean = true,
    val goalGames: List<GoalGameUi> = emptyList(),
    val backlog: List<BacklogGameUi> = emptyList(),
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val gameRepository: GameRepository,
    private val settings: SettingsDataStore,
) : ViewModel() {

    val uiState: StateFlow<LibraryUiState> = combine(
        gameRepository.goalGames,
        gameRepository.backlog,
        settings.steamIdFlow,
    ) { goals, backlog, steamId ->
        LibraryUiState(
            loading = false,
            configured = BuildConfig.STEAM_API_KEY.isNotBlank() && steamId.isNotBlank(),
            goalGames = goals.map { game ->
                val target = game.targetMinutes ?: 0
                GoalGameUi(
                    appId = game.appId,
                    name = game.name,
                    iconUrl = game.iconUrl,
                    playtimeForever = game.playtimeForever,
                    targetMinutes = target,
                    progress = Gamification.goalProgress(game.playtimeForever, target).fraction.toFloat(),
                )
            },
            backlog = backlog.map { game ->
                BacklogGameUi(
                    appId = game.appId,
                    name = game.name,
                    iconUrl = game.iconUrl,
                    playtimeForever = game.playtimeForever,
                )
            },
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = LibraryUiState(),
    )

    fun tagGoal(appId: Long, targetMinutes: Int) = viewModelScope.launch {
        gameRepository.tagGoal(appId, targetMinutes)
    }

    fun untagGoal(appId: Long) = viewModelScope.launch {
        gameRepository.untagGoal(appId)
    }
}
