package com.example.backlogium.data.repo

import androidx.room.withTransaction
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.domain.*
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.transformLatest

/** Local bounded evidence only; no lifetime session detail, progress writes or remote dependencies. */
@Singleton
class PersonalMomentumRepository @Inject constructor(private val database: BacklogiumDatabase) {
    suspend fun read(key: MomentumKey): PersonalMomentum = database.withTransaction {
        val dao = database.dailyActivityDao()
        val weeks = completedWeeks(key.today)
        val games = dao.momentumGames()
        val records = dao.momentumRecords(weeks.startMillis(key.zone), weeks.endExclusiveMillis(key.zone))
        val earliest = dao.earliestMomentumStart()?.let { Instant.ofEpochMilli(it).atZone(key.zone).toLocalDate() }
        personalMomentum(key, games, momentumDays(records, weeks, key.zone), earliest)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observe(key: MomentumKey): Flow<PersonalMomentum?> =
        database.invalidationTracker.createFlow("sessions", "games", "hidden_games").conflate().transformLatest {
            emit(null)
            emit(read(key))
        }

    suspend fun detailAvailable(appId: Long): Boolean = database.dailyActivityDao().detailAvailable(appId)
}
