package com.example.backlogium.data.notifications

import androidx.room.withTransaction
import com.example.backlogium.data.credentials.AccountChangeMarkerStore
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.repo.CredentialsProvider
import com.example.backlogium.data.repo.DailyActivityRepository
import com.example.backlogium.domain.TimeProvider
import com.example.backlogium.work.SteamSyncCoordinator
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

internal fun previousRecapDate(now: Long, zone: ZoneId): LocalDate =
    Instant.ofEpochMilli(now).atZone(zone).toLocalDate().minusDays(1)

internal fun nextRecapDelay(now: Long, zone: ZoneId): Long {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return (today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - now).coerceAtLeast(1_000L)
}

internal fun recapText(date: LocalDate, minutes: Long, games: Int, unlocks: Int, missingCoverage: Boolean): String =
    "$date: $minutes recorded minutes across $games games; $unlocks unlocks with confirmed dates. " +
        "Play timing may include estimates. Unlocks without supported dates are excluded. " +
        if (missingCoverage) "Coverage is incomplete; this is a partial record." else "Recorded evidence only; unobserved play is not included."

@Singleton
class PreviousDayRecap @Inject constructor(
    private val database: BacklogiumDatabase,
    private val activity: DailyActivityRepository,
    private val alerts: ProgressAlerts,
    private val time: TimeProvider,
    private val credentials: CredentialsProvider,
    private val marker: AccountChangeMarkerStore,
    private val coordinator: SteamSyncCoordinator,
) {
    suspend fun recordCurrentDay() = coordinator.withLock {
        marker.withAccountState {
            if (marker.pendingSteamId() != null) return@withAccountState
            val account = credentials.currentCredentials()?.steamId ?: return@withAccountState
            if (database.playerProfileDao().get()?.steamId != account) return@withAccountState
            val generation = marker.generation()
            val zone = time.zone()
            val date = previousRecapDate(time.nowMillis(), zone)
            val key = "$account:$generation:${ProgressCategory.RECAP.name}:$date"
            if (database.progressAlertDao().get(key) != null) return@withAccountState
            database.withTransaction {
                val day = activity.readDay(date, account, zone)
                val unlocks = database.dailyActivityDao().readUnlocksBetween(
                    date.atStartOfDay(zone).toInstant().toEpochMilli(),
                    date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
                )
                val games = day.games.filter { it.detailAvailable && it.minutes > 0 }
                database.progressAlertDao().insert(alerts.prepare(ProgressCategory.RECAP, account, generation,
                    date.toString(), title = "Your recap for $date",
                    text = recapText(date, games.sumOf { it.minutes }, games.size, unlocks.size, day.storedMinutes == null || day.differenceMinutes != 0L)))
            }
        }
    }
}
