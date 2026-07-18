package com.example.backlogium.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.backlogium.BuildConfig
import com.example.backlogium.data.local.SettingsDataStore
import com.example.backlogium.data.repo.GameRepository
import com.example.backlogium.data.repo.ProfileRepository
import com.example.backlogium.data.repo.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class SessionUi(
    val id: Long,
    val gameName: String,
    val startAt: Long,
    val minutes: Int,
    val open: Boolean,
)

data class DayStatUi(
    val date: String,
    val minutesPlayed: Int,
    val goalMinutesPlayed: Int,
    val questMet: Boolean,
)

data class HistoryUiState(
    val loading: Boolean = true,
    val configured: Boolean = true,
    val sessions: List<SessionUi> = emptyList(),
    val days: List<DayStatUi> = emptyList(),
)

@HiltViewModel
class HistoryViewModel @Inject constructor(
    sessionRepository: SessionRepository,
    gameRepository: GameRepository,
    profileRepository: ProfileRepository,
    settings: SettingsDataStore,
) : ViewModel() {

    val uiState: StateFlow<HistoryUiState> = combine(
        sessionRepository.recentSessions,
        gameRepository.library,
        profileRepository.dailyProgress,
        settings.steamIdFlow,
    ) { sessions, games, days, steamId ->
        val nameById = games.associate { it.appId to it.name }
        HistoryUiState(
            loading = false,
            configured = BuildConfig.STEAM_API_KEY.isNotBlank() && steamId.isNotBlank(),
            sessions = sessions.map { session ->
                SessionUi(
                    id = session.id,
                    gameName = nameById[session.appId] ?: "App ${session.appId}",
                    startAt = session.startAt,
                    minutes = session.minutes,
                    open = session.open,
                )
            },
            days = days.map { day ->
                DayStatUi(
                    date = day.date,
                    minutesPlayed = day.minutesPlayed,
                    goalMinutesPlayed = day.goalMinutesPlayed,
                    questMet = day.questMet,
                )
            },
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HistoryUiState(),
    )
}
