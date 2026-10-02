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
            val windows = java.util.concurrent.CopyOnWriteArrayList<DailyActivityWindow>()
            val windowJob = launch(Dispatchers.Default) {
                repository.observeWindow(date.minusDays(29), date, "fixture-account").collect { windows.add(it) }
            }
            withTimeout(5000) { while (observed.isEmpty() || windows.isEmpty()) delay(10) }
            assertEquals(listOf(session.copy(id = id)), db.sessionDao().getAll())
            assertEquals(progress, db.dailyProgressDao().getByDate(date.toString()))
            db.withTransaction {
                db.sessionDao().update(session.copy(id = id, minutes = 60))
                db.dailyProgressDao().setMinutes(date.toString(), 60, 0)
            }
            withTimeout(5000) { while (observed.last().recordedMinutes != 60L) delay(10) }
            withTimeout(5000) { while (windows.last().sessions.sumOf { it.minutes } != 60) delay(10) }
            assertTrue(windows.any { it.updating && it.sessions.sumOf { row -> row.minutes } == 40 })
            assertTrue(windows.all { it.sessions.sumOf { row -> row.minutes } == it.progress.single().minutesPlayed })
            assertTrue(observed.any { it.updating && it.recordedMinutes == 40L })
            assertTrue(observed.all { it.differenceMinutes == 0L })
            assertEquals(true, db.dailyProgressDao().getByDate(date.toString())?.questMet)
            job.cancelAndJoin()
            windowJob.cancelAndJoin()
        } finally { db.close() }
    }
    @Test fun wholeWindowIsBoundedHiddenAwareAndReadOnly() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), BacklogiumDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val time = SystemTimeProvider()
            val date = LocalDate.of(2026, 10, 3)
            fun at(day: LocalDate) = day.atStartOfDay(time.zone()).toInstant().toEpochMilli()
            db.gameDao().upsert(Game(1, "Visible", "", 0, 0, 0))
            db.gameDao().upsert(Game(2, "Hidden", "", 0, 0, 0))
            db.hiddenGameDao().upsertAll(listOf(com.example.backlogium.data.local.entity.HiddenGame(2, 1)))
            for ((appId, start) in listOf(1L to at(date), 2L to at(date), 1L to at(date.plusDays(1)), 1L to at(date.minusDays(31)))) {
                db.sessionDao().insert(Session(appId = appId, startAt = start, minutes = 40, open = false))
            }
            val progress = DailyProgress(date.minusDays(3).toString(), 60, questMet = true)
            db.dailyProgressDao().upsert(progress)
            val before = db.sessionDao().getAll()
            val snapshot = DailyActivityRepository(db, time).readWindow(date.minusDays(29), date, "account")
            assertEquals(listOf(1L), snapshot.sessions.map { it.appId })
            assertEquals(listOf(1L), snapshot.games.map { it.appId })
            assertEquals(listOf(progress.date), snapshot.progress.map { it.date })
            assertTrue(snapshot.hasOlder)
            assertEquals(before, db.sessionDao().getAll())
            assertEquals(progress, db.dailyProgressDao().getByDate(progress.date))
        } finally { db.close() }
    }

}
