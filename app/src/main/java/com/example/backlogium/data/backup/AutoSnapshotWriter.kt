package com.example.backlogium.data.backup

/**
 * The narrow backup side-effect the Steam sync worker may schedule after a successful poll.
 *
 * The worker only calls [writeAutoSnapshotIfDue] behind its own best-effort guard, so this gateway
 * keeps [SteamSyncWorker] constructible in host tests without the full [BackupRepository] object
 * graph (which needs the Android-Keystore-backed credential store). The implementation and its
 * protocol are unchanged: [BackupRepository] is the only real binding.
 */
interface AutoSnapshotWriter {
    /** Write an automatic snapshot if enabled and due; a no-op otherwise. */
    suspend fun writeAutoSnapshotIfDue()
}