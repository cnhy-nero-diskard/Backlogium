package com.example.backlogium.data.repo

import androidx.room.withTransaction
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.domain.DailyActivity
import com.example.backlogium.domain.DailyActivityEvidence
import com.example.backlogium.domain.PlaytimeActivity
import com.example.backlogium.domain.PlaytimeReadProjector.sessionDate
import com.example.backlogium.domain.PlaytimeReadProjector.toCreditedAllocation
import com.example.backlogium.domain.TimeProvider
import com.example.backlogium.domain.dailyActivity
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformLatest

/** Local reads of canonical dated activity and stored quest progress. */
@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class DailyActivityRepository @Inject constructor(
    private val database: BacklogiumDatabase,
    private val time: TimeProvider,
) {
    private val dao = database.dailyActivityDao()
    private val sessions = database.sessionDao()

    fun observeDay(
        date: LocalDate,
        accountId: String,
        zone: ZoneId = time.zone(),
    ): Flow<DailyActivity> =
        observeSnapshot(markUpdating = { it.copy(updating = true) }) {
            readDaySnapshot(date, accountId, zone)
        }

    suspend fun readDay(date: LocalDate, accountId: String, zone: ZoneId = time.zone()): DailyActivity =
        database.withTransaction { readDaySnapshot(date, accountId, zone) }

    private suspend fun readDaySnapshot(date: LocalDate, accountId: String, zone: ZoneId): DailyActivity {
        val activities = sessions.readCanonicalDatedActivity(zone, date, date.plusDays(1))
        val games = readGames(activities.map { it.appId }).associateBy { it.appId }
        val progress = database.dailyProgressDao().getByDate(date.toString())
        val evidence = buildList {
            for (activity in activities) {
                val minutes = when (activity) {
                    is PlaytimeActivity.TimestampedSession -> {
                        val unlinked = activity.unlinkedMinutes.takeIf {
                            sessionDate(activity.startAt, zone) == date
                        } ?: 0L
                        unlinked + activity.fundedShares.filter { it.creditedDate == date }.sumOf { it.minutes }
                    }
                    is PlaytimeActivity.DatedAllocation -> activity.minutes
                    is PlaytimeActivity.PendingDateUnknown -> continue
                }
                val game = games[activity.appId]
                add(DailyActivityEvidence(activity.appId, game?.name, minutes, game != null))
            }
        }
        return dailyActivity(
            date, accountId, evidence, progress?.minutesPlayed?.toLong(), progress?.questMet,
            storedFocusMinutes = progress?.goalMinutesPlayed?.toLong(),
            allocations = activities.filterIsInstance<PlaytimeActivity.DatedAllocation>()
                .map { it.toCreditedAllocation() },
        )
    }

    suspend fun detailAvailable(appId: Long): Boolean = dao.detailAvailable(appId)

    /** Read the entire loaded History window in one transaction, including its paging floor. */
    suspend fun readWindow(start: LocalDate, endInclusive: LocalDate, accountId: String): DailyActivityWindow =
        database.withTransaction { readWindowSnapshot(start, endInclusive, accountId, time.zone()) }

    private suspend fun readWindowSnapshot(
        start: LocalDate,
        endInclusive: LocalDate,
        accountId: String,
        zone: ZoneId,
    ): DailyActivityWindow {
        val endExclusive = endInclusive.plusDays(1)
        val from = start.atStartOfDay(zone).toInstant().toEpochMilli()
        val until = endExclusive.atStartOfDay(zone).toInstant().toEpochMilli()
        val rawSessions = dao.readVisibleBetween(from, until).map { it.toDomain() }
        val activities = sessions.readCanonicalDatedActivity(zone, start, endExclusive)
        val games = readGames(activities.map { it.appId } + rawSessions.map { it.appId })
            .map { LibraryGame(it.appId, it.name, it.iconUrl, playtimeForever = it.playtimeForever, isGoal = it.isGoal) }
        val progress = dao.readProgressBetween(start.toString(), endInclusive.toString()).map {
            DayProgress(it.date, it.minutesPlayed, it.goalMinutesPlayed, it.questMet)
        }
        val achievements = dao.readUnlocksBetween(from, until).map {
            AchievementUnlockSummary(it.appId, it.iconUrl, it.unlockedAt)
        }
        return DailyActivityWindow(
            start, endInclusive, accountId, rawSessions, games, progress, achievements,
            hasOlder = dao.hasProgressBefore(start.toString()) ||
                sessions.readEarliestCanonicalActivityDate(zone)?.let { it < start } == true,
            activities = activities,
            zone = zone,
        )
    }

    fun observeWindow(
        start: LocalDate,
        endInclusive: LocalDate,
        accountId: String,
        zone: ZoneId = time.zone(),
    ): Flow<DailyActivityWindow> =
        observeSnapshot("achievements", markUpdating = { it.copy(updating = true) }) {
            readWindowSnapshot(start, endInclusive, accountId, zone)
        }

    private suspend fun readGames(appIds: List<Long>) =
        appIds.distinct().chunked(900).flatMap { dao.readGames(it) }

    private fun <T : Any> observeSnapshot(
        vararg additionalTables: String,
        markUpdating: (T) -> T,
        read: suspend () -> T,
    ): Flow<T> = flow {
        var previous: T? = null
        emitAll(database.invalidationTracker.createFlow(
            "sessions", "games", "hidden_games", "daily_progress", "player_profile", "playtime_contributions",
            "recovery_timed_placements", "recovery_unknown_allocations", "recovery_allocation_recipes",
            *additionalTables,
        ).conflate().transformLatest {
            previous?.let { emit(markUpdating(it)) }
            val result = database.withTransaction { read() }
            previous = result
            emit(result)
        }.distinctUntilChanged())
    }
}

data class DailyActivityWindow(
    val start: LocalDate,
    val endInclusive: LocalDate,
    val accountId: String,
    val sessions: List<PlaySession>,
    val games: List<LibraryGame>,
    val progress: List<DayProgress>,
    val achievements: List<AchievementUnlockSummary>,
    val hasOlder: Boolean,
    val activities: List<PlaytimeActivity>,
    val zone: ZoneId,
    val updating: Boolean = false,
)
