package com.example.backlogium.domain

import java.time.Instant
import java.time.ZoneId

/** Minutes credited to one local calendar date by a set of session actions. */
data class DailyProgressCredit(
    val minutesPlayed: Int,
    val goalMinutesPlayed: Int,
)

/**
 * Attribute only newly observed session minutes to each session's start date. A session remains
 * atomic across midnight; [SessionDiffer.SessionAction.addedMinutes] is the delta for an Extend,
 * not the session's accumulated total.
 *
 * Shared by both session mechanisms — playtime diffing and presence derivation — because a derived
 * session must reach the daily quest by exactly the path a diffed one does. Lives in `domain/` for
 * that reason: it was previously private to the sync worker, which is no longer its only caller.
 *
 * [hiddenAppIds] are dropped: a hidden game contributes nothing to the day's quest from the point
 * it is hidden onward (add-hidden-games). Its *sessions* are still recorded by the caller — hiding
 * destroys nothing — they simply stop counting toward daily progress. Already-recorded days are
 * never revisited: a day the player met their quest remains met.
 */
fun attributeDailyProgress(
    actions: List<SessionDiffer.SessionAction>,
    goalAppIds: Set<Long>,
    zone: ZoneId,
    hiddenAppIds: Set<Long> = emptySet(),
): Map<String, DailyProgressCredit> = actions
    .asSequence()
    .filter { it.addedMinutes > 0 && it.appId !in hiddenAppIds }
    .groupBy { action ->
        Instant.ofEpochMilli(action.startAt).atZone(zone).toLocalDate().toString()
    }
    .mapValues { (_, dayActions) ->
        DailyProgressCredit(
            minutesPlayed = dayActions.sumOf { it.addedMinutes },
            goalMinutesPlayed = dayActions
                .filter { it.appId in goalAppIds }
                .sumOf { it.addedMinutes },
        )
    }
