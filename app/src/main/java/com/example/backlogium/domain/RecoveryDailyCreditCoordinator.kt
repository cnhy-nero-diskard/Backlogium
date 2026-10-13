package com.example.backlogium.domain

import com.example.backlogium.data.local.dao.DailyProgressDao
import com.example.backlogium.data.local.entity.DailyProgress
import javax.inject.Inject

/**
 * Applies operation-owned dated-credit deltas to stored daily rows (recover-missed-playtime,
 * design.md §7).
 *
 * The coordinator's contract is the cancelled-sum of the operation's before/after placement graph
 * — it never recomputes a whole date, so a day's unrelated legacy/manual remainder is preserved and
 * a date the operation never mentions is never touched. Every delta is applied exactly once as a
 * signed change inside the caller's raw Room transaction. This coordinator is deliberately not
 * itself a replay guard: durable operation ids, placement revisions and materialization cursors
 * decide whether an operation is new before calling it; replaying a positive delta without that
 * guard would duplicate credit and is therefore a caller/storage-protocol bug.
 *
 * Underflow is **rejected**, never silently zeroed: applying a delta would turn a stored total
 * negative, that is an inconsistent quantity graph and it throws (inside the caller's raw
 * transaction, so nothing is written). Only the normative goal-vs-total clamp is applied. This
 * write is raw playtime only — re-evaluating the affected dates' quests is the caller's next step,
 * under corrective provenance.
 */
class RecoveryDailyCreditCoordinator @Inject constructor(
    private val dailyProgressDao: DailyProgressDao,
) {
    /**
     * Apply [deltas] atomically (the caller supplies the surrounding transaction). Deltas for the
     * same date are summed; no-op entries are skipped. Returns the deltas actually applied, in date
     * order, for the replacement journal.
     */
    suspend fun apply(deltas: List<DailyCreditDelta>): List<DailyCreditDelta> {
        val applied = deltas
            .filterNot { it.isNoOp }
            .groupBy { it.date }
            .toSortedMap()
            .map { (date, dayDeltas) ->
                DailyCreditDelta(
                    date = date,
                    minutesDelta = Math.toIntExact(
                        dayDeltas.fold(0L) { total, item -> Math.addExact(total, item.minutesDelta.toLong()) },
                    ),
                    goalMinutesDelta = Math.toIntExact(
                        dayDeltas.fold(0L) { total, item -> Math.addExact(total, item.goalMinutesDelta.toLong()) },
                    ),
                )
            }
            .filterNot { it.isNoOp }

        applied.forEach { delta ->
            dailyProgressDao.ensureDate(delta.date)
            val stored = dailyProgressDao.getByDate(delta.date) ?: DailyProgress(delta.date)
            val targetMinutes = stored.minutesPlayed.toLong() + delta.minutesDelta
            val targetGoal = stored.goalMinutesPlayed.toLong() + delta.goalMinutesDelta
            require(targetMinutes >= 0L) {
                "recovery correction underflows daily minutes on ${delta.date}: " +
                    "${stored.minutesPlayed} + ${delta.minutesDelta} < 0 — inconsistent quantity graph"
            }
            require(targetGoal >= 0L) {
                "recovery correction underflows goal minutes on ${delta.date}: " +
                    "${stored.goalMinutesPlayed} + ${delta.goalMinutesDelta} < 0 — inconsistent quantity graph"
            }
            require(targetMinutes <= Int.MAX_VALUE) {
                "recovery correction overflows daily minutes on ${delta.date}"
            }
            require(targetGoal <= targetMinutes) {
                "recovery correction would make goal minutes $targetGoal exceed total $targetMinutes on ${delta.date}"
            }
            dailyProgressDao.applyMinutesDelta(delta.date, delta.minutesDelta, delta.goalMinutesDelta)
        }
        return applied
    }

    /**
     * Apply the signed change of one placement-revision move: [before] are the superseded revision's
     * active shares, [after] the new revision's. Hidden shares contribute to neither side, pending
     * rows never appear, and funded timed shares use their frozen credited date.
     */
    suspend fun applyPlacementCorrections(
        before: List<PlacementShare>,
        after: List<PlacementShare>,
    ): List<DailyCreditDelta> = apply(placementShareDeltas(before, after))
}
