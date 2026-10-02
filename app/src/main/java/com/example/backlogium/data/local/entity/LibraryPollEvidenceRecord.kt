package com.example.backlogium.data.local.entity

import androidx.room.Entity

/** The durable outcome kind of one admitted library-poll operation. */
enum class LibraryPollOutcomeKind {
    /** The accepted owned-library response committed inside the raw library transaction. */
    COMMITTED,

    /** The poll deliberately did not run or commit, with an attributable [LibraryPollRefusal]. */
    NOT_PERFORMED,

    /** The attempt failed; a consumer may offer recovery. */
    FAILED,
}

/**
 * One attributable library-poll operation outcome, durably keyed by the exact admitted work id and
 * the account it ran for (stabilize-first-run-setup).
 *
 * A [COMMITTED] row is written **inside the same Room transaction** that commits the accepted
 * owned-library response — including an explicitly confirmed empty library — so a crash after that
 * transaction (and before any worker output) leaves the committed outcome recoverable from Room,
 * while an interrupted initial commit rolls the row back with the library data. [NOT_PERFORMED] and
 * [FAILED] rows carry the reason that prevented a commit and are written outside the raw
 * transaction, guarded by the account admission barrier.
 *
 * The composite primary key lets one periodic work identity carry separate outcomes for separate
 * accounts across an account change: a result is only ever projected onto a request carrying the
 * same work identity **and** the same account.
 */
@Entity(
    tableName = "library_poll_evidence",
    primaryKeys = ["workIdentity", "accountSteamId"],
)
data class LibraryPollEvidenceRecord(
    /** The exact admitted work UUID (never a reusable unique work name). */
    val workIdentity: String,
    /** The Steam account the operation ran for. */
    val accountSteamId: String,
    /** One of [LibraryPollOutcomeKind] names; a malformed value projects to pending/unknown. */
    val outcome: String,
    /** Accepted owned-game count for [COMMITTED]; `-1` when the outcome carries no count. */
    val gameCount: Int,
    /** Commit `lastSyncAt` for [COMMITTED]; `0` otherwise. */
    val lastSyncAt: Long,
    /** One of [com.example.backlogium.data.repo.LibraryPollRefusal] names for [NOT_PERFORMED]. */
    val refusal: String?,
    /** Failure explanation for [FAILED]; null otherwise. */
    val reason: String?,
    /** When this outcome was recorded (epoch millis). */
    val recordedAt: Long,
)