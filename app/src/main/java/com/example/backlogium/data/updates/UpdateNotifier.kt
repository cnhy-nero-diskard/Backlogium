package com.example.backlogium.data.updates

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
import com.example.backlogium.BuildConfig
import com.example.backlogium.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

const val OPEN_UPDATE_EXTRA = "com.example.backlogium.OPEN_UPDATE"

interface UpdateNotifier {
    fun notify(update: AvailableUpdate): Boolean

    /** Posts a user-driven PackageInstaller confirmation action when the app is backgrounded. */
    fun notifyInstallConfirmation(confirmation: Intent): Boolean = false

    /** Posts a tap-to-open notification when a successful install could not relaunch the app directly. */
    fun notifyInstallComplete(versionName: String): Boolean = false
}

@Singleton
class AndroidUpdateNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) : UpdateNotifier {
    override fun notify(update: AvailableUpdate): Boolean {
        if (BuildConfig.DEBUG) return false
        if (!canPostNotifications()) return false

        return runCatching {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return false
            ensureChannel(manager)
            val launchIntent = context.packageManager
                .getLaunchIntentForPackage(context.packageName)
                ?.putExtra(OPEN_UPDATE_EXTRA, true)
                ?: return false
            val contentIntent = PendingIntent.getActivity(
                context,
                NOTIFICATION_ID,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notes = update.releaseNotes.ifBlank { "Open Settings to review this update." }
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Backlogium ${update.versionName} is available")
                .setContentText(update.releaseName)
                .setStyle(NotificationCompat.BigTextStyle().bigText(notes))
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .build()
            postNotification(NOTIFICATION_ID, notification)
            true
        }.getOrDefault(false)
    }

    override fun notifyInstallConfirmation(confirmation: Intent): Boolean {
        if (BuildConfig.DEBUG) return false
        if (!canPostNotifications()) return false

        return runCatching {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return false
            ensureChannel(manager)
            val contentIntent = PendingIntent.getActivity(
                context,
                INSTALL_CONFIRMATION_REQUEST_CODE,
                confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Confirm Backlogium update")
                .setContentText("Tap to continue installing the downloaded update.")
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .build()
            postNotification(INSTALL_CONFIRMATION_NOTIFICATION_ID, notification)
            true
        }.getOrDefault(false)
    }

    override fun notifyInstallComplete(versionName: String): Boolean {
        if (BuildConfig.DEBUG) return false
        if (!canPostNotifications()) return false

        return runCatching {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return false
            ensureChannel(manager)
            val launchIntent = context.packageManager
                .getLaunchIntentForPackage(context.packageName)
                ?: return false
            val contentIntent = PendingIntent.getActivity(
                context,
                INSTALL_COMPLETE_REQUEST_CODE,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Backlogium updated to $versionName")
                .setContentText("Tap to open.")
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .build()
            postNotification(INSTALL_COMPLETE_NOTIFICATION_ID, notification)
            true
        }.getOrDefault(false)
    }

    private fun canPostNotifications(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED &&
            NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            context.getSystemService(NotificationManager::class.java)
                ?.getNotificationChannel(CHANNEL_ID)
                ?.importance != NotificationManager.IMPORTANCE_NONE

    @SuppressLint("MissingPermission")
    private fun postNotification(notificationId: Int, notification: Notification) {
        NotificationManagerCompat.from(context).notify(notificationId, notification)
    }

    private fun ensureChannel(manager: NotificationManager) {
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "App updates",
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }

    private companion object {
        const val CHANNEL_ID = "app_updates"
        const val NOTIFICATION_ID = 4202
        const val INSTALL_CONFIRMATION_NOTIFICATION_ID = 4204
        const val INSTALL_CONFIRMATION_REQUEST_CODE = 4205
        const val INSTALL_COMPLETE_NOTIFICATION_ID = 4206
        const val INSTALL_COMPLETE_REQUEST_CODE = 4207
    }
}
