package com.example.backlogium.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.backlogium.data.local.entity.RequestCounterTotals
import com.example.backlogium.data.local.entity.RequestTotal
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DiagnosticsDaoTest {
    private lateinit var database: BacklogiumDatabase

    @Before
    fun setUp() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, BacklogiumDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun incrementingUpsertAccumulatesAndWindowQueriesUseInclusiveCutoffs() = runBlocking {
        val dao = database.diagnosticsDao()
        dao.incrementRequestTotal(2_000L, "/route/", "200", ok = true, count = 2)
        dao.incrementRequestTotal(2_000L, "/route/", "200", ok = true, count = 3)
        dao.incrementRequestTotal(2_000L, "/route/", "network", ok = false, count = 4)
        dao.incrementRequestTotal(1_999L, "/route/", "200", ok = true, count = 8)

        assertEquals(
            RequestCounterTotals(ok = 5, failed = 4),
            dao.observeRequestTotals(cutoff = 2_000L).first(),
        )
        assertEquals(
            listOf(RequestTotalRoute("/route/", ok = 5, failed = 4)),
            dao.observeRequestRoutes(cutoff = 2_000L).first().map { RequestTotalRoute(it.route, it.ok, it.failed) },
        )
    }

    @Test
    fun pruneRemovesOnlyBucketsOlderThanTheCutoff() = runBlocking {
        val dao = database.diagnosticsDao()
        dao.incrementRequestTotal(1_999L, "/old/", "200", ok = true, count = 1)
        dao.incrementRequestTotal(2_000L, "/kept/", "200", ok = true, count = 2)

        dao.pruneRequestTotals(cutoff = 2_000L)

        assertEquals(
            RequestCounterTotals(ok = 2, failed = 0),
            dao.observeRequestTotals(cutoff = Long.MIN_VALUE).first(),
        )
    }

    private data class RequestTotalRoute(val route: String, val ok: Long, val failed: Long)
}
