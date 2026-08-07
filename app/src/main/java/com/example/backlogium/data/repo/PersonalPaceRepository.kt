package com.example.backlogium.data.repo

import com.example.backlogium.domain.PersonalPace
import com.example.backlogium.domain.PersonalPaceProfile
import com.example.backlogium.domain.PersonalPaceSession
import com.example.backlogium.domain.TimeProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shared local Personal Pace stream. Both Home and Collections consume this same derived profile,
 * so a screen cannot quietly use a different lookback, date zone, or confidence interpretation.
 */
@Singleton
class PersonalPaceRepository @Inject constructor(
    private val sessionRepository: SessionRepository,
    private val time: TimeProvider,
) {
    private val today = time.today()
    private val zone = time.zone()
    private val cutoffMillis = today
        .minusDays(PersonalPace.LOOKBACK_DAYS)
        .atStartOfDay(zone)
        .toInstant()
        .toEpochMilli()

    val profile: Flow<PersonalPaceProfile> = sessionRepository
        .closedSessionsSince(cutoffMillis)
        .map { sessions ->
            PersonalPace.derive(
                sessions = sessions.map { session ->
                    PersonalPaceSession(
                        startAtMillis = session.startAt,
                        minutes = session.minutes,
                        open = session.open,
                    )
                },
                today = today,
                zone = zone,
            )
        }
}
