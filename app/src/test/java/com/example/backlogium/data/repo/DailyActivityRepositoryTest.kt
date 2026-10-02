package com.example.backlogium.data.repo

import androidx.room.Room
import androidx.room.withTransaction
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.entity.DailyProgress
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.Session
import com.example.backlogium.domain.SystemTimeProvider
import java.time.LocalDate
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DailyActivityRepositoryTest {
    @Test fun localInspectionDoesNotWriteAndRefreshRetainsMarkedCoherentSnapshot() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), BacklogiumDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val time = SystemTimeProvider()
            val date = LocalDate.of(2026, 10, 3)
            val start = date.atStartOfDay(time.zone()).toInstant().toEpochMilli()
            db.gameDao().upsert(Game(1, "Fixture", "", 0, 0, 0))
            val session = Session(appId = 1, startAt = start, minutes = 40, open = false)
            val id = db.sessionDao().insert(session)
            val progress = DailyProgress(date.toString(), 40, questMet = true)
            db.dailyProgressDao().upsert(progress)
            val observed = java.util.concurrent.CopyOnWriteArrayList<com.example.backlogium.domain.DailyActivity>()
            val repository = DailyActivityRepository(db, time)
            val job = launch(Dispatchers.Default) { repository.observeDay(date, "fixture-account").collect { observed.add(it) } }
            withTimeout(5000) { while (observed.isEmpty()) delay(10) }
            assertEquals(listOf(session.copy(id = id)), db.sessionDao().getAll())
            assertEquals(progress, db.dailyProgressDao().getByDate(date.toString()))
            db.withTransaction {
                db.sessionDao().update(session.copy(id = id, minutes = 60))
                db.dailyProgressDao().setMinutes(date.toString(), 60, 0)
            }
            withTimeout(5000) { while (observed.last().recordedMinutes != 60L) delay(10) }
            assertTrue(observed.any { it.updating && it.recordedMinutes == 40L })
            assertTrue(observed.all { it.differenceMinutes == 0L })
            assertEquals(true, db.dailyProgressDao().getByDate(date.toString())?.questMet)
            job.cancelAndJoin()
        } finally { db.close() }
    }
}
