package com.example.backlogium.domain

/**
 * The per-app generation counter that says which post-play schedule is the live one.
 *
 * An interface (mirroring [ProgressMarksStore] and [TimeProvider]) so the coordinator that reads
 * and advances it — the correctness guard for superseded schedules — is testable on the JVM,
 * without a DataStore or an Android context.
 */
interface PostPlayGenerations {

    /** Advance the app's generation and return the new value. Atomic against concurrent callers. */
    suspend fun advance(appId: Long): Long

    /** The app's live generation, or 0 when no schedule has ever started for it. */
    suspend fun current(appId: Long): Long
}
