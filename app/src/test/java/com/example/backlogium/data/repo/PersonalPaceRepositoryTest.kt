package com.example.backlogium.data.repo

import com.example.backlogium.data.local.dao.SessionDao
import com.example.backlogium.data.local.entity.Session
import com.example.backlogium.domain.CurrentDateProvider
import com.example.backlogium.domain.PersonalPace
import com.example.backlogium.domain.PersonalPaceConfidence
import com.example.backlogium.domain.TimeProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class PersonalPaceRepositoryTest {

    private val zone = ZoneId.of("UTC")

    @Test
    fun localMidnightMakesAThirteenDateProfileReliableOnTheFourteenthCompletedDate() = runTest {
        val initialDate = LocalDate.of(2026, 8, 14)
        val time = virtualClock(this, at(initialDate, 23, 59))
        val sessions = (1..13).map { daysAgo ->
            session(initialDate.minusDays(daysAgo.toLong()), minutes = if (daysAgo <= 6) 60 else 0)
        }
        // This date is excluded initially, then becomes the fourteenth completed date at midnight.
        val allSessions = sessions + session(initialDate, minutes = 60)
        val requestedCutoffs = mutableListOf<Long>()

        val profiles = repository(time, allSessions, requestedCutoffs).profile
            .distinctUntilChanged()
            .take(2)
            .toList()

        assertEquals(listOf(13, 14), profiles.map { it.coveredDates })
        assertEquals(
            listOf(PersonalPaceConfidence.LEARNING, PersonalPaceConfidence.RELIABLE),
            profiles.map { it.confidence },
        )
        assertEquals(
            listOf(
                cutoff(initialDate),
                cutoff(initialDate.plusDays(1)),
            ),
            requestedCutoffs,
        )
    }

    @Test
    fun localMidnightAgesTheOldestActiveObservationOutOfTheLookback() = runTest {
        val initialDate = LocalDate.of(2026, 8, 14)
        val time = virtualClock(this, at(initialDate, 23, 59))
        // Exactly six active dates qualify until the oldest one falls outside the 56-day window.
        val sessions = (51..56).map { daysAgo ->
            session(initialDate.minusDays(daysAgo.toLong()), minutes = 60)
        }
        val requestedCutoffs = mutableListOf<Long>()

        val profiles = repository(time, sessions, requestedCutoffs).profile
            .distinctUntilChanged()
            .take(2)
            .toList()

        assertEquals(PersonalPaceConfidence.RELIABLE, profiles[0].confidence)
        assertEquals(6, profiles[0].activeDates)
        assertEquals(PersonalPaceConfidence.LEARNING, profiles[1].confidence)
        assertEquals(5, profiles[1].activeDates)
        assertEquals(initialDate.minusDays(55), profiles[1].dailyTotals.first().date)
        assertEquals(listOf(cutoff(initialDate), cutoff(initialDate.plusDays(1))), requestedCutoffs)
    }

    private fun repository(
        time: TimeProvider,
        sessions: List<Session>,
        requestedCutoffs: MutableList<Long>,
    ): PersonalPaceRepository {
        val sessionDao = Proxy.newProxyInstance(
            SessionDao::class.java.classLoader,
            arrayOf(SessionDao::class.java),
        ) { _, method, args ->
            when (method.name) {
                "observeClosedSince" -> {
                    val cutoff = args!![0] as Long
                    requestedCutoffs += cutoff
                    flowOf(sessions.filter { it.startAt >= cutoff })
                }
                "observeEarliestSessionStart", "observeEarliestVisibleSessionStart" -> flowOf(null)
                "observeTrackedMinutesByGame", "observeSessionCountsByGame",
                "observeFirstSessionStartByGame", "observeLatestSessionInstantByGame" ->
                    flowOf(emptyList<Any>())
                "toString" -> "SessionDao test double"
                "hashCode" -> 0
                "equals" -> false
                else -> throw IllegalStateException("unexpected SessionDao method: ${method.name}")
            }
        } as SessionDao
        return PersonalPaceRepository(
            sessionRepository = SessionRepository(sessionDao, fakeHiddenGamesRepository()),
            currentDate = CurrentDateProvider(time),
        )
    }

    private fun session(date: LocalDate, minutes: Int): Session {
        val startAt = at(date, 12)
        return Session(
            appId = 1L,
            startAt = startAt,
            endAt = startAt + 1,
            minutes = minutes,
            open = false,
        )
    }

    private fun at(date: LocalDate, hour: Int, minute: Int = 0): Long =
        date.atStartOfDay(zone).plusHours(hour.toLong()).plusMinutes(minute.toLong())
            .toInstant().toEpochMilli()

    private fun cutoff(today: LocalDate): Long = at(today.minusDays(PersonalPace.LOOKBACK_DAYS), 0)

    private fun virtualClock(scope: TestScope, startAt: Long) = object : TimeProvider {
        override fun nowMillis(): Long = startAt + scope.testScheduler.currentTime
        override fun zone(): ZoneId = zone
        override fun today(): LocalDate = Instant.ofEpochMilli(nowMillis()).atZone(zone).toLocalDate()
    }
}
