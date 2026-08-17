package com.example.backlogium.work

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.example.backlogium.data.diagnostics.PresenceDecisionRecorder
import com.example.backlogium.data.diagnostics.PresenceOutcome
import com.example.backlogium.data.local.PresenceMonitoringAvailability
import com.example.backlogium.data.repo.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Requests [PresenceService] only from a context that Android considers foreground-visible.
 *
 * A scheduled worker may discover a running game while the process is backgrounded, but it is
 * not an exemption from Android's foreground-service start restriction. In that case this class
 * deliberately does not issue the illegal request: the Steam poll remains authoritative for
 * playtime, and the missed fine-grained monitor is recorded for the next foreground visit.
 */
@Singleton
class PresenceServiceStarter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val diagnostics: PresenceDecisionRecorder,
) {
    suspend fun start(trigger: String): PresenceStartOutcome {
        val outcome = if (PresenceService.isRunning) {
            PresenceStartOutcome.ALREADY_RUNNING
        } else if (!isAppVisible()) {
            PresenceStartOutcome.NOT_ATTEMPTED
        } else {
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, PresenceService::class.java),
                )
                PresenceStartOutcome.STARTED
            } catch (_: ForegroundServiceStartNotAllowedException) {
                PresenceStartOutcome.REFUSED
            } catch (_: Exception) {
                PresenceStartOutcome.FAILED
            }
        }

        updateAvailability(outcome.availability)
        diagnostics.record(trigger, outcome.diagnostic)
        return outcome
    }

    private fun isAppVisible(): Boolean =
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    private suspend fun updateAvailability(availability: PresenceMonitoringAvailability) {
        try {
            if (
                availability == PresenceMonitoringAvailability.FOREGROUND_REQUIRED &&
                settings.liveMonitoringAvailability.first() ==
                PresenceMonitoringAvailability.RUNTIME_BUDGET_EXHAUSTED
            ) {
                return
            }
            settings.setLiveMonitoringAvailability(availability)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Diagnostics must not turn a best-effort presence request into a failed sync.
        }
    }
}

/** The observable result of one request to start the live monitor. */
enum class PresenceStartOutcome(
    val diagnostic: PresenceOutcome,
    val availability: PresenceMonitoringAvailability,
) {
    ALREADY_RUNNING(
        diagnostic = PresenceOutcome.MONITORING_STARTED,
        availability = PresenceMonitoringAvailability.AVAILABLE,
    ),
    STARTED(
        diagnostic = PresenceOutcome.MONITORING_STARTED,
        availability = PresenceMonitoringAvailability.AVAILABLE,
    ),
    REFUSED(
        diagnostic = PresenceOutcome.START_REFUSED,
        availability = PresenceMonitoringAvailability.START_REFUSED,
    ),
    FAILED(
        diagnostic = PresenceOutcome.START_FAILED,
        availability = PresenceMonitoringAvailability.START_FAILED,
    ),
    NOT_ATTEMPTED(
        diagnostic = PresenceOutcome.START_NOT_ATTEMPTED,
        availability = PresenceMonitoringAvailability.FOREGROUND_REQUIRED,
    ),
}
