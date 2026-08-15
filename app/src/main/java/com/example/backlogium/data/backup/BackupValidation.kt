package com.example.backlogium.data.backup

import java.time.LocalDate
import java.time.ZoneOffset

/** One preflight problem: which record type and index failed, and why. */
data class BackupValidationProblem(
    val recordType: String,
    val index: Int,
    val detail: String,
)

sealed interface BackupValidationResult {
    data class Valid(val file: BackupFile) : BackupValidationResult
    data class Invalid(val problems: List<BackupValidationProblem>) : BackupValidationResult
}

/**
 * Full structural/semantic preflight over a parsed [BackupFile], with no database access
 * (design.md decision 1). Every category the merge used to discover mid-write — malformed
 * dates, implausible timestamps, broken relationships, malformed ids, out-of-range values,
 * duplicate natural keys — is checked here first, so an invalid file is rejected before any
 * write happens, with a message naming what failed and where (tasks.md 2.1-2.3).
 *
 * References are checked within the file only: a self-produced export always includes every
 * owned game and every collection it has members for, so an achievement or member pointing
 * outside the file's own `games`/`collections` lists is exactly the kind of broken relationship
 * this preflight exists to catch before the merge's foreign keys reject it mid-write.
 */
object BackupValidator {

    /**
     * Plausibility window for any date/timestamp in a backup. Steam itself launched in 2003 and
     * this app cannot hold records predating the account it syncs, so anything earlier is
     * corruption rather than history. The upper bound is deliberately generous — it exists to
     * stop absurd values, not to police clock skew — and is a fixed constant rather than "today"
     * so validation stays pure and needs no [com.example.backlogium.domain.TimeProvider].
     */
    internal val EARLIEST_PLAUSIBLE_DATE: LocalDate = LocalDate.of(2003, 1, 1)
    internal val LATEST_PLAUSIBLE_DATE: LocalDate = LocalDate.of(2100, 1, 1)

    private val EARLIEST_PLAUSIBLE_MILLIS: Long =
        EARLIEST_PLAUSIBLE_DATE.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    private val LATEST_PLAUSIBLE_MILLIS: Long =
        LATEST_PLAUSIBLE_DATE.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    fun validate(file: BackupFile): BackupValidationResult {
        val problems = mutableListOf<BackupValidationProblem>()
        val gameAppIds = file.games.mapTo(mutableSetOf()) { it.appId }
        val collectionIds = file.collections.mapTo(mutableSetOf()) { it.id }

        file.games.forEachIndexed { index, game ->
            if (game.appId <= 0L) {
                problems += BackupValidationProblem("game", index, "malformed appId ${game.appId}")
            }
        }

        file.sessions.forEachIndexed { index, session ->
            val startAt = session.startAt.toEpochMilliOrNull()
            when {
                startAt == null ->
                    problems += BackupValidationProblem("session", index, "unparseable startAt '${session.startAt}'")
                startAt < 0L ->
                    problems += BackupValidationProblem("session", index, "implausible negative startAt")
                startAt < EARLIEST_PLAUSIBLE_MILLIS || startAt > LATEST_PLAUSIBLE_MILLIS ->
                    problems += BackupValidationProblem(
                        "session", index,
                        "startAt '${session.startAt}' outside the supported range " +
                            "$EARLIEST_PLAUSIBLE_DATE..$LATEST_PLAUSIBLE_DATE",
                    )
                session.endAt != null -> {
                    val endAt = session.endAt.toEpochMilliOrNull()
                    if (endAt == null) {
                        problems += BackupValidationProblem("session", index, "unparseable endAt '${session.endAt}'")
                    } else if (endAt < startAt) {
                        problems += BackupValidationProblem("session", index, "endAt precedes startAt")
                    }
                }
            }
        }

        file.dailyProgress.forEachIndexed { index, day ->
            val parsed = runCatching { LocalDate.parse(day.date) }.getOrNull()
            when {
                parsed == null ->
                    problems += BackupValidationProblem("dailyProgress", index, "unparseable date '${day.date}'")
                // Parseability alone is not enough: GamificationUpdater.compute() expands the
                // calendar from the earliest stored day through today, so a syntactically valid
                // but absurd date (year 0001) would commit and then drive a recompute over
                // hundreds of thousands of days — which the pending-recompute recovery would
                // faithfully retry on every subsequent launch.
                parsed < EARLIEST_PLAUSIBLE_DATE || parsed > LATEST_PLAUSIBLE_DATE ->
                    problems += BackupValidationProblem(
                        "dailyProgress", index,
                        "date '${day.date}' outside the supported range " +
                            "$EARLIEST_PLAUSIBLE_DATE..$LATEST_PLAUSIBLE_DATE",
                    )
            }
        }

        file.achievements.forEachIndexed { index, achievement ->
            if (achievement.appId !in gameAppIds) {
                problems += BackupValidationProblem(
                    "achievement", index,
                    "references absent game ${achievement.appId}",
                )
            }
            achievement.unlockedAt?.let { unlockedAt ->
                if (unlockedAt.toEpochMilliOrNull() == null) {
                    problems += BackupValidationProblem(
                        "achievement", index,
                        "unparseable unlockedAt '$unlockedAt'",
                    )
                }
            }
            achievement.snapshotPercent?.let { percent ->
                if (percent < 0.0 || percent > 100.0) {
                    problems += BackupValidationProblem(
                        "achievement", index,
                        "snapshotPercent $percent out of range",
                    )
                }
            }
        }

        file.collectionMembers.forEachIndexed { index, member ->
            if (member.collectionId !in collectionIds) {
                problems += BackupValidationProblem(
                    "collectionMember", index,
                    "references absent collection ${member.collectionId}",
                )
            }
        }

        file.collectionMembers
            .groupBy { it.collectionId }
            .forEach { (collectionId, members) ->
                members.groupBy { it.appId }
                    .filterValues { it.size > 1 }
                    .forEach { (appId, duplicates) ->
                        val index = file.collectionMembers.indexOf(duplicates.first())
                        problems += BackupValidationProblem(
                            "collectionMember", index,
                            "duplicate appId $appId within collection $collectionId",
                        )
                    }
            }

        return if (problems.isEmpty()) {
            BackupValidationResult.Valid(file)
        } else {
            BackupValidationResult.Invalid(problems)
        }
    }

    private fun String.toEpochMilliOrNull(): Long? = runCatching { iso8601ToEpochMilli() }.getOrNull()
}
