package com.example.backlogium.data.backup

import androidx.room.withTransaction
import com.example.backlogium.data.local.BacklogiumDatabase
import javax.inject.Inject
import javax.inject.Singleton

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
}

/** Runs [block] with no transactional boundary — the default for plain-JVM tests using fakes. */
object PassThroughTransactionScope : DatabaseTransactionScope {
    override suspend fun <R> run(block: suspend () -> R): R = block()
}

@Singleton
class RoomDatabaseTransactionScope @Inject constructor(
    private val database: BacklogiumDatabase,
) : DatabaseTransactionScope {
    override suspend fun <R> run(block: suspend () -> R): R = database.withTransaction(block)
}
