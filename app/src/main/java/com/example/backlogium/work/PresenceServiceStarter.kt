package com.example.backlogium.work

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
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
 * Starts [PresenceService] for known foreground entry points and records lifecycle outcomes.
 *
 * The scheduled worker must use [recordNotAttempted] instead of this class's start-capable method.
 * Keeping those operations explicit prevents a delayed process-lifecycle signal from becoming the
 * legality boundary for a background service start.
 */
@Singleton
class PresenceServiceStarter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val diagnostics: PresenceDecisionRecorder,
) {
    /** Start from a foreground interaction such as Settings or the app-foreground observer. */
    suspend fun startFromForeground(trigger: String): PresenceStartOutcome {
        val outcome = if (PresenceService.isRunning) {
            PresenceStartOutcome.ALREADY_RUNNING
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

        recordOutcome(trigger, outcome)
        return outcome
    }

    /**
     * Resolve a background worker's decision without issuing a service-start request. If the
     * service is already active, preserve that available state instead of reporting a refusal.
     */
    suspend fun recordNotAttempted(trigger: String): PresenceStartOutcome {
        val outcome = backgroundPresenceOutcome(PresenceService.isRunning)
        recordOutcome(trigger, outcome)
        return outcome
    }

    private suspend fun recordOutcome(trigger: String, outcome: PresenceStartOutcome) {
        updateAvailability(outcome.availability)
        diagnostics.record(trigger, outcome.diagnostic)
    }

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

/** Resolve a background poll's decision without ever making it a service-start operation. */
internal fun backgroundPresenceOutcome(serviceRunning: Boolean): PresenceStartOutcome =
    if (serviceRunning) {
        PresenceStartOutcome.ALREADY_RUNNING
    } else {
        PresenceStartOutcome.NOT_ATTEMPTED
    }

/** The observable result of one request to start the live monitor. */
enum class PresenceStartOutcome(
    val diagnostic: PresenceOutcome,
    val availability: PresenceMonitoringAvailability,
) {
    ALREADY_RUNNING(
        diagnostic = PresenceOutcome.MONITORING_ALREADY_RUNNING,
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
