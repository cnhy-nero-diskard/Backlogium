package com.example.backlogium.ui.history

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.backlogium.data.repo.CredentialsRepository
import com.example.backlogium.data.repo.DailyActivityRepository
import com.example.backlogium.data.repo.SessionRepository
import com.example.backlogium.domain.CreditedAllocation
import com.example.backlogium.domain.PlaytimeActivity
import com.example.backlogium.domain.PlaytimeAllocationMethod
import com.example.backlogium.domain.PlaytimeReadProjector.toCreditedAllocation
import com.example.backlogium.domain.PlaytimeRecoveryStatus
import com.example.backlogium.data.repo.CloudPresenceRepository
import com.example.backlogium.data.repo.CloudReadSummary
import com.example.backlogium.domain.CurrentDateProvider
import com.example.backlogium.domain.TimeProvider
import com.example.backlogium.ui.util.observeCalendar
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.flowOf
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

/**
 * What recovery disclosure the History screen owes the reader (recover-missed-playtime app-ui
 * spec). Distinct, honest statuses: the interface never claims that unreported play was seen in
 * real time, and last-good figures stay on screen in every non-ideal state.
 */
enum class RecoveryStatusUi {
    /** Nothing to disclose: no pending or unavailable recovery signal. */
    KNOWN_GOOD,

    /** Steam playtime is unavailable/private; recovery cannot read a new total. */
    REMOTE_UNAVAILABLE,

    /** Play was observed locally but Steam has not yet published an updated total. */
    OBSERVED_AWAITING_STEAM,

    /** A trustworthy unchanged total with no independent sign of pending play. */
    UNCHANGED_NO_KNOWN_PLAY,

    /** Authorized minutes exist but cannot be dated yet (inverted/no-baseline window). */
    PENDING_DATE_QUANTITY,
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
    val recoveryStatus: RecoveryStatusUi = RecoveryStatusUi.KNOWN_GOOD,
    val pendingDateUnknownMinutes: Int = 0,
)

data class HistoryReveal(val date: String, val appId: Long, val sessionId: Long)

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val activity: DailyActivityRepository,
    private val sessionRepository: SessionRepository,
    private val credentials: CredentialsRepository,
    private val time: TimeProvider,
    private val currentDate: CurrentDateProvider,
    private val cloudPresence: CloudPresenceRepository,
    @param:ApplicationContext context: Context,
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

    private val calendar = currentDate.observeCalendar(context)

    private val recoveryDisclosure = combine(
        sessionRepository.pendingDateUnknownQuantity,
        sessionRepository.recoveryStatus,
        ::Pair,
    )

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<HistoryUiState> = credentials.steamIdFlow.distinctUntilChanged()
        .flatMapLatest { accountId ->
            windowDays.value = INITIAL_WINDOW_DAYS
            revealState.value = null
            detailUnavailable.value = false
            if (accountId == null) return@flatMapLatest flowOf(HistoryUiState(loading = false, configured = false))
            var previous: HistoryUiState? = null
            combine(windowDays, calendar, ::Pair).flatMapLatest { (window, calendarDate) ->
                val (today, zone) = calendarDate
                val start = today.minusDays((window - 1).toLong())
                activity.observeWindow(start, today, accountId, zone).map { snapshot ->
                    HistoryUiState(
                        loading = false,
                        accountId = snapshot.accountId,
                        days = groupHistory(
                            sessions = snapshot.sessions,
                            games = snapshot.games,
                            dailyProgress = snapshot.progress,
                            achievementUnlocks = snapshot.achievements,
                            zone = snapshot.zone,
                            sessionActivities = snapshot.activities.filterIsInstance<PlaytimeActivity.TimestampedSession>(),
                            allocations = snapshot.activities.filterIsInstance<PlaytimeActivity.DatedAllocation>()
                                .map { it.toCreditedAllocation().toHistoryAllocationUi() },
                            windowStartDate = snapshot.start.toString(),
                            windowEndDate = snapshot.endInclusive.toString(),
                            accountId = snapshot.accountId,
                        ),
                        today = snapshot.endInclusive.toString(), windowStartDate = snapshot.start.toString(),
                        hasOlderHistory = snapshot.hasOlder, updating = snapshot.updating,
                    ).also { previous = it }
                }.flowOn(Dispatchers.Default).onStart {
                    previous?.let { emit(it.copy(updating = true)) }
                }
            }.combine(recoveryDisclosure) { state, (pending, status) ->
                state.copy(
                    pendingDateUnknownMinutes = if (state.loading) 0 else pending,
                    recoveryStatus = when {
                        state.loading -> RecoveryStatusUi.KNOWN_GOOD
                        pending > 0 -> RecoveryStatusUi.PENDING_DATE_QUANTITY
                        else -> status.toRecoveryStatusUi()
                    },
                )
            }.onStart {
                emit(HistoryUiState(accountId = accountId))
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
                credentials.currentCredentials()?.steamId == account && uiState.value.accountId == account && uiState.value.days.any { day ->
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

internal fun CreditedAllocation.toHistoryAllocationUi(): HistoryAllocationUi {
    val method = when (method) {
        PlaytimeAllocationMethod.SINGLE_DAY_ESTIMATE -> HistoryAllocationMethod.SINGLE_DAY_ESTIMATE
        PlaytimeAllocationMethod.MULTI_DAY_ESTIMATE -> HistoryAllocationMethod.MULTI_DAY_ESTIMATE
        PlaytimeAllocationMethod.KNOWN_TARGETED_DATE -> HistoryAllocationMethod.KNOWN_TARGETED_DATE
        // Timed placement never reaches unknown-time leaves; defensive so the mapping stays total.
        PlaytimeAllocationMethod.EVIDENCE_INFORMED -> HistoryAllocationMethod.KNOWN_TARGETED_DATE
        PlaytimeAllocationMethod.PENDING_DATE_UNKNOWN ->
            error("pending date-unknown quantity cannot be rendered as a dated History allocation")
    }
    val windowStart = windowStartDate ?: date
    val windowEnd = windowEndDate ?: date
    return HistoryAllocationUi(
        placementId = placementId,
        appId = appId,
        date = date,
        minutes = minutes,
        method = method,
        window = HistoryAllocationWindow(
            windowStartDate = windowStart,
            windowEndDate = windowEnd,
            zone = zone,
            totalMinutes = windowTotalMinutes.coerceAtLeast(minutes),
        ),
        goalAtCredit = goalAtCredit,
    )
}

private fun PlaytimeRecoveryStatus.toRecoveryStatusUi(): RecoveryStatusUi = when (this) {
    PlaytimeRecoveryStatus.REMOTE_UNAVAILABLE -> RecoveryStatusUi.REMOTE_UNAVAILABLE
    PlaytimeRecoveryStatus.OBSERVED_AWAITING_STEAM -> RecoveryStatusUi.OBSERVED_AWAITING_STEAM
    PlaytimeRecoveryStatus.UNCHANGED_NO_KNOWN_PLAY -> RecoveryStatusUi.UNCHANGED_NO_KNOWN_PLAY
    PlaytimeRecoveryStatus.KNOWN_GOOD -> RecoveryStatusUi.KNOWN_GOOD
}
