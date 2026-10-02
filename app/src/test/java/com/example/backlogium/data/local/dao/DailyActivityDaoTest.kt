package com.example.backlogium.data.local.dao

import androidx.room.Room
import androidx.room.withTransaction
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.entity.*
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
class DailyActivityDaoTest {
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
            val rows = db.dailyActivityDao().observeDay(date.toString(), start, end).first()
            assertEquals(1, rows.size)
            assertEquals(12, rows.single().minutes)
            assertEquals(62L, rows.single().creditedMinutes)
            assertTrue(rows.single().questMet!!)
            assertFalse(db.dailyActivityDao().detailAvailable(2))
            assertTrue(db.dailyActivityDao().detailAvailable(1))
            db.gameDao().deleteAll()
            assertFalse(db.dailyActivityDao().detailAvailable(1))
        } finally { db.close() }
    }

    @Test fun correctionPublishesOnlyCommittedScopesAndSupportsProgressOnlyDay() = runBlocking {
        val db = database()
        try {
            db.gameDao().upsert(game(1))
            val id = db.sessionDao().insert(Session(appId = 1, startAt = 1, minutes = 40, open = false))
            db.dailyProgressDao().upsert(DailyProgress("2026-10-03", 40))
            val observed = java.util.concurrent.CopyOnWriteArrayList<List<DailyActivityRow>>()
            val job = launch(Dispatchers.Default) {
                db.dailyActivityDao().observeDay("2026-10-03", 0, 100).collect { observed.add(it) }
            }
            withTimeout(5000) { while (observed.isEmpty()) delay(10) }
            db.withTransaction {
                db.sessionDao().update(Session(id, 1, 1, minutes = 60, open = false))
                delay(50)
                db.dailyProgressDao().setMinutes("2026-10-03", 60, 0)
            }
            withTimeout(5000) { while (observed.last().first().minutes != 60) delay(10) }
            assertTrue(observed.all { it.single().minutes?.toLong() == it.single().creditedMinutes })
            job.cancelAndJoin()
            db.sessionDao().deleteAll()
            val row = db.dailyActivityDao().observeDay("2026-10-03", 0, 100).first().single()
            assertNull(row.appId)
            assertEquals(60L, row.creditedMinutes)
            assertNull(db.dailyActivityDao().observeDay("2026-10-04", 0, 100).first().single().creditedMinutes)
        } finally { db.close() }
    }

    private fun database() = Room.inMemoryDatabaseBuilder(
        RuntimeEnvironment.getApplication(), BacklogiumDatabase::class.java,
    ).allowMainThreadQueries().build()
    private fun game(id: Long) = Game(id, "Game $id", "", 0, 0, 0)
}
