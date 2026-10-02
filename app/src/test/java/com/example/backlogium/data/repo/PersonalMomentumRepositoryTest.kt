package com.example.backlogium.data.repo

import androidx.room.Room
import androidx.room.withTransaction
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.Session
import com.example.backlogium.data.local.entity.HiddenGame
import com.example.backlogium.domain.*
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PersonalMomentumRepositoryTest {
    private val key = MomentumKey("fixture", LocalDate.of(2026, 10, 3), ZoneId.of("UTC"))
    private val weeks = completedWeeks(key.today)
    private fun at(date: LocalDate) = date.atStartOfDay(key.zone).toInstant().toEpochMilli()
    private fun database() = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), BacklogiumDatabase::class.java)
        .allowMainThreadQueries().build()

    @Test fun boundedFinalizedEvidenceExcludesHiddenNonLibraryTodayAndUndatedCreditWithoutWrites() = runBlocking {
        val db = database()
        try {
            db.gameDao().upsert(Game(1, "Visible", "", 999999, 0, 0, backfillMinutes = 999999))
            db.gameDao().upsert(Game(2, "Hidden", "", 0, 0, 0))
            db.hiddenGameDao().upsertAll(listOf(HiddenGame(2, 1)))
            // Simulate an orphan imported by older data: normal Room writes enforce this FK.
            db.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys = OFF")
            for (appId in listOf(2L, 99L)) db.sessionDao().insert(Session(appId = appId,
                startAt = at(weeks.baselineStart.minusDays(50)), minutes = 100, open = false))
            db.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys = ON")
            for ((date, minutes, open) in listOf(Triple(weeks.baselineStart, 40, false),
                Triple(weeks.currentStart, 60, false), Triple(weeks.currentEnd, 60, false),
                Triple(weeks.currentStart, 900, true), Triple(key.today, 900, false))) {
                db.sessionDao().insert(Session(appId = 1, startAt = at(date), minutes = minutes, open = open))
            }
            val before = db.sessionDao().getAll()
            val result = PersonalMomentumRepository(db).read(key)
            assertEquals(120L, result.candidates.single().currentMinutes)
            assertEquals(40L, result.candidates.single().baselineMinutes)
            assertEquals(before, db.sessionDao().getAll())
            assertEquals(999999, db.gameDao().getAll().first { it.appId == 1L }.backfillMinutes)
            // Remove the only boundary-setting eligible row: old hidden/non-library records cannot replace it.
            db.sessionDao().deleteById(before.first { it.appId == 1L && it.minutes == 40 }.id)
            assertEquals(MomentumAvailability.LEARNING, PersonalMomentumRepository(db).read(key).availability)
        } finally { db.close() }
    }
    @Test fun coherentRefreshHandlesCorrectionsFinalizationAndVisibilityWithoutNewIds() = runBlocking {
        val db = database()
        try {
            db.gameDao().upsert(Game(1, "Visible", "", 0, 0, 0))
            val baseline = Session(appId = 1, startAt = at(weeks.baselineStart), minutes = 40, open = false)
            val baselineId = db.sessionDao().insert(baseline)
            val current = Session(appId = 1, startAt = at(weeks.currentStart), minutes = 60, open = false)
            val currentId = db.sessionDao().insert(current)
            db.sessionDao().insert(current.copy(startAt = at(weeks.currentEnd)))
            val open = current.copy(startAt = at(weeks.currentStart.plusDays(1)), minutes = 10, open = true)
            val openId = db.sessionDao().insert(open)
            val repository = PersonalMomentumRepository(db)
            val observed = java.util.concurrent.CopyOnWriteArrayList<MomentumRead>()
            val job = launch(Dispatchers.Default) { observePersonalMomentum(flowOf(key), repository::observe).collect { observed.add(it) } }
            suspend fun awaitResult(predicate: (PersonalMomentum) -> Boolean) {
                withTimeout(5000) { while (observed.lastOrNull()?.result?.let(predicate) != true || observed.last().updating) delay(10) }
            }
            awaitResult { it.candidates.singleOrNull()?.currentMinutes == 120L }
            db.withTransaction {
                db.sessionDao().update(current.copy(id = currentId, minutes = 70))
                db.sessionDao().update(baseline.copy(id = baselineId, minutes = 50))
            }
            awaitResult { it.candidates.singleOrNull()?.currentMinutes == 130L }
            assertTrue(observed.filter { it.result != null }.all { it.result!!.candidates.single().additionalMinutes == 80L })
            assertTrue(observed.any { it.updating && it.result?.candidates?.singleOrNull()?.currentMinutes == 120L })
            db.sessionDao().update(open.copy(id = openId, open = false))
            awaitResult { it.candidates.singleOrNull()?.currentMinutes == 140L }
            db.sessionDao().update(current.copy(id = currentId, startAt = at(weeks.baselineStart), minutes = 70))
            awaitResult { it.candidates.isEmpty() }
            db.hiddenGameDao().upsertAll(listOf(HiddenGame(1, 1)))
            awaitResult { it.availability == MomentumAvailability.EMPTY_LIBRARY }
            assertEquals(4, db.sessionDao().getAll().size)
            job.cancelAndJoin()
        } finally { db.close() }
    }
}
