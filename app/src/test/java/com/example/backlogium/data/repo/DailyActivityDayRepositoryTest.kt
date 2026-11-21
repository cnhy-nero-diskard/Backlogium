package com.example.backlogium.data.repo

import androidx.room.Room
import androidx.room.withTransaction
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.entity.DailyProgress
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.HiddenGame
import com.example.backlogium.data.local.entity.Session
import com.example.backlogium.domain.DailyActivity
import com.example.backlogium.domain.TimeProvider
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DailyActivityDayRepositoryTest {
    private val time = object : TimeProvider {
        override fun zone() = ZoneId.of("Asia/Taipei")
        override fun nowMillis() = 0L
        override fun today() = LocalDate.of(2026, 10, 3)
    }

    @Test fun boundedSnapshotExcludesHiddenAndLifetimeValuesAndKeepsOpenAmounts() = runBlocking {
        val db = database()
        try {
            val date = LocalDate.of(2026, 10, 3)
            val start = date.atStartOfDay(ZoneId.of("Asia/Taipei")).toInstant().toEpochMilli()
            val end = date.plusDays(1).atStartOfDay(ZoneId.of("Asia/Taipei")).toInstant().toEpochMilli()
            db.gameDao().upsert(game(1).copy(backfillMinutes = 500, manualSharedMinutes = 700))
            db.gameDao().upsert(game(2))
            db.sessionDao().insert(Session(appId = 1, startAt = start - 1, endAt = start + 1000, minutes = 80, open = false))
            db.sessionDao().insert(Session(appId = 1, startAt = start, minutes = 12, open = true))
            db.sessionDao().insert(Session(appId = 1, startAt = end, minutes = 90, open = false))
            db.sessionDao().insert(Session(appId = 2, startAt = start, minutes = 50, open = false))
            db.hiddenGameDao().upsertAll(listOf(HiddenGame(2, 0)))
            db.dailyProgressDao().upsert(DailyProgress(date.toString(), 62, questMet = true))
            val repository = DailyActivityRepository(db, time)
            val activity = repository.observeDay(date, "fixture").first()
            assertEquals(1L, activity.games.single().appId)
            assertEquals(12L, activity.recordedMinutes)
            assertEquals(62L, activity.storedMinutes)
            assertTrue(activity.questMet!!)
            assertFalse(repository.detailAvailable(2))
            assertTrue(repository.detailAvailable(1))
            db.gameDao().deleteAll()
            assertFalse(repository.detailAvailable(1))
        } finally { db.close() }
    }

    @Test fun correctionPublishesOnlyCommittedScopesAndSupportsProgressOnlyDay() = runBlocking {
        val db = database()
        try {
            db.gameDao().upsert(game(1))
            val date = LocalDate.of(2026, 10, 3)
            val start = date.atStartOfDay(time.zone()).toInstant().toEpochMilli()
            val id = db.sessionDao().insert(Session(appId = 1, startAt = start, minutes = 40, open = false))
            db.dailyProgressDao().upsert(DailyProgress(date.toString(), 40))
            val repository = DailyActivityRepository(db, time)
            val observed = java.util.concurrent.CopyOnWriteArrayList<DailyActivity>()
            val job = launch(Dispatchers.Default) {
                repository.observeDay(date, "fixture").collect { observed.add(it) }
            }
            withTimeout(5000) { while (observed.isEmpty()) delay(10) }
            db.withTransaction {
                db.sessionDao().update(Session(id, 1, start, minutes = 60, open = false))
                delay(50)
                db.dailyProgressDao().setMinutes(date.toString(), 60, 0)
            }
            withTimeout(5000) { while (observed.last().recordedMinutes != 60L) delay(10) }
            assertTrue(observed.all { it.recordedMinutes == it.storedMinutes })
            job.cancelAndJoin()
            db.sessionDao().deleteAll()
            val activity = repository.observeDay(date, "fixture").first()
            assertTrue(activity.games.isEmpty())
            assertEquals(60L, activity.storedMinutes)
            assertNull(repository.observeDay(date.plusDays(1), "fixture").first().storedMinutes)
        } finally { db.close() }
    }

    private fun database() = Room.inMemoryDatabaseBuilder(
        RuntimeEnvironment.getApplication(), BacklogiumDatabase::class.java,
    ).allowMainThreadQueries().build()
    private fun game(id: Long) = Game(id, "Game $id", "", 0, 0, 0)
}
