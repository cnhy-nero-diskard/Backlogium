package com.example.backlogium.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.backlogium.BuildConfig
import com.example.backlogium.data.local.SettingsDataStore
import com.example.backlogium.data.repo.GameRepository
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
                GoalGameUi(
                    appId = game.appId,
                    name = game.name,
                    iconUrl = game.iconUrl,
                    playtimeForever = game.playtimeForever,
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

    fun tagGoal(appId: Long) = viewModelScope.launch {
        gameRepository.tagGoal(appId)
    }

    fun untagGoal(appId: Long) = viewModelScope.launch {
        gameRepository.untagGoal(appId)
    }
}
