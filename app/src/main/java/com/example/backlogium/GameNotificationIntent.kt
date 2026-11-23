package com.example.backlogium

import android.content.Context
import android.content.Intent
import android.net.Uri

internal data class GameNotificationTarget(val account: String, val appId: Long)

internal object GameNotificationIntent {
    private const val ACTION = "com.example.backlogium.OPEN_NOTIFICATION_GAME"
    private const val ACCOUNT = "notification_game_account"
    private const val APP_ID = "notification_game_app_id"
    private const val LEGACY_ACCOUNT = "achievement_account"
    private const val LEGACY_APP_ID = "achievement_app_id"

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
            .setAction(ACTION)
            .setData(identity.build())
            .putExtra(ACCOUNT, target.account)
            .putExtra(APP_ID, target.appId)
    }

    fun read(intent: Intent): GameNotificationTarget? = runCatching {
        val legacy = intent.action != ACTION
        val account = intent.getStringExtra(if (legacy) LEGACY_ACCOUNT else ACCOUNT)
        val appId = intent.getLongExtra(if (legacy) LEGACY_APP_ID else APP_ID, 0L)
        if (account.isNullOrBlank() || appId <= 0L) null else GameNotificationTarget(account, appId)
    }.getOrNull()
}
