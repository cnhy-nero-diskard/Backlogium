package com.example.backlogium.data.repo

import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.domain.DailyActivity
import com.example.backlogium.domain.DailyActivityEvidence
import com.example.backlogium.domain.TimeProvider
import com.example.backlogium.domain.dailyActivity
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.transformLatest
import androidx.room.withTransaction
import java.time.Instant

/** Local reads only: no sync, clock extension, quest evaluation, or lifetime allocation. */
@Singleton
class DailyActivityRepository @Inject constructor(
    private val database: BacklogiumDatabase,
    private val time: TimeProvider,
) {
    private val dao = database.dailyActivityDao()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observeDay(date: LocalDate, accountId: String): Flow<DailyActivity> {
        var previous: DailyActivity? = null
        return database.invalidationTracker.createFlow("sessions", "games", "hidden_games", "daily_progress")
            .conflate().transformLatest {
                previous?.let { emit(it.copy(updating = true)) }
                val rows = dao.readDay(
                    date.toString(), date.atStartOfDay(time.zone()).toInstant().toEpochMilli(),
                    date.plusDays(1).atStartOfDay(time.zone()).toInstant().toEpochMilli(),
                )
                val activity = dailyActivity(
                    date, accountId,
                    rows.mapNotNull { row ->
                        row.appId?.let {
                            DailyActivityEvidence(it, row.name, requireNotNull(row.minutes), row.detailAppId != null)
                        }
                    }, rows.firstOrNull()?.creditedMinutes, rows.firstOrNull()?.questMet,
                )
                previous = activity
                emit(activity)
            }.distinctUntilChanged()
    }

    suspend fun detailAvailable(appId: Long): Boolean = dao.detailAvailable(appId)

    /** One bounded transaction for the whole History window, never a query for each rendered day. */
    suspend fun readWindow(start: LocalDate, endInclusive: LocalDate, accountId: String): DailyActivityWindow =
        database.withTransaction {
            val zone = time.zone()
            val from = start.atStartOfDay(zone).toInstant().toEpochMilli()
            val until = endInclusive.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val hidden = database.hiddenGameDao().hiddenAppIds().toSet()
            val games = database.gameDao().getAll().filterNot { it.appId in hidden }.map {
                LibraryGame(it.appId, it.name, it.iconUrl, playtimeForever = it.playtimeForever, isGoal = it.isGoal)
            }
            val sessions = dao.readVisibleBetween(from, until).map { it.toDomain() }
            val progress = dao.readProgressBetween(start.toString(), endInclusive.toString()).map {
                DayProgress(it.date, it.minutesPlayed, it.goalMinutesPlayed, it.questMet)
            }
            val achievements = dao.readUnlocksBetween(from, until).map {
                AchievementUnlockSummary(it.appId, it.iconUrl, it.unlockedAt)
            }
            val earliest = dao.earliestVisibleStart()
            DailyActivityWindow(
                start, endInclusive, accountId, sessions, games, progress, achievements,
                dao.hasProgressBefore(start.toString()) ||
                    earliest?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() < start } == true,
            )
        }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observeWindow(start: LocalDate, endInclusive: LocalDate, accountId: String): Flow<DailyActivityWindow> {
        var previous: DailyActivityWindow? = null
        return database.invalidationTracker.createFlow("sessions", "games", "hidden_games", "daily_progress", "achievements")
            .conflate().transformLatest {
                previous?.let { emit(it.copy(updating = true)) }
                val result = readWindow(start, endInclusive, accountId)
                previous = result
                emit(result)
            }
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
    val updating: Boolean = false,
)
