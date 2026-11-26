package com.example.backlogium.work

import androidx.room.Room
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.backlogium.data.credentials.AccountChangeMarkerStore
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.domain.TimeProvider
import com.example.backlogium.test.SettingsDataStoreRule
import java.time.Instant
import java.time.ZoneId
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ProgressAlertSchedulerTest {
    @get:Rule val settings = SettingsDataStoreRule()
    @Test fun zoneChangeReplacesTheDeadlineWithCurrentLocalMidnight() {
        val context = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        val db = Room.inMemoryDatabaseBuilder(context, BacklogiumDatabase::class.java).allowMainThreadQueries().build()
        var zone = ZoneId.of("UTC")
        val time = object : TimeProvider {
            override fun nowMillis() = Instant.parse("2026-11-26T10:00:00Z").toEpochMilli()
            override fun zone() = zone
            override fun today() = Instant.ofEpochMilli(nowMillis()).atZone(zone).toLocalDate()
        }
        try {
            val scheduler = ProgressAlertScheduler(context, db, settings.create(), AccountChangeMarkerStore(context), time)
            fun pending() = WorkManager.getInstance(context).getWorkInfosForUniqueWork("progress-alert-next-day").get()
                .single { it.state == WorkInfo.State.ENQUEUED }
            fun delay(info: WorkInfo) = WorkManagerImpl.getInstance()!!.workDatabase.workSpecDao().getWorkSpec(info.id.toString())!!.initialDelay
            scheduler.scheduleNextRecap()
            val first = pending()
            assertEquals(14 * 60 * 60 * 1000L, delay(first))
            zone = ZoneId.of("Asia/Manila")
            scheduler.scheduleNextRecap()
            val second = pending()
            assertNotEquals(first.id, second.id)
            assertEquals(6 * 60 * 60 * 1000L, delay(second))
        } finally {
            db.close()
            WorkManagerTestInitHelper.closeWorkDatabase()
        }
    }
}
