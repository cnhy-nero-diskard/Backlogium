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

/** Local reads only: no sync, clock extension, quest evaluation, or lifetime allocation. */
@Singleton
class DailyActivityRepository @Inject constructor(
    database: BacklogiumDatabase,
    private val time: TimeProvider,
) {
    private val dao = database.dailyActivityDao()

    fun observeDay(date: LocalDate, accountId: String): Flow<DailyActivity> = dao.observeDay(
        date.toString(), date.atStartOfDay(time.zone()).toInstant().toEpochMilli(),
        date.plusDays(1).atStartOfDay(time.zone()).toInstant().toEpochMilli(),
    ).map { rows ->
        dailyActivity(
            date, accountId,
            rows.mapNotNull { row ->
                row.appId?.let {
                    DailyActivityEvidence(it, row.name, requireNotNull(row.minutes), row.detailAppId != null)
                }
            }, rows.firstOrNull()?.creditedMinutes, rows.firstOrNull()?.questMet,
        )
    }.distinctUntilChanged()

    suspend fun detailAvailable(appId: Long): Boolean = dao.detailAvailable(appId)
}
