package com.example.backlogium.work

import com.example.backlogium.domain.OperationCounts
import com.example.backlogium.domain.OperationPhase
import com.example.backlogium.domain.OperationProgress
import com.example.backlogium.domain.OperationStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class OperationReportStoreTest {
    private val store = OperationReportStore(RuntimeEnvironment.getApplication())

    @Test fun `periodic retries reuse one cycle identity but a later cycle and account get new identities`() = runTest {
        store.clearForTest()
        val workId = UUID.randomUUID().toString()
        val first = store.begin(OperationChannel.ACHIEVEMENTS, OperationSource.PERIODIC, workId, generation = 4, retry = false)
        store.save(OperationChannel.ACHIEVEMENTS, first.copy(
            progress = first.progress.copy(
                status = OperationStatus.RETRYING,
                phase = OperationPhase.ACHIEVEMENTS,
                counts = OperationCounts(2, 5, failed = 1),
            ),
        ))

        val retry = store.begin(OperationChannel.ACHIEVEMENTS, OperationSource.PERIODIC, workId, generation = 4, retry = true)
        assertEquals(first.progress.id, retry.progress.id)
        assertEquals(first.startedAt, retry.startedAt)

        store.save(OperationChannel.ACHIEVEMENTS, retry.copy(
            progress = retry.progress.copy(status = OperationStatus.PARTIAL),
        ))
        val nextCycle = store.begin(OperationChannel.ACHIEVEMENTS, OperationSource.PERIODIC, workId, generation = 4, retry = false)
        assertNotEquals(retry.progress.id, nextCycle.progress.id)
        assertTrue(nextCycle.startedAt > retry.startedAt)

        val nextAccount = store.begin(OperationChannel.ACHIEVEMENTS, OperationSource.PERIODIC, workId, generation = 5, retry = false)
        store.save(OperationChannel.ACHIEVEMENTS, retry.copy(
            progress = retry.progress.copy(status = OperationStatus.FAILED),
        ))
        val reattached = OperationReportStore(RuntimeEnvironment.getApplication())
            .observe(OperationChannel.ACHIEVEMENTS).first()

        assertEquals(5L, reattached.single().generation)
        assertEquals(nextAccount.progress.id, reattached.single().progress.id)
    }

    @Test fun `manual and periodic overlaps retain independent reports`() = runTest {
        store.clearForTest()
        val periodic = store.begin(
            OperationChannel.STEAM, OperationSource.PERIODIC, UUID.randomUUID().toString(), 4, retry = false,
        )
        val manual = store.begin(
            OperationChannel.STEAM, OperationSource.MANUAL, UUID.randomUUID().toString(), 4, retry = false,
        )
        assertTrue("newer execution order stays deterministic within one clock tick",
            manual.startedAt > periodic.startedAt)

        store.save(OperationChannel.STEAM, manual.copy(
            progress = manual.progress.copy(
                status = OperationStatus.SUCCEEDED,
                counts = OperationCounts(5, 5),
            ),
        ))
        store.save(OperationChannel.STEAM, periodic.copy(
            progress = periodic.progress.copy(
                status = OperationStatus.RETRYING,
                counts = OperationCounts(2, 8, failed = 1),
            ),
        ))

        val reports = store.observe(OperationChannel.STEAM).first().associateBy { it.source }
        assertEquals(periodic.workId, reports[OperationSource.PERIODIC]?.workId)
        assertEquals(periodic.progress.id, reports[OperationSource.PERIODIC]?.progress?.id)
        assertEquals(OperationStatus.RETRYING, reports[OperationSource.PERIODIC]?.progress?.status)
        assertEquals(2, reports[OperationSource.PERIODIC]?.progress?.counts?.processed)
        assertEquals(manual.workId, reports[OperationSource.MANUAL]?.workId)
        assertEquals(OperationStatus.SUCCEEDED, reports[OperationSource.MANUAL]?.progress?.status)
    }

    @Test fun `a late report from replaced work cannot replace the newer report in its slot`() = runTest {
        store.clearForTest()
        val older = store.begin(OperationChannel.STEAM, OperationSource.PERIODIC,
            UUID.randomUUID().toString(), 4, retry = false)
        val newer = store.begin(OperationChannel.STEAM, OperationSource.PERIODIC,
            UUID.randomUUID().toString(), 4, retry = false)

        store.save(OperationChannel.STEAM, older.copy(
            progress = older.progress.copy(status = OperationStatus.FAILED),
        ))

        val current = store.observe(OperationChannel.STEAM).first().single()
        assertEquals(newer.workId, current.workId)
        assertEquals(newer.progress.id, current.progress.id)
        assertEquals(OperationStatus.RUNNING, current.progress.status)
    }

    @Test fun `an interrupted snapshot cannot replace a newer execution in the same slot`() = runTest {
        store.clearForTest()
        val workId = UUID.randomUUID().toString()
        val interrupted = store.begin(OperationChannel.STEAM, OperationSource.PERIODIC,
            workId, generation = 4, retry = false)
        val replacement = store.begin(OperationChannel.STEAM, OperationSource.PERIODIC,
            workId, generation = 4, retry = false)

        store.saveInterrupted(
            OperationChannel.STEAM,
            interrupted.copy(progress = interrupted.progress.copy(
                phase = OperationPhase.ACHIEVEMENTS,
                counts = OperationCounts(2, 5),
            )),
        ) { true }

        val current = store.observe(OperationChannel.STEAM).first().single()
        assertEquals(replacement.progress.id, current.progress.id)
        assertEquals(OperationPhase.PREPARING, current.progress.phase)
        assertEquals(null, current.progress.counts)
    }
}
