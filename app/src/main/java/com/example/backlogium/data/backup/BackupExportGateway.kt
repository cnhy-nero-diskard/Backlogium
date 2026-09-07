package com.example.backlogium.data.backup

import android.net.Uri

/**
 * Narrow view of [BackupRepository] for the onboarding identity-change export: the one write the
 * flow drives. An interface so the flow can be tested on the JVM without its SAF + WorkManager
 * graph.
 */
interface BackupExportGateway {
    suspend fun exportTo(uri: Uri)
}
