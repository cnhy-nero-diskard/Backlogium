package com.example.backlogium.ui.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.backlogium.data.repo.CloudPresenceRepository
import com.example.backlogium.data.repo.CloudPresenceSnapshot
import com.example.backlogium.data.repo.HiddenGamesRepository
import com.example.backlogium.data.repo.PlaySession
import com.example.backlogium.data.repo.SessionRepository
import com.example.backlogium.domain.CloudCoverageState
import com.example.backlogium.domain.CloudPresenceInterval
import com.example.backlogium.domain.TimeProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class CloudDiagnosticsUiState(
    val configured: Boolean = false,
    val snapshot: CloudPresenceSnapshot? = null,
    val intervals: List<CloudPresenceInterval> = emptyList(),
    val localSessions: List<PlaySession> = emptyList(),
    val dateDisagreements: List<CloudDateDisagreement> = emptyList(),
)

data class CloudDateDisagreement(
    val appId: Long,
    val gameName: String?,
    val cloudDate: String,
    val localDate: String,
)

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class CloudPresenceDiagnosticsViewModel @Inject constructor(
    cloudPresence: CloudPresenceRepository,
    sessions: SessionRepository,
    hiddenGames: HiddenGamesRepository,
    private val time: TimeProvider,
) : ViewModel() {
    val state: StateFlow<CloudDiagnosticsUiState> = combine(
        cloudPresence.configuration,
        cloudPresence.snapshot,
    ) { configuration, snapshot -> Pair(configuration != null, snapshot) }
        .flatMapLatest { (configured, snapshot) ->
            when {
                !configured -> flowOf(CloudDiagnosticsUiState())
                snapshot == null -> flowOf(CloudDiagnosticsUiState(configured = true))
                else -> combine(
                    sessions.sessionsBetween(snapshot.windowStart, snapshot.windowEnd),
                    hiddenGames.hiddenAppIds,
                ) { localSessions, hiddenAppIds ->
                    projectCloudDiagnostics(snapshot, localSessions, hiddenAppIds, time.zone())
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CloudDiagnosticsUiState())

}

internal fun projectCloudDiagnostics(
    snapshot: CloudPresenceSnapshot,
    localSessions: List<PlaySession>,
    hiddenAppIds: Set<Long>,
    zone: ZoneId,
): CloudDiagnosticsUiState {
    val visibleIntervals = snapshot.intervals.filterNot { it.appId in hiddenAppIds }
    val disagreements = visibleIntervals.flatMap { interval ->
        val cloudDate = Instant.ofEpochMilli(interval.startAt).atZone(zone).toLocalDate().toString()
        localSessions.asSequence()
            .filter { it.appId == interval.appId }
            .map { Instant.ofEpochMilli(it.startAt).atZone(zone).toLocalDate().toString() }
            .distinct()
            .filter { it != cloudDate }
            .map { localDate ->
                CloudDateDisagreement(interval.appId, interval.gameName, cloudDate, localDate)
            }
            .toList()
    }.distinct()
    return CloudDiagnosticsUiState(
        configured = true,
        snapshot = snapshot,
        intervals = visibleIntervals,
        localSessions = localSessions,
        dateDisagreements = disagreements,
    )
}
