package com.example.backlogium.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.backlogium.data.repo.AchievementRepository
import com.example.backlogium.data.repo.CredentialsRepository
import com.example.backlogium.data.repo.CredentialsState
import com.example.backlogium.data.repo.GameRepository
import com.example.backlogium.data.repo.ProfileRepository
import com.example.backlogium.data.repo.SessionRepository
import com.example.backlogium.data.repo.CloudPresenceRepository
import com.example.backlogium.data.repo.CloudReadSummary
import com.example.backlogium.domain.CurrentDateProvider
import com.example.backlogium.domain.TimeProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class HistoryUiState(
    val loading: Boolean = true,
    val configured: Boolean = true,
    val days: List<HistoryDayGroup> = emptyList(),
    /** Today's local date (ISO), so the screen can expand it by default without its own clock. */
    val today: String = "",
    /** First included local date for the currently loaded History window. */
    val windowStartDate: String = "",
    val cloudReaderConfigured: Boolean = false,
    val cloudReadSummary: CloudReadSummary = CloudReadSummary(),
    val statusNow: Long = 0L,
)

data class HistoryReveal(val date: String, val appId: Long, val sessionId: Long)

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val sessionRepository: SessionRepository,
    private val gameRepository: GameRepository,
    private val profileRepository: ProfileRepository,
    private val achievementRepository: AchievementRepository,
    private val credentials: CredentialsRepository,
    private val time: TimeProvider,
    private val currentDate: CurrentDateProvider,
    private val cloudPresence: CloudPresenceRepository,
) : ViewModel() {

    private val revealState = MutableStateFlow<HistoryReveal?>(null)
    val reveal: StateFlow<HistoryReveal?> = revealState
    private val statusTimeMillis = MutableStateFlow(time.nowMillis())

    fun revealSession(item: HistoryContribution) {
        revealState.value = HistoryReveal(item.date, item.game.appId, item.session.id)
    }

    fun clearReveal() { revealState.value = null }

    fun refreshStatusTime() {
        statusTimeMillis.value = time.nowMillis()
    }

    /**
     * How many trailing calendar days are in view. Transient (not persisted): the screen opens
     * back at [INITIAL_WINDOW_DAYS] every time; [loadOlder] widens it for the current session.
     */
    private val windowDays = MutableStateFlow(INITIAL_WINDOW_DAYS)

    // Both the window cutoff and the expand-today anchor are dated, so the date joins the window as
    // an input: crossing midnight has to re-derive them without waiting for a sync to arrive.
    val uiState: StateFlow<HistoryUiState> = combine(windowDays, currentDate.currentDate, ::Pair)
        .flatMapLatest { (window, today) ->
            val cutoff = historyWindowCutoffMillis(window, today, time.zone())
            val windowStartDate = today.minusDays((window - 1).toLong()).toString()
            combine(
                sessionRepository.sessionsSince(cutoff),
                gameRepository.library,
                profileRepository.dailyProgress,
                achievementRepository.unlockedSince(cutoff),
                credentials.credentialsStateFlow,
            ) { sessions, games, dailyProgress, achievements, credState ->
                HistoryUiState(
                    loading = false,
                    configured = credState is CredentialsState.Configured,
                    days = groupHistory(
                        sessions = sessions,
                        games = games,
                        dailyProgress = dailyProgress,
                        achievementUnlocks = achievements,
                        zone = time.zone(),
                    ),
                    today = today.toString(),
                    windowStartDate = windowStartDate,
                )
            }.combine(cloudPresence.configuration) { state, reader ->
                state.copy(cloudReaderConfigured = reader != null)
            }.combine(cloudPresence.readSummary) { state, summary ->
                state.copy(cloudReadSummary = summary)
            }.combine(statusTimeMillis) { state, now ->
                state.copy(statusNow = now)
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = HistoryUiState(),
        )

    /** Widen the window by another [WINDOW_STEP_DAYS] days, appending older days to the list. */
    fun loadOlder() {
        windowDays.value += WINDOW_STEP_DAYS
    }

    private companion object {
        const val INITIAL_WINDOW_DAYS = 30
        const val WINDOW_STEP_DAYS = 30
    }
}
