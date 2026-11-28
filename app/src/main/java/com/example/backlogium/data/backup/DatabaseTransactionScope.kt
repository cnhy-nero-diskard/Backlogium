package com.example.backlogium.data.backup

import androidx.room.withTransaction
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.entity.DeadlineRevisionBatch
import com.example.backlogium.domain.SystemTimeProvider
import com.example.backlogium.domain.TimeProvider
import javax.inject.Inject
import javax.inject.Singleton
import java.util.UUID

/**
 * A single-commit boundary around a block of DAO calls, backed by [BacklogiumDatabase.withTransaction]
 * in production. Used by [BackupMergeEngine] (one commit for the whole raw-data merge, design.md
 * decision 2) and [BackupExportMapper] (one consistent read view for the whole export, decision 4).
 *
 * Kept as a seam rather than injecting [BacklogiumDatabase] directly into either class, so both
 * stay constructible against fake DAOs in a plain JVM test; real atomicity/consistency is
 * exercised against the real database in an instrumented/Robolectric test instead.
 */
interface DatabaseTransactionScope {
    suspend fun <R> run(block: suspend () -> R): R

    /** One commit whose deadline evidence revisions need a shared cutoff boundary. */
    suspend fun <R> runDeadlineEvidence(block: suspend () -> R): R = run(block)
}

/** Runs [block] with no transactional boundary — the default for plain-JVM tests using fakes. */
object PassThroughTransactionScope : DatabaseTransactionScope {
    override suspend fun <R> run(block: suspend () -> R): R = block()
}

@Singleton
class RoomDatabaseTransactionScope @Inject constructor(
    private val database: BacklogiumDatabase,
    private val time: TimeProvider = SystemTimeProvider(),
) : DatabaseTransactionScope {
    override suspend fun <R> run(block: suspend () -> R): R = database.withTransaction(block)

    override suspend fun <R> runDeadlineEvidence(block: suspend () -> R): R = database.withTransaction {
        val dao = database.deadlineArchiveDao()
        val active = dao.activeBatch()
        if (active == null) {
            dao.beginBatch(DeadlineRevisionBatch(batchId = UUID.randomUUID().toString(), startedAt = time.nowMillis()))
        } else {
            dao.nestBatch()
        }
        try {
            block()
        } finally {
            dao.leaveBatch()
            dao.activeBatch()?.takeIf { it.depth == 0 }?.let { batch ->
                dao.completeBatch(batch.batchId, time.nowMillis())
                dao.clearBatch()
            }
        }
    }
}
