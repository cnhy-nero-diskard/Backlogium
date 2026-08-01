package com.example.backlogium.work

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Starts [PresenceService]. A thin wrapper so the two detection paths — [SteamSyncWorker]'s
 * periodic poll, and the app-foreground re-check in
 * [com.example.backlogium.BacklogiumApp] — don't each construct the service [Intent] themselves.
 *
 * Safe to call while the service is already running: Android re-delivers to the same instance
 * (`onStartCommand`) rather than creating another, so this never stacks instances.
 */
@Singleton
class PresenceServiceStarter @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun start() {
        ContextCompat.startForegroundService(context, Intent(context, PresenceService::class.java))
    }
}
