package com.example.backlogium.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.example.backlogium.data.local.entity.LibraryPollEvidenceRecord
import kotlinx.coroutines.flow.Flow

/**
 * Durable, attributable library-poll operation outcomes keyed by exact work id + account
 * (stabilize-first-run-setup).
 *
 * Latest outcome wins per (work, account): a periodic worker and a re-admitted manual poll both
 * record successive attempts under the same identity, so the newest attempt's outcome is what a
 * consumer projects. Two accounts never share a row, so an old account's outcome can never be
 * projected onto a new account's request.
 */
@Dao
interface LibraryPollEvidenceDao {

    /** The latest recorded outcome for one admitted work/account pair. */
    @Query(
        "SELECT * FROM library_poll_evidence " +
            "WHERE workIdentity = :workIdentity AND accountSteamId = :accountSteamId",
    )
    suspend fun getFor(workIdentity: String, accountSteamId: String): LibraryPollEvidenceRecord?

    /** Observable latest outcome for one admitted work/account pair. */
    @Query(
        "SELECT * FROM library_poll_evidence " +
            "WHERE workIdentity = :workIdentity AND accountSteamId = :accountSteamId",
    )
    fun observeFor(workIdentity: String, accountSteamId: String): Flow<LibraryPollEvidenceRecord?>

    /** Every recorded outcome for one account, newest first, for investigation/diagnostics. */
    @Query(
        "SELECT * FROM library_poll_evidence " +
            "WHERE accountSteamId = :accountSteamId ORDER BY recordedAt DESC",
    )
    suspend fun listForAccount(accountSteamId: String): List<LibraryPollEvidenceRecord>

    @Upsert
    suspend fun upsert(row: LibraryPollEvidenceRecord)

    /**
     * Bounded retention: delete the account's older terminal outcomes while always keeping the
     * [currentWorkId] row, every [protectedWorkIds] association, and the newest [retainedCount]
     * other outcomes (ordered by recordedAt). The keep-set excludes current/protected before
     * taking the newest N, exactly as the recorder documents ("current + protected + newest N").
     * Mirrors `DiagnosticsDao.pruneRuns`' LIMIT-subquery shape.
     *
     * The protected snapshot (setup-observed stages) is captured outside Room by the caller; an
     * empty [protectedWorkIds] must pass a non-empty sentinel so Room never emits `NOT IN ()`.
     *
     * @return the number of rows pruned.
     */
    @Query(
        "DELETE FROM library_poll_evidence " +
            "WHERE accountSteamId = :accountSteamId " +
            "AND workIdentity != :currentWorkId " +
            "AND workIdentity NOT IN (:protectedWorkIds) " +
            "AND workIdentity NOT IN (" +
            "SELECT workIdentity FROM library_poll_evidence " +
            "WHERE accountSteamId = :accountSteamId " +
            "AND workIdentity != :currentWorkId " +
            "AND workIdentity NOT IN (:protectedWorkIds) " +
            "ORDER BY recordedAt DESC LIMIT :retainedCount)",
    )
    suspend fun pruneOlderThanRetained(
        accountSteamId: String,
        currentWorkId: String,
        protectedWorkIds: List<String>,
        retainedCount: Int,
    ): Int

    /**
     * Account reset discards every attributable outcome of the previous account's polls. New
     * outcomes are account-scoped records; the mapper refuses to project them across accounts, and
     * a purge keeps the store from accumulating retired accounts' records.
     */
    @Query("DELETE FROM library_poll_evidence")
    suspend fun deleteAll()
}