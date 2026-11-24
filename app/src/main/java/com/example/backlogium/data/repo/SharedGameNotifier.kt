package com.example.backlogium.data.repo

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.backlogium.GameNotificationIntent
import com.example.backlogium.GameNotificationTarget
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
fun interface SharedGameNotifier {
    /** @return true when a notification was actually posted. */
    fun notifyAdmitted(account: String, appId: Long, name: String): Boolean
}

@Singleton
class AndroidSharedGameNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) : SharedGameNotifier {

    override fun notifyAdmitted(account: String, appId: Long, name: String): Boolean = runCatching {
        if (!canPostNotifications()) return false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Family-shared games",
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        val launchIntent = GameNotificationIntent.create(
            context, GameNotificationTarget(account, appId), "shared-admission",
        )
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            launchIntent,
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
        postNotification(launchIntent.dataString, notification)
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
    private fun postNotification(tag: String?, notification: Notification) {
        NotificationManagerCompat.from(context).notify(tag, NOTIFICATION_ID, notification)
    }

    private companion object {
        const val CHANNEL_ID = "shared_games"
        const val NOTIFICATION_ID = 4400
    }
}
