package com.example.backlogium.data.repo

import com.example.backlogium.domain.CurrentDateProvider
import com.example.backlogium.domain.PersonalPace
import com.example.backlogium.domain.PersonalPaceProfile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shared local Personal Pace stream. Both Home and Collections consume this same derived profile,
 * so a screen cannot quietly use a different lookback, date zone, or confidence interpretation. The
 * live date input also makes the profile reclassify and age its session window at local midnight.
 *
 * Since recover-missed-playtime (design.md Decision 9) the feed combines **eligible completed-date
 * totals** (`SessionRepository.closedTrainingTotalsBetween`): closed sessions contribute their
 * unlinked legacy remainder on their current-zone date, funded timed shares of closed sessions (and
 * detached shares) train on their frozen credited dates, dated allocations add their frozen dates,
 * and the bounded recipe tail joins the window. Open sessions contribute nothing, current dates and
 * pending date-unknown quantity never train, and every unresolved multi-day estimate window is
 * masked via [PersonalPace.derive]'s `unknownDates` (bounded to the 56-day horizon) so estimated
 * dates are never synthesized into observed zero-play days and cannot satisfy the
 * 14-covered/six-active confidence floors.
 */
@Singleton
class PersonalPaceRepository @Inject constructor(
    private val sessionRepository: SessionRepository,
    private val currentDate: CurrentDateProvider,
) {
    private val zone = currentDate.zone

    @OptIn(ExperimentalCoroutinesApi::class)
    val profile: Flow<PersonalPaceProfile> = currentDate.currentDate.flatMapLatest { today ->
        combine(
            sessionRepository.closedTrainingTotalsBetween(
                zone = zone,
                firstDateInclusive = today.minusDays(PersonalPace.LOOKBACK_DAYS),
                lastDateInclusive = today.minusDays(1),
            ),
            sessionRepository.unresolvedRecoveryWindows(),
            sessionRepository.openSessionDatesBetween(
                zone = zone,
                firstDateInclusive = today.minusDays(PersonalPace.LOOKBACK_DAYS),
                lastDateInclusive = today.minusDays(1),
            ),
        ) { totals, estimateWindows, openDates ->
            val unknownDates = estimateWindows
                .flatMap { it.unknownDatesBetween(today.minusDays(PersonalPace.LOOKBACK_DAYS), today.minusDays(1)) }
                .toSet() + openDates
            PersonalPace.derive(
                totals = totals,
                today = today,
                unknownDates = unknownDates,
            )
        }
    }
}
