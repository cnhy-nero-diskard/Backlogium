package com.example.backlogium.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.backlogium.data.repo.AchievementRepository
import com.example.backlogium.data.repo.CredentialsRepository
import com.example.backlogium.data.repo.CredentialsState
import com.example.backlogium.data.repo.GameRepository
import com.example.backlogium.data.repo.PlaySession
import com.example.backlogium.data.repo.ProfileRepository
import com.example.backlogium.data.repo.SessionRepository
import com.example.backlogium.data.repo.CloudPresenceRepository
import com.example.backlogium.data.repo.CloudReadSummary
import com.example.backlogium.domain.CreditedAllocation
import com.example.backlogium.domain.CurrentDateProvider
import com.example.backlogium.domain.PlaytimeActivity
import com.example.backlogium.domain.PlaytimeAllocationMethod
import com.example.backlogium.domain.PlaytimeRecoveryStatus
import com.example.backlogium.domain.TimeProvider
import com.example.backlogium.domain.PlaytimeReadProjector.toCreditedAllocation
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
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
    /** Honest recovery disclosure; see [RecoveryStatusUi]. */
    val recoveryStatus: RecoveryStatusUi = RecoveryStatusUi.KNOWN_GOOD,
    /** Authorized minutes with no valid date window yet; disclosed separately from dated rows. */
    val pendingDateUnknownMinutes: Int = 0,
)

data class HistoryReveal(val date: String, val appId: Long, val sessionId: Long)

/** One windowed snapshot feeding History: sessions, allocations, paging anchor and recovery. */
private data class HistoryWindowedInputs(
    val sessions: List<PlaySession>,
    val activities: List<PlaytimeActivity>,
    val earliestCanonicalDate: String?,
    val pendingMinutes: Int,
    val domainStatus: PlaytimeRecoveryStatus,
)

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
            val windowEndExclusive = today.plusDays(1)
            val windowEndMillis = windowEndExclusive.atStartOfDay(time.zone()).toInstant().toEpochMilli()
            combine(
                combine(
                    sessionRepository.sessionsBetween(cutoff, windowEndMillis),
                    sessionRepository.activityBetween(
                        zone = time.zone(),
                        startDateInclusive = LocalDate.parse(windowStartDate),
                        endDateExclusive = windowEndExclusive,
                    ),
                    sessionRepository.earliestCanonicalActivityDate,
                    combine(
                        sessionRepository.pendingDateUnknownQuantity,
                        sessionRepository.recoveryStatus,
                    ) { pending, status ->
                        pending to status
                    },
                ) { sessions, activities, earliestCanonicalDate, recovery ->
                    HistoryWindowedInputs(
                        sessions = sessions,
                        activities = activities,
                        earliestCanonicalDate = earliestCanonicalDate?.toString(),
                        pendingMinutes = recovery.first,
                        domainStatus = recovery.second,
                    )
                },
                gameRepository.library,
                profileRepository.dailyProgress,
                achievementRepository.unlockedSince(cutoff),
                credentials.credentialsStateFlow,
            ) { windowed, games, dailyProgress, achievements, credState ->
                HistoryUiState(
                    loading = false,
                    configured = credState is CredentialsState.Configured,
                    days = groupHistory(
                        sessions = windowed.sessions,
                        sessionActivities = windowed.activities
                            .filterIsInstance<PlaytimeActivity.TimestampedSession>(),
                        games = games,
                        dailyProgress = dailyProgress,
                        achievementUnlocks = achievements,
                        zone = time.zone(),
                        allocations = windowed.activities
                            .filterIsInstance<PlaytimeActivity.DatedAllocation>()
                            .map { it.toCreditedAllocation().toHistoryAllocationUi() },
                        windowStartDate = windowStartDate,
                        windowEndDate = today.toString(),
                    ),
                    today = today.toString(),
                    windowStartDate = windowStartDate,
                    hasOlderHistory = historyHasOlderRecords(
                        windowStartDate, null, dailyProgress, time.zone(),
                        earliestAllocationDate = windowed.earliestCanonicalDate,
                    ),
                    recoveryStatus = if (windowed.pendingMinutes > 0) {
                        RecoveryStatusUi.PENDING_DATE_QUANTITY
                    } else {
                        windowed.domainStatus.toRecoveryStatusUi()
                    },
                    pendingDateUnknownMinutes = windowed.pendingMinutes,
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

    /** Widen the window by another 30 days, appending older days to the list. */
    fun loadOlder() {
        windowDays.value = nextHistoryWindowDays(windowDays.value)
    }

    private companion object {
        const val INITIAL_WINDOW_DAYS = 30
    }
}

/**
 * Recovery mapping (recover-missed-playtime tasks 7.1/7.4): maps the canonical dated-activity
 * projection [CreditedAllocation] and the honest recovery-status vocabulary that the domain
 * agent exposes on [SessionRepository] into the History tree's leaf model and status enum.
 *
 * The landed flows are `datedAllocationsBetween(startInclusiveDate, endInclusiveDate)`,
 * `pendingDateUnknownQuantity`, `earliestCanonicalActivityDate` and `recoveryStatus`, with
 * [CreditedAllocation] and [PlaytimeRecoveryStatus] living in `com.example.backlogium.domain`.
 * Pending date-unknown quantity takes precedence over the persisted account-scoped domain status
 * in the status row; an observation instant is never presented as a gameplay boundary.
 */
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
