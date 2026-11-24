package com.example.backlogium

import android.content.Context
import android.content.Intent
import android.net.Uri

internal data class GameNotificationTarget(val account: String, val appId: Long)

internal object GameNotificationIntent {
    // Keep the keys used by achievement notifications already posted by released builds.
    private const val ACCOUNT = "achievement_account"
    private const val APP_ID = "achievement_app_id"

    fun create(
        context: Context,
        target: GameNotificationTarget,
        source: String,
        eventId: String? = null,
    ): Intent {
        // PendingIntent identity includes data, but ignores extras.
        val identity = Uri.Builder().scheme("backlogium").authority("game-notification")
            .appendPath(source).appendPath(target.account).appendPath(target.appId.toString())
        eventId?.let { identity.appendPath(it) }
        return Intent(context, MainActivity::class.java)
            .setData(identity.build())
            .putExtra(ACCOUNT, target.account)
            .putExtra(APP_ID, target.appId)
    }

    fun read(intent: Intent): GameNotificationTarget? = runCatching {
        val account = intent.getStringExtra(ACCOUNT)
        val appId = intent.getLongExtra(APP_ID, 0L)
        if (account.isNullOrBlank() || appId <= 0L) null else GameNotificationTarget(account, appId)
    }.getOrNull()
}
