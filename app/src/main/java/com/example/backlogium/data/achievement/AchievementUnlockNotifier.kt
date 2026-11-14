package com.example.backlogium.data.achievement

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
import com.example.backlogium.MainActivity
import com.example.backlogium.R
import com.example.backlogium.domain.AchievementUnlockGroup
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

const val OPEN_ACHIEVEMENT_GAME = "achievement_app_id"
const val OPEN_ACHIEVEMENT_ACCOUNT = "achievement_account"

interface AchievementUnlockNotifier {
    enum class Result { POSTED, SUPPRESSED, RETRY }
    fun post(group: AchievementUnlockGroup): Result
}

@Singleton
class AndroidAchievementUnlockNotifier @Inject constructor(@ApplicationContext private val context: Context) : AchievementUnlockNotifier {
    @SuppressLint("MissingPermission")
    override fun post(group: AchievementUnlockGroup): AchievementUnlockNotifier.Result {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ||
            !NotificationManagerCompat.from(context).areNotificationsEnabled()) return AchievementUnlockNotifier.Result.SUPPRESSED
        return try {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Achievement unlocks", NotificationManager.IMPORTANCE_DEFAULT))
            if (manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return AchievementUnlockNotifier.Result.SUPPRESSED
            val intent = Intent(context, MainActivity::class.java)
                .setData(Uri.parse("backlogium://achievement/${group.eventId}"))
                .putExtra(OPEN_ACHIEVEMENT_GAME, group.appId).putExtra(OPEN_ACHIEVEMENT_ACCOUNT, group.account)
            val contentIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val text = unlockGroupText(group)
            val notification = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(group.gameName).setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setOnlyAlertOnce(true).setAutoCancel(true).setContentIntent(contentIntent).build()
            // The full event identity in the tag avoids collisions from truncating a hash to Int.
            NotificationManagerCompat.from(context).notify("achievement:${group.eventId}", 1, notification)
            AchievementUnlockNotifier.Result.POSTED
        } catch (_: SecurityException) { AchievementUnlockNotifier.Result.SUPPRESSED }
        catch (_: Exception) { AchievementUnlockNotifier.Result.RETRY }
    }

    companion object { const val CHANNEL = "achievement_unlocks" }
}

fun unlockGroupText(group: AchievementUnlockGroup): String =
    if (group.achievements.size == 1) "Unlocked ${group.achievements.single().name}"
    else "${group.achievements.size} achievements unlocked: ${group.achievements.joinToString { it.name }}"
