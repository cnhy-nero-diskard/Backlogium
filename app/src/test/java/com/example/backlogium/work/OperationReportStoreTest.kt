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
        val first = store.begin(OperationChannel.ACHIEVEMENTS, workId, generation = 4, retry = false)
        store.save(OperationChannel.ACHIEVEMENTS, first.copy(
            progress = first.progress.copy(
                status = OperationStatus.RETRYING,
                phase = OperationPhase.ACHIEVEMENTS,
                counts = OperationCounts(2, 5, failed = 1),
            ),
        ))

        val retry = store.begin(OperationChannel.ACHIEVEMENTS, workId, generation = 4, retry = true)
        assertEquals(first.progress.id, retry.progress.id)
        assertEquals(first.startedAt, retry.startedAt)

        store.save(OperationChannel.ACHIEVEMENTS, retry.copy(
            progress = retry.progress.copy(status = OperationStatus.PARTIAL),
        ))
        val nextCycle = store.begin(OperationChannel.ACHIEVEMENTS, workId, generation = 4, retry = false)
        assertNotEquals(retry.progress.id, nextCycle.progress.id)
        assertTrue(nextCycle.startedAt > retry.startedAt)

        val nextAccount = store.begin(OperationChannel.ACHIEVEMENTS, workId, generation = 5, retry = false)
        store.save(OperationChannel.ACHIEVEMENTS, retry.copy(
            progress = retry.progress.copy(status = OperationStatus.FAILED),
        ))
        val reattached = OperationReportStore(RuntimeEnvironment.getApplication())
            .observe(OperationChannel.ACHIEVEMENTS).first()

        assertEquals(5L, reattached?.generation)
        assertEquals(nextAccount.progress.id, reattached?.progress?.id)
    }

    @Test fun `a late report from overlapping work cannot replace the newer execution`() = runTest {
        store.clearForTest()
        val older = store.begin(OperationChannel.STEAM, UUID.randomUUID().toString(), 4, retry = false)
        val newer = store.begin(OperationChannel.STEAM, UUID.randomUUID().toString(), 4, retry = false)

        store.save(OperationChannel.STEAM, older.copy(
            progress = older.progress.copy(status = OperationStatus.FAILED),
        ))

        val current = store.observe(OperationChannel.STEAM).first()
        assertEquals(newer.workId, current?.workId)
        assertEquals(newer.progress.id, current?.progress?.id)
        assertEquals(OperationStatus.RUNNING, current?.progress?.status)
    }
}
