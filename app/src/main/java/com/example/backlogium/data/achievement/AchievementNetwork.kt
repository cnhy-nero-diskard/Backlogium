package com.example.backlogium.data.achievement

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import retrofit2.HttpException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

@Singleton
open class AchievementNetwork @Inject constructor(@ApplicationContext private val context: Context) {
    open fun isOnline(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}

internal fun achievementRetryAfter(error: Exception, now: Long): Long? {
    val http = error as? HttpException ?: return null
    if (http.code() != 429 && http.code() != 503) return null
    val header = http.response()?.headers()?.get("Retry-After")
    val seconds = header?.toLongOrNull()
    return if (seconds != null) now + seconds.coerceIn(0, 365L * 24 * 60 * 60) * 1_000
    else header?.let { runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull() }
        ?: (now + 60_000)
}

internal fun achievementRetryDelay(failures: Int): Long =
    (60_000L * (1L shl failures.coerceIn(0, 4))).coerceAtMost(15 * 60_000L)
