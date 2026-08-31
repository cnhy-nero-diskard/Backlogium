package com.example.backlogium.work

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.backlogium.MainActivity
import com.example.backlogium.R
import com.example.backlogium.ui.util.UiFormat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds and posts the ongoing "Playing X · 47m" notification while [PresenceService] runs, on
 * its own channel (`IMPORTANCE_LOW`, silent, updated in place).
 *
 * [initial] is always built and returned unconditionally: [android.app.Service.startForeground]
 * requires *a* notification regardless of the runtime permission. [update] gates on the
 * POST_NOTIFICATIONS runtime permission and skips silently without it — the foreground service
 * keeps running either way, just without a visible notification.
 */
@Singleton
class PresenceNotifications @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** The notification the service must post immediately on start. */
    fun initial(): Notification {
        ensureChannel()
        return build(title = "Monitoring Steam", text = "")
    }

    /** Keep the required foreground-service notification visible while waiting for a game. */
    fun monitoring() {
        if (!hasPostPermission()) return
        NotificationManagerCompat.from(context).notify(
            NOTIFICATION_ID,
            build(title = "Monitoring Steam", text = "Checking every 30 seconds"),
        )
    }

    /** Refresh the ongoing notification's game name and elapsed time. */
    fun update(gameName: String, elapsedMinutes: Int) {
        if (!hasPostPermission()) return
        NotificationManagerCompat.from(context).notify(
            NOTIFICATION_ID,
            build(title = "Playing $gameName", text = UiFormat.minutes(elapsedMinutes)),
        )
    }

    /** Called when the service stops — no notification should outlive the session. */
    fun clear() {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun hasPostPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Now playing", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun build(title: String, text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .build()
    }

    companion object {
        const val NOTIFICATION_ID = 4301
        private const val CHANNEL_ID = "presence"
    }
}
