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
}
