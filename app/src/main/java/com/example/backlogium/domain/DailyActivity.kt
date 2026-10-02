package com.example.backlogium.domain

import java.text.Collator
import java.time.LocalDate

/** Recorded evidence and earned credit are separate scopes; neither repairs the other. */
data class DailyActivity(
    val date: LocalDate,
    val accountId: String,
    val games: List<DailyActivityGame>,
    val creditedMinutes: Long?,
    val questMet: Boolean?,
) {
    val recordedMinutes: Long = games.sumOf { it.minutes }
    val differenceMinutes: Long? = creditedMinutes?.minus(recordedMinutes)
}

data class DailyActivityGame(
    val appId: Long,
    val name: String?,
    val minutes: Long,
    val detailAvailable: Boolean,
)

data class DailyActivityEvidence(
    val appId: Long,
    val name: String?,
    val minutes: Int,
    val detailAvailable: Boolean,
)

fun dailyActivity(
    date: LocalDate,
    accountId: String,
    evidence: List<DailyActivityEvidence>,
    creditedMinutes: Long?,
    questMet: Boolean?,
): DailyActivity {
    val collator = Collator.getInstance()
    val games = evidence.groupBy { it.appId }.map { (appId, sessions) ->
        DailyActivityGame(
            appId, sessions.first().name?.takeIf { it.isNotBlank() },
            sessions.sumOf { it.minutes.toLong() }, sessions.first().detailAvailable,
        )
    }.sortedWith { a, b ->
        compareValues(b.minutes, a.minutes).takeIf { it != 0 }
            ?: collator.compare(a.name ?: "App ${a.appId}", b.name ?: "App ${b.appId}")
                .takeIf { it != 0 }
            ?: compareValues(a.appId, b.appId)
    }
    return DailyActivity(date, accountId, games, creditedMinutes, questMet)
}
