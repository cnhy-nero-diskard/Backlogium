package com.example.backlogium.data.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.backlogium.GameNotificationIntent
import com.example.backlogium.GameNotificationTarget
import com.example.backlogium.MainActivity
import com.example.backlogium.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProgressAlertPlatform @Inject constructor(@ApplicationContext private val context: Context) {
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    fun registerChannels() {
        manager?.createNotificationChannels(ProgressCategory.entries.map {
            NotificationChannel(it.channelId, it.title, NotificationManager.IMPORTANCE_DEFAULT)
                .apply { enableVibration(true) }
        })
    }

    fun permissionGranted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED && NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** null means registration must be retried, never that the player disabled this channel. */
    fun channelAllowed(category: ProgressCategory): Boolean? =
        manager?.getNotificationChannel(category.channelId)?.let { it.importance != NotificationManager.IMPORTANCE_NONE }

    fun prepare(alert: ProgressAlert): android.app.Notification {
        val intent = alert.appId?.let {
            GameNotificationIntent.create(context, GameNotificationTarget(alert.account, it), "progress", alert.eventKey)
        } ?: Intent(context, MainActivity::class.java).setData(
            Uri.Builder().scheme("backlogium").authority("progress").appendPath(alert.eventKey).build(),
        )
        return NotificationCompat.Builder(context, ProgressCategory.valueOf(alert.category).channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(alert.title)
            .setContentText(alert.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(alert.text))
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .build()
    }

    @SuppressLint("MissingPermission")
    fun post(alert: ProgressAlert, notification: android.app.Notification) {
        NotificationManagerCompat.from(context).notify(alert.eventKey, 4600, notification)
    }
}
