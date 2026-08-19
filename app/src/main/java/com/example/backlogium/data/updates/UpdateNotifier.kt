package com.example.backlogium.data.updates

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
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

@Singleton
class UpdateNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun notify(update: AvailableUpdate): Boolean {
        if (BuildConfig.DEBUG) return false
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }

        return runCatching {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return false
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "App updates",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
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
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            true
        }.getOrDefault(false)
    }

    private companion object {
        const val CHANNEL_ID = "app_updates"
        const val NOTIFICATION_ID = 4202
    }
}
