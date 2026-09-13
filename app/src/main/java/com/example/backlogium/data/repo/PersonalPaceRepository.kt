package com.example.backlogium.data.repo

import com.example.backlogium.domain.CurrentDateProvider
import com.example.backlogium.domain.PersonalPace
import com.example.backlogium.domain.PersonalPaceProfile
import com.example.backlogium.domain.PersonalPaceSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shared local Personal Pace stream. Both Home and Collections consume this same derived profile,
 * so a screen cannot quietly use a different lookback, date zone, or confidence interpretation. The
 * live date input also makes the profile reclassify and age its session window at local midnight.
 */
@Singleton
class PersonalPaceRepository @Inject constructor(
    private val sessionRepository: SessionRepository,
    private val currentDate: CurrentDateProvider,
) {
    private val zone = currentDate.zone

    @OptIn(ExperimentalCoroutinesApi::class)
    val profile: Flow<PersonalPaceProfile> = currentDate.currentDate.flatMapLatest { today ->
        val cutoffMillis = today
            .minusDays(PersonalPace.LOOKBACK_DAYS)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()
        sessionRepository.closedSessionsSince(cutoffMillis).map { sessions ->
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
}
