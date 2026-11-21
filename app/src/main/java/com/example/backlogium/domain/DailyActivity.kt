package com.example.backlogium.domain

import java.text.Collator
import java.time.LocalDate

/** Recorded evidence and earned credit are separate scopes; neither repairs the other. */
data class DailyActivity(
    val date: LocalDate,
    val accountId: String,
    val games: List<DailyActivityGame>,
    val storedMinutes: Long?,
    val questMet: Boolean?,
    val updating: Boolean = false,
    val storedFocusMinutes: Long? = null,
) {
    val recordedMinutes: Long = games.sumOf { it.minutes }
    val differenceMinutes: Long? = storedMinutes?.minus(recordedMinutes)
}

data class DailyActivityGame(
    val appId: Long,
    val name: String?,
    val minutes: Long,
    val detailAvailable: Boolean,
    val allocations: List<CreditedAllocation> = emptyList(),
)

data class DailyActivityEvidence(
    val appId: Long,
    val name: String?,
    val minutes: Long,
    val detailAvailable: Boolean,
)

fun dailyActivity(
    date: LocalDate,
    accountId: String,
    evidence: List<DailyActivityEvidence>,
    storedMinutes: Long?,
    questMet: Boolean?,
    storedFocusMinutes: Long? = null,
    allocations: List<CreditedAllocation> = emptyList(),
): DailyActivity {
    val collator = Collator.getInstance()
    val allocationsByGame = allocations.groupBy { it.appId }
    val games = evidence.groupBy { it.appId }.map { (appId, activity) ->
        DailyActivityGame(
            appId, activity.first().name?.takeIf { it.isNotBlank() },
            activity.sumOf { it.minutes }, activity.first().detailAvailable,
            allocationsByGame[appId].orEmpty(),
        )
    }.sortedWith { a, b ->
        compareValues(b.minutes, a.minutes).takeIf { it != 0 }
            ?: collator.compare(a.name ?: "App ${a.appId}", b.name ?: "App ${b.appId}")
                .takeIf { it != 0 }
            ?: compareValues(a.appId, b.appId)
    }
    return DailyActivity(date, accountId, games, storedMinutes, questMet, storedFocusMinutes = storedFocusMinutes)
}
