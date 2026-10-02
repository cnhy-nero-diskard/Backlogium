package com.example.backlogium.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.backlogium.data.repo.CredentialsRepository
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
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val HISTORY_WINDOW_STEP_DAYS = 30

/** The next History window size used by the explicit “Load older” action. */
internal fun nextHistoryWindowDays(currentWindowDays: Int): Int {
    require(currentWindowDays > 0) { "currentWindowDays must be positive" }
    return currentWindowDays + HISTORY_WINDOW_STEP_DAYS
}

data class HistoryUiState(
    val loading: Boolean = true,
    val accountId: String = "",
    val updating: Boolean = false,
    val detailUnavailable: Boolean = false,
    val configured: Boolean = true,
    val days: List<HistoryDayGroup> = emptyList(),
    /** Today's local date (ISO), so the screen can expand it by default without its own clock. */
    val today: String = "",
    /** First included local date for the currently loaded History window. */
    val windowStartDate: String = "",
    val hasOlderHistory: Boolean = false,
    val cloudReaderConfigured: Boolean = false,
    val cloudReadSummary: CloudReadSummary = CloudReadSummary(),
    val statusNow: Long = 0L,
)

data class HistoryReveal(val date: String, val appId: Long, val sessionId: Long)

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val activity: com.example.backlogium.data.repo.DailyActivityRepository,
    private val credentials: CredentialsRepository,
    private val time: TimeProvider,
    private val currentDate: CurrentDateProvider,
    private val cloudPresence: CloudPresenceRepository,
) : ViewModel() {

    private val detailUnavailable = MutableStateFlow(false)
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

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<HistoryUiState> = credentials.steamIdFlow.distinctUntilChanged()
        .flatMapLatest { accountId ->
            windowDays.value = INITIAL_WINDOW_DAYS
            revealState.value = null
            detailUnavailable.value = false
            var previous: HistoryUiState? = null
            combine(windowDays, currentDate.currentDate, ::Pair).flatMapLatest { (window, today) ->
                val start = today.minusDays((window - 1).toLong())
                activity.observeWindow(start, today, accountId.orEmpty()).map { snapshot ->
                    HistoryUiState(
                        loading = false, configured = accountId != null, accountId = accountId.orEmpty(),
                        days = groupHistory(snapshot.sessions, snapshot.games, snapshot.progress,
                            snapshot.achievements, time.zone(), snapshot.start.toString(),
                            snapshot.endInclusive.toString(), snapshot.accountId),
                        today = snapshot.endInclusive.toString(), windowStartDate = snapshot.start.toString(),
                        hasOlderHistory = snapshot.hasOlder, updating = snapshot.updating,
                    ).also { previous = it }
                }.flowOn(Dispatchers.Default).onStart {
                    emit(previous?.copy(updating = true) ?: HistoryUiState(
                        configured = accountId != null, accountId = accountId.orEmpty()))
                }
            }
        }.combine(cloudPresence.configuration) { state, reader ->
            state.copy(cloudReaderConfigured = reader != null)
        }.combine(cloudPresence.readSummary) { state, summary -> state.copy(cloudReadSummary = summary) }
        .combine(statusTimeMillis) { state, now -> state.copy(statusNow = now) }
        .combine(detailUnavailable) { state, unavailable -> state.copy(detailUnavailable = unavailable) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())

    fun openGame(appId: Long, onOpen: (Long) -> Unit) {
        viewModelScope.launch {
            val account = uiState.value.accountId
            if (credentials.currentCredentials()?.steamId == account && activity.detailAvailable(appId) &&
                uiState.value.accountId == account && uiState.value.days.any { day ->
                    day.games.any { it.appId == appId && it.detailAvailable }
                }) {
                detailUnavailable.value = false
                onOpen(appId)
            } else detailUnavailable.value = true
        }
    }

    /** Widen the window by another 30 days, appending older days to the list. */
    fun loadOlder() {
        windowDays.value = nextHistoryWindowDays(windowDays.value)
    }

    private companion object {
        const val INITIAL_WINDOW_DAYS = 30
    }
}
