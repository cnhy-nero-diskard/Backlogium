package com.example.backlogium.data.repo

import android.Manifest
import android.annotation.SuppressLint
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
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Announces a newly admitted family-shared game. Admission is automatic, so it must never be
 * silent: the notification is the player's only cue that the app started tracking something on its
 * own, and their route to removing it if the automatic answer was wrong.
 *
 * An interface so admission stays testable without Android, mirroring
 * [com.example.backlogium.data.updates.UpdateNotifier].
 */
interface SharedGameNotifier {
    /** @return true when a notification was actually posted. */
    fun notifyAdmitted(appId: Long, name: String): Boolean
}

@Singleton
class AndroidSharedGameNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) : SharedGameNotifier {

    override fun notifyAdmitted(appId: Long, name: String): Boolean = runCatching {
        if (!canPostNotifications()) return false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Family-shared games",
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        val contentIntent = PendingIntent.getActivity(
            context,
            // Distinct per game, so a second admission does not silently replace the first's
            // intent while both notifications are still on screen.
            appId.toInt(),
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = "Played through Family Sharing. Tracked time is what Backlogium observes."
        val notification: Notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Now tracking $name")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        // Per-game id: admitting a second game must not overwrite the first announcement.
        postNotification(NOTIFICATION_ID_BASE + appId.toInt(), notification)
        true
    }.getOrDefault(false)

    private fun canPostNotifications(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * The permission is checked in [canPostNotifications] before this is reached, which lint cannot
     * follow across the call. Suppressed at the one call site rather than baselined, mirroring
     * [com.example.backlogium.data.updates.AndroidUpdateNotifier].
     */
    @SuppressLint("MissingPermission")
    private fun postNotification(notificationId: Int, notification: Notification) {
        NotificationManagerCompat.from(context).notify(notificationId, notification)
    }

    private companion object {
        const val CHANNEL_ID = "shared_games"
        const val NOTIFICATION_ID_BASE = 4400
    }
}
