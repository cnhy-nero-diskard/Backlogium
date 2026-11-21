package com.example.backlogium.data.local.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.example.backlogium.data.local.entity.DailyPlaytimeSnapshot
import com.example.backlogium.data.local.entity.GameRecoveryState
import com.example.backlogium.data.local.entity.LocalTimingEvidence
import com.example.backlogium.data.local.entity.PlaytimeContribution
import com.example.backlogium.data.local.entity.PlaytimeRecoveryJournal
import com.example.backlogium.data.local.entity.RecoveryClockStates
import com.example.backlogium.data.local.entity.RecoverySnapshotClassifications
import com.example.backlogium.data.local.entity.TimedContributionPlacement
import com.example.backlogium.data.local.entity.UnknownTimeAllocation
import com.example.backlogium.data.local.entity.UnknownTimeAllocationRecipe
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/**
 * Storage for trustworthy observation state, funded contributions and their placements
 * (recover-missed-playtime, design.md Decision 2-4). This is the only persistence surface the
 * attribution committer and planner need; every quantity-authorizing write and the reads that
 * authorize it happen inside the caller's Room transaction.
 *
 * Idempotency is structural: contributions and placements carry deterministic derived primary keys,
 * so replaying an identical plan bumps exactly nothing. A conflicting identity (same derived key,
 * different quantity or window) is *rejected*, never silently ignored. Every write-bearing public
 * entry point is guarded by the durable profile account, so a stale worker admitted before a reset
 * cannot repopulate cleared state.
 */
@Dao
interface PlaytimeRecoveryDao {

    // ------------------------------------------------------------------ per-game state

    /** Bulk baseline read for an account, for the committer's transaction-scoped diff. */
    @Query("SELECT * FROM game_recovery_state WHERE account = :account")
    suspend fun statesForAccount(account: String): List<GameRecoveryState>

    /** One game's recovery baseline, or null when the game has never been baselined. */
    @Query("SELECT * FROM game_recovery_state WHERE account = :account AND appId = :appId")
    suspend fun state(account: String, appId: Long): GameRecoveryState?

    @Upsert
    suspend fun upsertState(state: GameRecoveryState)

    /** The configured account, read from the singleton profile — the durable reset barrier. */
    @Query("SELECT steamId FROM player_profile WHERE id = 0")
    suspend fun activeAccount(): String?

    /** Remove one game's baseline (shared→owned conversion re-baselines on its next owned read). */
    @Query("DELETE FROM game_recovery_state WHERE account = :account AND appId = :appId")
    suspend fun deleteStateFor(account: String, appId: Long)

    @Transaction
    suspend fun upsertStateIfAccountActive(state: GameRecoveryState): Boolean {
        if (activeAccount() != state.account) return false
        upsertState(state)
        return true
    }

    @Transaction
    suspend fun insertContributionIfAccountActive(contribution: PlaytimeContribution): Boolean {
        if (activeAccount() != contribution.account) return false
        return insertContributionChecked(contribution)
    }

    /**
     * The honest high-water update routine (tasks 2.2):
     * - a previously unbaselined game *seeds* quantity only — no minutes credited, and epoch
     *   [epoch] (default 1) is stamped so contribution identities can never escape their baseline;
     * - a reading at/above the high-water advances the usable observation boundary (including a
     *   zero delta: delayed Steam publication cannot reveal the real date of unchanged play, so the
     *   boundary still moves); an *older* response exposing new quantity advances the high-water
     *   but never rewinds the newer boundary, and never rewinds a day's newer snapshot metadata;
     * - a regression records a diagnostic daily snapshot and touches neither the high-water nor
     *   the boundary, so a replay can never manufacture minutes.
     *
     * A caller whose [account] is not the configured active account writes nothing and receives a
     * [BaselineAdvance.ignored] result. Parameters are ordered `(account, appId, absoluteMinutes,
     * observedAt, date, zone, epoch)`; the date/zone pair are the observation's original device
     * date and zone and are stored as the retained metadata (never as normalized allocation dates).
     */
    @Transaction
    suspend fun advanceBaseline(
        account: String,
        appId: Long,
        absoluteMinutes: Long,
        observedAt: Long,
        date: String?,
        zone: String?,
        epoch: Long = 1L,
    ): BaselineAdvance {
        if (activeAccount() != account) {
            return BaselineAdvance(0L, baselineAdvanced = false, firstBaseline = false, regression = false, ignored = true)
        }
        require(appId > 0L) { "non-positive appId rejected: $appId" }
        require(epoch > 0L) { "non-positive baseline epoch rejected: $epoch" }
        require(absoluteMinutes >= 0L) { "negative cumulative reading rejected: $absoluteMinutes" }
        val existing = state(account, appId)
        if (existing == null) {
            upsertState(
                GameRecoveryState(
                    account = account,
                    appId = appId,
                    baselineEpoch = epoch,
                    highWaterMinutes = absoluteMinutes,
                    lastObservationAt = observedAt.takeIf { it > 0L },
                    lastObservationDate = date,
                    lastObservationZone = zone,
                    clockState = if (zone == null) RecoveryClockStates.UNKNOWN else RecoveryClockStates.VALID,
                    updatedAt = observedAt,
                ),
            )
            if (date != null) {
                upsertSnapshot(
                    DailyPlaytimeSnapshot(
                        account = account,
                        appId = appId,
                        date = date,
                        zone = zone.orEmpty(),
                        absoluteMinutes = absoluteMinutes,
                        observedAt = observedAt,
                        classification = RecoverySnapshotClassifications.TRUSTED,
                    ),
                )
            }
            return BaselineAdvance(0L, baselineAdvanced = false, firstBaseline = true, regression = false)
        }

        if (absoluteMinutes < existing.highWaterMinutes) {
            if (date != null) {
                upsertSnapshot(
                    DailyPlaytimeSnapshot(
                        account = account,
                        appId = appId,
                        date = date,
                        zone = zone.orEmpty(),
                        absoluteMinutes = absoluteMinutes,
                        observedAt = observedAt,
                        classification = RecoverySnapshotClassifications.REGRESSION,
                    ),
                )
            }
            return BaselineAdvance(-1L, baselineAdvanced = false, firstBaseline = false, regression = true)
        }

        val delta = absoluteMinutes - existing.highWaterMinutes
        val advancesBoundary = observedAt >= (existing.lastObservationAt ?: Long.MIN_VALUE)
        upsertState(
            existing.copy(
                highWaterMinutes = absoluteMinutes,
                lastObservationAt = if (advancesBoundary) observedAt else existing.lastObservationAt,
                lastObservationDate = if (advancesBoundary) date else existing.lastObservationDate,
                lastObservationZone = if (advancesBoundary) zone else existing.lastObservationZone,
                clockState = when {
                    !advancesBoundary -> existing.clockState
                    zone == null -> RecoveryClockStates.UNKNOWN
                    else -> RecoveryClockStates.VALID
                },
                updatedAt = observedAt,
            ),
        )
        if (date != null) {
            upsertSnapshot(
                DailyPlaytimeSnapshot(
                    account = account,
                    appId = appId,
                    date = date,
                    zone = zone.orEmpty(),
                    absoluteMinutes = absoluteMinutes,
                    observedAt = observedAt,
                    classification = RecoverySnapshotClassifications.TRUSTED,
                ),
            )
        }
        return BaselineAdvance(delta, baselineAdvanced = delta > 0L, firstBaseline = false, regression = false)
    }

    /**
     * Deliberately re-establish a baseline for a fresh epoch without importing any prior lifetime
     * total as play: account reset followed a coherent restore, or a shared→owned conversion where
     * the first owned reading must seed quantity only. The observed total becomes the high-water,
     * so no delta exists until the *next* increase.
     */
    @Transaction
    suspend fun reestablishBaseline(
        account: String,
        appId: Long,
        absoluteMinutes: Long,
        observedAt: Long,
        date: String?,
        zone: String?,
        epoch: Long,
    ): BaselineAdvance {
        if (activeAccount() != account) {
            return BaselineAdvance(0L, baselineAdvanced = false, firstBaseline = false, regression = false, ignored = true)
        }
        deleteStateFor(account, appId)
        return advanceBaseline(account, appId, absoluteMinutes, observedAt, date, zone, epoch)
    }

    // ------------------------------------------------------------------ daily snapshots

    /**
     * Same-day successful reads replace the day's snapshot — but the day always keeps its *newest*
     * observation metadata. An older response that still exposes genuinely new quantity advances
     * the state high-water through [advanceBaseline]; it must never rewind the day's raw snapshot
     * backwards. On an exact-equal `observedAt` tie the incoming row replaces (identical replay is
     * idempotent; the tie-break is deterministic latest-write).
     *
     * For writes from [advanceBaseline] the account barrier has already held before this is reached.
     */
    @Transaction
    suspend fun upsertSnapshot(snapshot: DailyPlaytimeSnapshot): Boolean {
        val existing = snapshot(snapshot.account, snapshot.appId, snapshot.date)
        if (existing != null && snapshot.observedAt < existing.observedAt) return false
        upsertSnapshotRow(snapshot)
        return true
    }

    @Upsert
    suspend fun upsertSnapshotRow(snapshot: DailyPlaytimeSnapshot)

    @Query(
        "SELECT * FROM playtime_daily_snapshots " +
            "WHERE account = :account AND appId = :appId AND date = :date",
    )
    suspend fun snapshot(account: String, appId: Long, date: String): DailyPlaytimeSnapshot?

    @Query(
        "SELECT * FROM playtime_daily_snapshots " +
            "WHERE account = :account AND appId = :appId ORDER BY date DESC LIMIT :limit",
    )
    suspend fun recentSnapshots(account: String, appId: Long, limit: Int): List<DailyPlaytimeSnapshot>

    // ------------------------------------------------------------------ contributions

    /**
     * Low-level write, idempotent by deterministic primary key. Prefer
     * [insertContributionChecked]/[commitAllocatedGraph], which reject conflicting identities,
     * malformed ranges and overlapping ranges instead of silently keeping the older row.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertContributionRow(contribution: PlaytimeContribution): Long

    /**
     * Idempotent by deterministic identity. Replaying an identical contribution is a no-op (returns
     * false); a *conflicting* replay of the same identity, a malformed range, or a new range that
     * overlaps an existing funded range of the same account/app/epoch is rejected before any write.
     *
     * @return true when the contribution was newly created.
     */
    @Transaction
    suspend fun insertContributionChecked(contribution: PlaytimeContribution): Boolean {
        require(contribution.account.isNotBlank()) { "blank account rejected" }
        require(contribution.appId > 0L) { "non-positive appId rejected: ${contribution.appId}" }
        require(contribution.baselineEpoch > 0L) { "non-positive baseline epoch rejected: ${contribution.baselineEpoch}" }
        require(contribution.fromMinutes >= 0L) { "negative range lower bound rejected" }
        require(contribution.toMinutes > contribution.fromMinutes) { "cumulative range must be non-empty" }
        require(contribution.authorizedMinutes == contribution.toMinutes - contribution.fromMinutes) {
            "authorized quantity must equal the cumulative range width"
        }
        val existing = contribution(contribution.contributionId)
        if (existing != null) {
            require(quantityMatches(existing, contribution)) {
                "conflicting funded contribution identity: ${contribution.contributionId}"
            }
            return false
        }
        require(
            hasOverlappingContribution(
                contribution.account,
                contribution.appId,
                contribution.baselineEpoch,
                contribution.fromMinutes,
                contribution.toMinutes,
            ) == 0,
        ) {
            "overlapping funded contribution range for ${contribution.account}:${contribution.appId} " +
                "epoch ${contribution.baselineEpoch} in (${contribution.fromMinutes}, ${contribution.toMinutes}]"
        }
        return insertContributionRow(contribution) != -1L
    }

    @Query("SELECT * FROM playtime_contributions WHERE contributionId = :contributionId")
    suspend fun contribution(contributionId: String): PlaytimeContribution?

    /** Prior published name; now rejects conflicts (see [insertContributionChecked]). */
    @Deprecated("Use insertContributionChecked; conflicting identity is rejected, not ignored")
    @Transaction
    suspend fun insertContributionIfAbsent(contribution: PlaytimeContribution): Boolean =
        insertContributionChecked(contribution)

    /** Compatibility alias for focused persistence fixtures; production must use the checked name. */
    @Deprecated("Use insertContributionChecked")
    suspend fun insertContribution(contribution: PlaytimeContribution): Boolean =
        insertContributionChecked(contribution)

    @Query(
        "SELECT * FROM playtime_contributions WHERE account = :account AND appId = :appId " +
            "ORDER BY baselineEpoch ASC, fromMinutes ASC",
    )
    suspend fun contributionsForGame(account: String, appId: Long): List<PlaytimeContribution>

    /** Recovery contributions for a bounded set of games during one whole-library observation. */
    @Query(
        "SELECT * FROM playtime_contributions WHERE account = :account AND appId IN (:appIds) " +
            "ORDER BY appId, baselineEpoch ASC, fromMinutes ASC",
    )
    suspend fun contributionsForGames(account: String, appIds: List<Long>): List<PlaytimeContribution>

    @Query("SELECT * FROM playtime_contributions WHERE account = :account ORDER BY appId, baselineEpoch, fromMinutes")
    suspend fun contributionsForAccount(account: String): List<PlaytimeContribution>

    /** Distinct deterministic game keys for bounded account-wide backup export reads. */
    @Query("SELECT DISTINCT appId FROM playtime_contributions WHERE account = :account ORDER BY appId")
    suspend fun contributionAppIdsForAccount(account: String): List<Long>

    @Query(
        "SELECT COUNT(*) FROM playtime_contributions " +
            "WHERE account = :account AND appId = :appId AND baselineEpoch = :baselineEpoch " +
            "AND :fromMinutes < toMinutes AND :toMinutes > fromMinutes",
    )
    suspend fun hasOverlappingContribution(
        account: String,
        appId: Long,
        baselineEpoch: Long,
        fromMinutes: Long,
        toMinutes: Long,
    ): Int

    /** Advance a contribution's placement revision without changing its authorized quantity. */
    @Query(
        "UPDATE playtime_contributions SET revision = :revision " +
            "WHERE contributionId = :contributionId AND revision < :revision",
    )
    suspend fun advanceRevision(contributionId: String, revision: Long): Int

    // ------------------------------------------------------------------ timed placements

    /** Deterministic id makes a replayed plan a no-op; a new revision adds rows, never edits old ones. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTimedPlacements(placements: List<TimedContributionPlacement>)

    /** Active (non-superseded) funded timed shares for a contribution. */
    @Query(
        "SELECT * FROM recovery_timed_placements " +
            "WHERE contributionId = :contributionId AND superseded = 0 " +
            "ORDER BY creditedDate ASC",
    )
    suspend fun activeTimedPlacements(contributionId: String): List<TimedContributionPlacement>

    /** Active funded shares for all contributions of one game (delayed-evidence reconciliation). */
    @Query(
        "SELECT * FROM recovery_timed_placements " +
            "WHERE account = :account AND appId = :appId AND superseded = 0 " +
            "ORDER BY contributionId, creditedDate ASC",
    )
    suspend fun activeTimedPlacementsForGame(
        account: String,
        appId: Long,
    ): List<TimedContributionPlacement>

    /** Active funded shares for a bounded set of games in one observation batch. */
    @Query(
        "SELECT * FROM recovery_timed_placements " +
            "WHERE account = :account AND appId IN (:appIds) AND superseded = 0 " +
            "ORDER BY appId, contributionId, creditedDate ASC",
    )
    suspend fun activeTimedPlacementsForGames(
        account: String,
        appIds: List<Long>,
    ): List<TimedContributionPlacement>

    @Query("SELECT * FROM recovery_timed_placements WHERE account = :account AND superseded = 0")
    suspend fun activeTimedPlacementsForAccount(account: String): List<TimedContributionPlacement>

    /** Active funded shares sitting inside one session — the mixed-row legacy remainder subtracts these. */
    @Query(
        "SELECT * FROM recovery_timed_placements " +
            "WHERE sessionId = :sessionId AND superseded = 0",
    )
    suspend fun activeTimedSharesForSession(sessionId: Long): List<TimedContributionPlacement>

    /** Active funded shares for multiple sessions; avoids one peer query per changed session. */
    @Query(
        "SELECT * FROM recovery_timed_placements " +
            "WHERE sessionId IN (:sessionIds) AND superseded = 0 ORDER BY sessionId",
    )
    suspend fun activeTimedSharesForSessions(sessionIds: List<Long>): List<TimedContributionPlacement>

    @Query(
        "SELECT COALESCE(SUM(minutes), 0) FROM recovery_timed_placements " +
            "WHERE contributionId = :contributionId AND superseded = 0",
    )
    suspend fun sumActiveTimedMinutes(contributionId: String): Long

    /** Mark every older-revision placement superseded; the kept revision's rows stay active. */
    @Query(
        "UPDATE recovery_timed_placements SET superseded = 1 " +
            "WHERE contributionId = :contributionId AND revision < :keepRevision",
    )
    suspend fun supersedeTimedPlacementsUpTo(contributionId: String, keepRevision: Long): Int

    // ------------------------------------------------------------------ unknown-time allocations

    /** Deterministic placement id makes a replayed plan a no-op and forbids double credit. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllocations(allocations: List<UnknownTimeAllocation>)

    /** Active dated and pending allocations for a contribution, chronologically. */
    @Query(
        "SELECT * FROM recovery_unknown_allocations " +
            "WHERE contributionId = :contributionId AND superseded = 0 " +
            "ORDER BY pendingDate ASC, date ASC",
    )
    suspend fun activeAllocations(contributionId: String): List<UnknownTimeAllocation>

    /** Active allocations for a bounded set of games in one observation batch. */
    @Query(
        "SELECT * FROM recovery_unknown_allocations " +
            "WHERE account = :account AND appId IN (:appIds) AND superseded = 0 " +
            "ORDER BY appId, contributionId, pendingDate ASC, date ASC",
    )
    suspend fun activeAllocationsForGames(
        account: String,
        appIds: List<Long>,
    ): List<UnknownTimeAllocation>

    @Query("SELECT * FROM recovery_unknown_allocations WHERE account = :account AND superseded = 0")
    suspend fun activeAllocationsForAccount(account: String): List<UnknownTimeAllocation>

    /** Active allocations touching one date — History/Analytics canonical dated credit. */
    @Query(
        "SELECT * FROM recovery_unknown_allocations " +
            "WHERE account = :account AND date = :date AND superseded = 0 AND pendingDate = 0",
    )
    suspend fun activeAllocationsForDate(account: String, date: String): List<UnknownTimeAllocation>

    /** Pending date-unknown quantity: eligible for cumulative XP, never a dated quest. */
    @Query(
        "SELECT COALESCE(SUM(minutes), 0) FROM recovery_unknown_allocations " +
            "WHERE account = :account AND appId = :appId AND superseded = 0 AND pendingDate = 1",
    )
    suspend fun sumActivePendingMinutes(account: String, appId: Long): Long

    /** Placed (dated) unknown-time credit for a game. */
    @Query(
        "SELECT COALESCE(SUM(minutes), 0) FROM recovery_unknown_allocations " +
            "WHERE account = :account AND appId = :appId AND superseded = 0 AND pendingDate = 0",
    )
    suspend fun sumActiveDatedMinutes(account: String, appId: Long): Long

    /** Dated active allocations for one contribution (recipe materialization slices). */
    @Query(
        "SELECT COALESCE(SUM(minutes), 0) FROM recovery_unknown_allocations " +
            "WHERE contributionId = :contributionId AND superseded = 0 AND pendingDate = 0",
    )
    suspend fun sumActiveDatedForContribution(contributionId: String): Long

    /** Pending active allocations for one contribution (must not coexist with a dated recipe). */
    @Query(
        "SELECT COALESCE(SUM(minutes), 0) FROM recovery_unknown_allocations " +
            "WHERE contributionId = :contributionId AND superseded = 0 AND pendingDate = 1",
    )
    suspend fun sumActivePendingForContribution(contributionId: String): Long

    /** Dates already materialized for this contribution/revision (for proven-prefix validation). */
    @Query(
        "SELECT date FROM recovery_unknown_allocations " +
            "WHERE contributionId = :contributionId AND revision = :revision " +
            "AND superseded = 0 AND pendingDate = 0 AND date IS NOT NULL",
    )
    suspend fun materializedDates(contributionId: String, revision: Long): List<String>

    @Query(
        "UPDATE recovery_unknown_allocations SET superseded = 1 " +
            "WHERE contributionId = :contributionId AND revision < :keepRevision",
    )
    suspend fun supersedeAllocationsUpTo(contributionId: String, keepRevision: Long): Int

    /** Placed dated credit grouped by date — the canonical raw daily contribution view. */
    @Query(
        "SELECT date AS date, COALESCE(SUM(minutes), 0) AS minutes " +
            "FROM recovery_unknown_allocations " +
            "WHERE account = :account AND superseded = 0 AND pendingDate = 0 " +
            "GROUP BY date ORDER BY date",
    )
    suspend fun creditedMinutesByDate(account: String): List<CreditedDateMinutes>

    /** Placed dated credit grouped by game — canonical per-game contribution view. */
    @Query(
        "SELECT appId AS appId, COALESCE(SUM(minutes), 0) AS minutes " +
            "FROM recovery_unknown_allocations " +
            "WHERE account = :account AND superseded = 0 AND pendingDate = 0 " +
            "GROUP BY appId",
    )
    suspend fun allocatedMinutesByGame(account: String): List<GameAllocatedMinutes>

    // ------------------------------------------------------------------ compact recipes

    @Upsert
    suspend fun upsertRecipe(recipe: UnknownTimeAllocationRecipe)

    @Query("SELECT * FROM recovery_allocation_recipes WHERE contributionId = :contributionId")
    suspend fun recipe(contributionId: String): UnknownTimeAllocationRecipe?

    /** All compact recipes for one account for snapshot-consistent backup export. */
    @Query("SELECT * FROM recovery_allocation_recipes WHERE account = :account")
    suspend fun recipesForAccount(account: String): List<UnknownTimeAllocationRecipe>

    /** Remove the prior compact graph when a placement revision becomes fully row-materialized. */
    @Query("DELETE FROM recovery_allocation_recipes WHERE contributionId = :contributionId")
    suspend fun deleteRecipe(contributionId: String)

    /** The exact residual a compact recipe still owes: total minus materialized minutes. */
    @Transaction
    suspend fun recipeResidual(contributionId: String): Long {
        val recipe = recipe(contributionId) ?: return 0L
        val total = recipeWindow(recipe)?.total ?: 0L
        return total - recipe.materializedMinutes
    }

    /**
     * Materialize a bounded slice of a recipe's dates into [UnknownTimeAllocation] rows.
     *
     * Semantics are rank-based and window-iteration-free (a recipe is never expanded):
     * - every date's minutes must equal the recipe's frozen quotient/remainder share for that
     *   date's *eligible rank* (computed as calendar ordinal minus excluded-dates before it, O(|exclusions|));
     * - zero-share dates never receive rows, and excluded dates never receive rows;
     * - the materialized rows together must be the contiguous proven prefix of *positive* eligible
     *   dates — ranks 1..m with no skipped positive date (excluded/zero days do not count);
     * - [materializedThroughDate], when supplied, must not skip positive dates and bounds the
     *   proven cursor; when omitted, the cursor is the last row's date, or the window end once
     *   every positive date is proven (the zero tail needs no rows);
     * - repeated slices are idempotent: duplicate placement ids are ignored and
     *   [UnknownTimeAllocationRecipe.materializedMinutes] is re-read from the rows, never incremented.
     */
    @Transaction
    suspend fun materializeRecipeSlice(
        allocations: List<UnknownTimeAllocation>,
        materializedThroughDate: String? = null,
    ): Boolean {
        require(allocations.isNotEmpty()) { "empty materialization slice" }
        val contributionId = allocations.first().contributionId
        require(allocations.all { it.contributionId == contributionId }) { "slice mixes contributions" }
        val recipe = recipe(contributionId) ?: throw IllegalStateException("no recipe for materialization")
        val contribution = contribution(contributionId) ?: throw IllegalStateException("missing contribution")
        require(recipe.revision == contribution.revision) { "recipe revision is not current" }
        require(!recipe.pending) { "pending quantity is an allocation, not a dated recipe" }
        val window = recipeWindow(recipe) ?: throw IllegalStateException("recipe window is invalid or non-positive")
        require(recipe.eligibleCount == window.eligibleCount) {
            "recipe eligibleCount ${recipe.eligibleCount} does not match its calendar window ${window.eligibleCount}"
        }
        require(allocations.all { !it.pendingDate && it.date != null && it.minutes > 0L && it.revision == recipe.revision }) {
            "slice must be dated, positive and at the recipe's revision"
        }
        for (allocation in allocations) {
            val date = parseIso(allocation.date!!) ?: throw IllegalArgumentException("invalid allocation date ${allocation.date}")
            val rank = window.rank(date) ?: throw IllegalArgumentException("allocation date ${allocation.date} is outside the window or excluded")
            val share = window.shareFor(rank)
            require(share > 0L) { "zero-share dates never receive activity rows: ${allocation.date}" }
            require(allocation.minutes == share) {
                "allocation ${allocation.date} must be exactly $share per the frozen recipe, was ${allocation.minutes}"
            }
            require(allocation.zone == null || recipe.zone == null || allocation.zone == recipe.zone) {
                "allocation zone must match the frozen recipe zone"
            }
        }
        val existingDates = materializedDates(contributionId, recipe.revision).mapNotNull { parseIso(it) }.sorted()
        val incomingDates = allocations.map { parseIso(it.date!!)!! }.sorted()
        // Existing rows must themselves be a proven prefix — holes from imports are rejected, never healed silently.
        requireRanksAreProvenPrefix(window, existingDates)
        val allDates = (existingDates + incomingDates).distinct().sorted()
        requireRanksAreProvenPrefix(window, allDates)
        require(allDates.size <= window.positiveCount) { "more rows than the recipe's positive dates" }

        val cursor = cursorFor(window, allDates, materializedThroughDate)

        insertAllocations(allocations)
        val materialized = sumActiveDatedForContribution(contributionId)
        require(materialized <= window.total) { "materialized slices exceed recipe total" }
        upsertRecipe(
            recipe.copy(
                materializedMinutes = materialized,
                materializedThroughDate = cursor,
                updatedAt = allocations.maxOf { it.createdAt },
            ),
        )
        return true
    }

    // ------------------------------------------------------------------ journal

    @Upsert
    suspend fun upsertJournal(journal: PlaytimeRecoveryJournal)

    @Query("SELECT * FROM recovery_journal WHERE operationId = :operationId")
    suspend fun journal(operationId: String): PlaytimeRecoveryJournal?

    @Query("SELECT * FROM recovery_journal WHERE contributionId = :contributionId ORDER BY updatedAt ASC")
    suspend fun journalsForContribution(contributionId: String): List<PlaytimeRecoveryJournal>

    /** All recovery journals for one account for snapshot-consistent backup export. */
    @Query("SELECT * FROM recovery_journal WHERE account = :account ORDER BY contributionId, updatedAt ASC")
    suspend fun journalsForAccount(account: String): List<PlaytimeRecoveryJournal>

    /** Operations whose staged derived finalization has not completed — resume candidates. */
    @Query(
        "SELECT * FROM recovery_journal WHERE account = :account " +
            "AND derivedStatus IN ('RAW_COMMITTED', 'CORRECTIVE_DONE', 'EARNED_DONE')",
    )
    suspend fun pendingFinalization(account: String): List<PlaytimeRecoveryJournal>

    @Query(
        "UPDATE recovery_journal SET derivedStatus = :status, updatedAt = :updatedAt " +
            "WHERE operationId = :operationId",
    )
    suspend fun markJournalStatus(operationId: String, status: String, updatedAt: Long): Int

    // ------------------------------------------------------------------ local timing evidence

    @Upsert
    suspend fun upsertLocalEvidence(evidence: LocalTimingEvidence)

    @Query(
        "SELECT * FROM recovery_local_evidence " +
            "WHERE account = :account AND appId = :appId AND generation = :generation",
    )
    suspend fun localEvidence(account: String, appId: Long, generation: Long): LocalTimingEvidence?

    @Query("SELECT * FROM recovery_local_evidence WHERE account = :account ORDER BY updatedAt DESC")
    suspend fun evidenceForAccount(account: String): List<LocalTimingEvidence>

    // ------------------------------------------------------------------ sessions (ref validation)

    @Query("SELECT appId AS appId, minutes AS minutes FROM sessions WHERE id = :sessionId")
    suspend fun sessionRow(sessionId: Long): SessionMinutesRow?

    /** Active funded timed shares summed per session across *all* contributions. */
    @Query(
        "SELECT sessionId AS sessionId, COALESCE(SUM(minutes), 0) AS minutes " +
            "FROM recovery_timed_placements " +
            "WHERE superseded = 0 AND sessionId IN (:sessionIds) GROUP BY sessionId",
    )
    suspend fun fundedMinutesBySession(sessionIds: List<Long>): List<SessionFundedMinutes>

    // ------------------------------------------------------------------ conservation

    /** `active timed shares + active unknown-time shares + pending shares` for one contribution. */
    @Transaction
    suspend fun conservedAllocatedMinutes(contributionId: String): Long {
        val timed = sumActiveTimedMinutes(contributionId)
        val recipe = recipe(contributionId)
        return if (recipe != null && !recipe.pending) {
            // A compact recipe is authoritative for its whole residual; materialized allocation
            // rows are slices of it, counted through the recipe exactly once — never alongside.
            timed + (recipeWindow(recipe)?.total ?: 0L) + sumActivePendingForContribution(contributionId)
        } else {
            timed + sumActiveAllocatedMinutes(contributionId)
        }
    }

    @Query(
        "SELECT COALESCE(SUM(minutes), 0) FROM recovery_unknown_allocations " +
            "WHERE contributionId = :contributionId AND superseded = 0",
    )
    suspend fun sumActiveAllocatedMinutes(contributionId: String): Long

    /**
     * Conservation invariant: `authorized minutes = active timed shares + active unknown-time
     * shares + pending shares`. Returns false for an unknown contribution or a conflicting
     * quantity graph; the planner must not commit a plan that does not hold.
     */
    @Transaction
    suspend fun conservationHolds(contributionId: String): Boolean {
        val contribution = contribution(contributionId) ?: return false
        return conservedAllocatedMinutes(contributionId) == contribution.authorizedMinutes
    }

    /** Whether accepting [additionalMinutes] keeps this contribution within authorization (rejects negatives, overflow-safe). */
    @Transaction
    suspend fun canAcceptShare(contributionId: String, additionalMinutes: Long): Boolean {
        if (additionalMinutes < 0L) return false
        val contribution = contribution(contributionId) ?: return false
        val conserved = conservedAllocatedMinutes(contributionId)
        if (conserved < 0L || conserved > contribution.authorizedMinutes) return false
        return additionalMinutes <= contribution.authorizedMinutes - conserved
    }

    /**
     * Full structural validation of a funded contribution's graph — reference/identity checks, not
     * just sums:
     * - every active share keeps the contribution's account/app/revision, positive minutes and a
     *   coherent pending<->date shape;
     * - timed shares resolve to existing sessions of the matching app whose funded total across
     *   *all* contributions never exceeds the session's actual minutes;
     * - a compact recipe matches the contribution (account/app/revision), its `eligibleCount`
     *   equals its calendar window cardinality, every materialized date holds the frozen exact
     *   share at its eligible rank, materialized rows are ranks 1..m with no skipped positive date,
     *   `materializedMinutes` equals the rows, the stored cursor proves every positive date at or
     *   before it, and pending quantity never coexists;
     * - authorized quantity is conserved exactly.
     */
    @Transaction
    suspend fun validateGraph(contributionId: String): Boolean {
        val contribution = contribution(contributionId) ?: return false
        val timed = activeTimedPlacements(contributionId)
        val allocations = activeAllocations(contributionId)

        if (timed.any { !shareIdentityValid(it, contribution) }) return false
        if (allocations.any { !shareIdentityValid(it, contribution) }) return false
        if (!sessionBoundsValid(this, timed)) return false

        val recipe = recipe(contributionId)
        if (recipe != null) {
            if (recipe.account != contribution.account || recipe.appId != contribution.appId) return false
            if (recipe.revision != contribution.revision) return false
            if (recipe.pending) {
                if (sumActivePendingForContribution(contributionId) == 0L) return false
            } else {
                if (sumActivePendingForContribution(contributionId) > 0L) return false
                val window = recipeWindow(recipe) ?: return false
                if (window.eligibleCount != recipe.eligibleCount) return false
                val rows = materializedDates(contributionId, recipe.revision).mapNotNull { parseIso(it) }.sorted()
                if (!rowsAreProvenPrefix(window, rows, recipe.zone, allocations)) return false
                if (rows.size > window.positiveCount) return false
                val cursor = recipe.materializedThroughDate
                if (cursor != null) {
                    val cd = parseIso(cursor) ?: return false
                    if (cd.isBefore(window.start) || cd.isAfter(window.end)) return false
                    if (minOf(window.positiveCount, window.eligibleCountAtOrBefore(cd)) != rows.size) return false
                } else if (rows.size == window.positiveCount) {
                    // A completed recipe must prove its zero tail with a cursor.
                    return false
                }
                if (sumActiveDatedForContribution(contributionId) != recipe.materializedMinutes) return false
                if (recipe.materializedMinutes > window.total) return false
            }
        } else {
            // Without a recipe, a pending row is the only shape allowed to have a null date.
            if (allocations.any { it.pendingDate != (it.date == null) }) return false
        }
        return conservedAllocatedMinutes(contributionId) == contribution.authorizedMinutes
    }

    /**
     * Commit one funded contribution and its full placement graph atomically. Validates account,
     * app, epoch, range bounds and identity before the write; rejects share account/app mismatches,
     * invalid pending<->date shapes, negatives, unknown or wrong-app sessions whose funded total
     * exceeds the session's minutes, over-materialized, skipped-positive or non-proven recipe
     * slices and any non-conserved graph. The whole transaction rolls back on the first violated
     * invariant — including a conflicting identity or overlapping range.
     */
    @Transaction
    suspend fun commitAllocatedGraph(
        contribution: PlaytimeContribution,
        timed: List<TimedContributionPlacement>,
        allocations: List<UnknownTimeAllocation>,
        recipe: UnknownTimeAllocationRecipe? = null,
    ): Boolean {
        require(contribution.account.isNotBlank()) { "blank account rejected" }
        require(contribution.appId > 0L) { "non-positive appId rejected: ${contribution.appId}" }
        require(contribution.baselineEpoch > 0L) { "non-positive baseline epoch rejected" }
        require(contribution.fromMinutes >= 0L) { "negative range lower bound rejected" }
        require(contribution.toMinutes > contribution.fromMinutes) { "cumulative range must be non-empty" }
        require(contribution.authorizedMinutes == contribution.toMinutes - contribution.fromMinutes) {
            "authorized quantity must equal the cumulative range width"
        }
        require(timed.all { shareIdentityValid(it, contribution) }) { "timed share violates contribution identity" }
        require(allocations.all { shareIdentityValid(it, contribution) }) { "allocation violates contribution identity" }

        val existing = contribution(contribution.contributionId)
        if (existing == null) {
            require(
                hasOverlappingContribution(
                    contribution.account,
                    contribution.appId,
                    contribution.baselineEpoch,
                    contribution.fromMinutes,
                    contribution.toMinutes,
                ) == 0,
            ) {
                "overlapping funded contribution range for ${contribution.account}:${contribution.appId} " +
                    "epoch ${contribution.baselineEpoch}"
            }
            insertContributionRow(contribution)
        } else {
            require(quantityMatches(existing, contribution)) {
                "conflicting funded contribution identity: ${contribution.contributionId}"
            }
        }

        // Timed shares and allocations land in *both* modes; only the recipe bookkeeping differs.
        insertTimedPlacements(timed)

        if (recipe != null) {
            require(recipe.account == contribution.account && recipe.appId == contribution.appId) {
                "recipe must target the same account/app as its contribution"
            }
            require(recipe.contributionId == contribution.contributionId) { "recipe targets another contribution" }
            require(!recipe.pending) { "pending quantity is an allocation, not a dated recipe" }
            require(recipe.revision == contribution.revision) { "recipe revision is not current" }
            require(allocations.all { !it.pendingDate && it.date != null }) {
                "recipe materialization must be dated; pending rows belong to a pending contribution"
            }
            val window = recipeWindow(recipe) ?: throw IllegalStateException("recipe window is invalid or non-positive")
            require(recipe.eligibleCount == window.eligibleCount) {
                "recipe eligibleCount ${recipe.eligibleCount} does not match its calendar window ${window.eligibleCount}"
            }
            require(recipe.zone == null || contribution.zone == null || recipe.zone == contribution.zone) {
                "recipe zone must match the frozen contribution zone"
            }
            for (allocation in allocations) {
                val date = parseIso(allocation.date!!) ?: throw IllegalArgumentException("invalid allocation date ${allocation.date}")
                val rank = window.rank(date) ?: throw IllegalArgumentException("allocation date ${allocation.date} is outside the window or excluded")
                val share = window.shareFor(rank)
                require(share > 0L) { "zero-share dates never receive activity rows: ${allocation.date}" }
                require(allocation.minutes == share) {
                    "allocation ${allocation.date} must be exactly $share per the frozen recipe, was ${allocation.minutes}"
                }
                require(allocation.zone == null || recipe.zone == null || allocation.zone == recipe.zone) {
                    "allocation zone must match the frozen recipe zone"
                }
            }
            val existingDates = materializedDates(contribution.contributionId, recipe.revision).mapNotNull { parseIso(it) }.sorted()
            requireRanksAreProvenPrefix(window, existingDates)
            val allDates = (existingDates + allocations.map { parseIso(it.date!!)!! }).distinct().sorted()
            requireRanksAreProvenPrefix(window, allDates)
            require(allDates.size <= window.positiveCount) { "more rows than the recipe's positive dates" }
            val cursor = cursorFor(window, allDates, materializedThroughDate = null)
            insertAllocations(allocations)
            val materialized = sumActiveDatedForContribution(contribution.contributionId)
            require(materialized <= window.total) { "materialized slices exceed recipe total" }
            upsertRecipe(
                recipe.copy(
                    materializedMinutes = materialized,
                    materializedThroughDate = cursor,
                    updatedAt = maxOf(contribution.createdAt, allocations.maxOfOrNull { it.createdAt } ?: 0L),
                ),
            )
        } else {
            insertAllocations(allocations)
        }

        // Reference/identity constraints over the *committed* graph (all contributions, all rows).
        val committedTimed = activeTimedPlacements(contribution.contributionId)
        if (committedTimed.any { !shareIdentityValid(it, contribution) }) {
            throw IllegalStateException("timed share violates contribution identity")
        }
        if (!sessionBoundsValid(this, committedTimed)) {
            throw IllegalStateException("funded timed shares exceed or mislink a session")
        }
        require(conservationHolds(contribution.contributionId)) {
            "funded graph violates conservation: authorized ${contribution.authorizedMinutes} " +
                "allocated ${conservedAllocatedMinutes(contribution.contributionId)}"
        }
        return true
    }

    // ------------------------------------------------------------------ reset / account changes

    /** Wipe all recovery state for every account — the reset helper's entire recovery surface. */
    @Transaction
    suspend fun clearAll() {
        clearAllJournals()
        clearAllAllocations()
        clearAllTimedPlacements()
        clearAllRecipes()
        clearAllContributions()
        clearAllSnapshots()
        clearAllStates()
        clearAllLocalEvidence()
    }

    /** Wipe only one account's recovery state (foreign import rejection, account-scoped reset). */
    @Transaction
    suspend fun clearForAccount(account: String) {
        clearJournalsForAccount(account)
        clearAllocationsForAccount(account)
        clearTimedPlacementsForAccount(account)
        clearRecipesForAccount(account)
        clearContributionsForAccount(account)
        clearSnapshotsForAccount(account)
        clearStatesForAccount(account)
        clearLocalEvidenceForAccount(account)
    }

    @Query("DELETE FROM game_recovery_state")
    suspend fun clearAllStates()

    @Query("DELETE FROM playtime_daily_snapshots")
    suspend fun clearAllSnapshots()

    @Query("DELETE FROM playtime_contributions")
    suspend fun clearAllContributions()

    @Query("DELETE FROM recovery_timed_placements")
    suspend fun clearAllTimedPlacements()

    @Query("DELETE FROM recovery_unknown_allocations")
    suspend fun clearAllAllocations()

    @Query("DELETE FROM recovery_allocation_recipes")
    suspend fun clearAllRecipes()

    @Query("DELETE FROM recovery_journal")
    suspend fun clearAllJournals()

    @Query("DELETE FROM recovery_local_evidence")
    suspend fun clearAllLocalEvidence()

    @Query("DELETE FROM game_recovery_state WHERE account = :account")
    suspend fun clearStatesForAccount(account: String)

    @Query("DELETE FROM playtime_daily_snapshots WHERE account = :account")
    suspend fun clearSnapshotsForAccount(account: String)

    @Query("DELETE FROM playtime_contributions WHERE account = :account")
    suspend fun clearContributionsForAccount(account: String)

    @Query("DELETE FROM recovery_timed_placements WHERE account = :account")
    suspend fun clearTimedPlacementsForAccount(account: String)

    @Query("DELETE FROM recovery_unknown_allocations WHERE account = :account")
    suspend fun clearAllocationsForAccount(account: String)

    @Query("DELETE FROM recovery_allocation_recipes WHERE account = :account")
    suspend fun clearRecipesForAccount(account: String)

    @Query("DELETE FROM recovery_journal WHERE account = :account")
    suspend fun clearJournalsForAccount(account: String)

    @Query("DELETE FROM recovery_local_evidence WHERE account = :account")
    suspend fun clearLocalEvidenceForAccount(account: String)
}

/** What one trustworthy observation did to a game's recovery baseline. [deltaMinutes] is -1 for a
 * regression, otherwise the non-negative increase measured beyond the previous high-water.
 * [ignored] is true when the caller's account is not the configured active account and nothing was
 * written. */
data class BaselineAdvance(
    val deltaMinutes: Long,
    val baselineAdvanced: Boolean,
    val firstBaseline: Boolean,
    val regression: Boolean,
    val ignored: Boolean = false,
)

/** One game's placed unknown-time credit — the canonical raw daily contribution projection. */
data class CreditedDateMinutes(
    @ColumnInfo(name = "date") val date: String,
    @ColumnInfo(name = "minutes") val minutes: Long,
)

/** One app's placed unknown-time credit — the canonical per-game contribution projection. */
data class GameAllocatedMinutes(
    @ColumnInfo(name = "appId") val appId: Long,
    @ColumnInfo(name = "minutes") val minutes: Long,
)

/** A session's identity and minutes, for funded-share boundary validation. */
data class SessionMinutesRow(
    @ColumnInfo(name = "appId") val appId: Long,
    @ColumnInfo(name = "minutes") val minutes: Int,
)

/** Funded timed minutes summed per session across all contributions. */
data class SessionFundedMinutes(
    @ColumnInfo(name = "sessionId") val sessionId: Long,
    @ColumnInfo(name = "minutes") val minutes: Long,
)

/**
 * Finite, window-iteration-free reading of a recipe: the calendar window `[dateStart, dateEnd]`,
 * the finite excluded-dates set, the eligible cardinality (`calendar count - excluded count`), the
 * checked total `quotient * eligibleCount + remainder`, and rank/share arithmetic in O(|exclusions|).
 *
 * A recipe is never expanded into its full date list — a ten- or two-hundred-year window costs the
 * same as a four-day one.
 */
private class RecipeWindow(
    val start: LocalDate,
    val end: LocalDate,
    val excluded: List<ClosedRange<LocalDate>>,
    val eligibleCount: Int,
    val total: Long,
    val quotient: Long,
    val remainder: Long,
) {
    /** The number of eligible dates that carry a positive share (a prefix of the eligible ranks). */
    val positiveCount: Int
        get() = if (quotient > 0L) eligibleCount else minOf(eligibleCount, remainder.coerceAtLeast(0L).toInt())

    /**
     * The 1-based rank of [date] among eligible dates, computed as its calendar ordinal minus the
     * excluded dates at or before it. Null when outside the window or excluded.
     */
    fun rank(date: LocalDate): Long? {
        if (date.isBefore(start) || date.isAfter(end)) return null
        if (excluded.any { date >= it.start && date <= it.endInclusive }) return null
        val ordinal = ChronoUnit.DAYS.between(start, date)
        val rank = ordinal + 1L - excludedCountThrough(date)
        return if (rank >= 1L) rank else null
    }

    /** The frozen share for eligible rank [rank]: `q + 1` for the earliest `remainder` ranks, else `q`. */
    fun shareFor(rank: Long): Long = if (rank <= remainder) quotient + 1L else quotient

    /** How many eligible dates lie at or before [date] (the first `rank`-1 invariants). */
    fun eligibleCountAtOrBefore(date: LocalDate): Int {
        val ordinal = ChronoUnit.DAYS.between(start, date)
        val eligibleUpTo = ordinal - excludedCountThrough(date) + 1L
        return eligibleUpTo.coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun excludedCountThrough(date: LocalDate): Long = excluded.sumOf { range ->
        when {
            range.start.isAfter(date) -> 0L
            range.endInclusive <= date -> ChronoUnit.DAYS.between(range.start, range.endInclusive) + 1L
            else -> ChronoUnit.DAYS.between(range.start, date) + 1L
        }
    }
}

private fun parseIso(value: String): LocalDate? = try {
    LocalDate.parse(value)
} catch (e: DateTimeParseException) {
    null
}

/**
 * Finite recipe reading. Returns null (rejected as invalid) for missing/malformed dates, an
 * inverted window, non-positive or over-sized cardinality, negative q/r, `remainder >= eligibleCount`,
 * a non-positive total, or a checked-math overflow (`quotient * eligibleCount` or plus `remainder`).
 */
private fun recipeWindow(recipe: UnknownTimeAllocationRecipe): RecipeWindow? {
    val start = recipe.dateStart?.let(::parseIso) ?: return null
    val end = recipe.dateEnd?.let(::parseIso) ?: return null
    if (end.isBefore(start)) return null
    val exclusionTokens = recipe.excludedDatesCsv?.takeIf { it.isNotBlank() }
        ?.split(',')?.map { it.trim() }.orEmpty()
    val excluded = exclusionTokens.map { token -> parseExclusionRange(token) ?: return null }
        .sortedBy { it.start }
    if (excluded.any { it.start.isBefore(start) || it.endInclusive.isAfter(end) }) return null
    if (excluded.zipWithNext().any { (first, second) -> !second.start.isAfter(first.endInclusive) }) return null
    val calendarCount = ChronoUnit.DAYS.between(start, end) + 1L
    if (calendarCount > Int.MAX_VALUE) return null
    val excludedCount = excluded.sumOf { ChronoUnit.DAYS.between(it.start, it.endInclusive) + 1L }
    val eligibleCount = (calendarCount - excludedCount).toInt()
    if (eligibleCount < 1) return null
    val q = recipe.quotient ?: 0L
    val r = recipe.remainderCount ?: 0L
    if (q < 0L || r < 0L) return null
    if (r >= eligibleCount.toLong()) return null
    val total = try {
        Math.addExact(Math.multiplyExact(q, eligibleCount.toLong()), r)
    } catch (e: ArithmeticException) {
        return null
    }
    if (total <= 0L) return null
    return RecipeWindow(start, end, excluded, eligibleCount, total, q, r)
}

/** One compact exclusion token is an ISO date or an inclusive `first..last` ISO-date range. */
private fun parseExclusionRange(value: String): ClosedRange<LocalDate>? {
    val separator = value.indexOf("..")
    if (separator < 0) {
        val date = parseIso(value) ?: return null
        return date..date
    }
    if (value.indexOf("..", separator + 2) >= 0) return null
    val first = parseIso(value.substring(0, separator)) ?: return null
    val last = parseIso(value.substring(separator + 2)) ?: return null
    if (last.isBefore(first)) return null
    return first..last
}

/**
 * Ranks must be exactly `1..size` — i.e. the rows are the first [dates.size] *positive* eligible
 * dates with no skipped positive date in between. Excluded dates and zero-share dates are simply
 * absent rows and never consume a rank. O(size · |exclusions|), never iterates the calendar window.
 */
private fun requireRanksAreProvenPrefix(window: RecipeWindow, dates: List<LocalDate>) {
    var expectedRank = 1L
    for (date in dates) {
        val rank = window.rank(date) ?: throw IllegalStateException("materialized date is no longer eligible")
        require(rank == expectedRank) { "materialized dates must be ranks 1..m with no skipped positive date" }
        require(rank <= window.positiveCount.toLong()) { "materialized row beyond the recipe's positive dates" }
        expectedRank++
    }
    require(dates.size <= window.positiveCount) { "more rows than the recipe's positive dates" }
}

/** The proven cursor: explicit through-date (validated against positives), else the last row's
 * date, or the window end once every positive date is proven (the zero tail needs no rows). */
private fun cursorFor(window: RecipeWindow, allDates: List<LocalDate>, materializedThroughDate: String?): String? {
    if (materializedThroughDate != null) {
        val cd = parseIso(materializedThroughDate) ?: throw IllegalArgumentException("invalid cursor date $materializedThroughDate")
        require(!cd.isBefore(window.start) && !cd.isAfter(window.end)) { "cursor outside the recipe window" }
        val positivesAtOrBefore = minOf(window.positiveCount, window.eligibleCountAtOrBefore(cd))
        require(positivesAtOrBefore == allDates.size) { "cursor would skip proven positive dates" }
        return cd.toString()
    }
    return if (allDates.size == window.positiveCount) window.end.toString() else allDates.lastOrNull()?.toString()
}

/** Read-only row validation for [validateGraph]: consecutive ranks, positive shares, exact minutes and zone. */
private fun rowsAreProvenPrefix(
    window: RecipeWindow,
    rows: List<LocalDate>,
    recipeZone: String?,
    allocations: List<UnknownTimeAllocation>,
): Boolean {
    var expectedRank = 1L
    for (date in rows) {
        val rank = window.rank(date) ?: return false
        if (rank != expectedRank) return false
        val share = window.shareFor(rank)
        if (share <= 0L) return false
        val row = allocations.firstOrNull { it.date == date.toString() } ?: return false
        if (row.minutes != share) return false
        if (row.zone != null && recipeZone != null && row.zone != recipeZone) return false
        expectedRank++
    }
    return rows.size <= window.positiveCount
}

/** A replay is a no-op only when every quantity and window fact matches the stored row. */
private fun quantityMatches(existing: PlaytimeContribution, incoming: PlaytimeContribution): Boolean =
    existing.account == incoming.account &&
        existing.appId == incoming.appId &&
        existing.baselineEpoch == incoming.baselineEpoch &&
        existing.fromMinutes == incoming.fromMinutes &&
        existing.toMinutes == incoming.toMinutes &&
        existing.authorizedMinutes == incoming.authorizedMinutes &&
        existing.windowStartAt == incoming.windowStartAt &&
        existing.windowEndAt == incoming.windowEndAt

/** Every funded share must target the contribution's own account/app/revision, be positive, and keep a coherent pending<->date shape and non-blank date/zone fields. */
private fun shareIdentityValid(share: Any, contribution: PlaytimeContribution): Boolean = when (share) {
    is TimedContributionPlacement ->
        share.account == contribution.account &&
            share.appId == contribution.appId &&
            share.revision == contribution.revision &&
            share.minutes > 0L &&
            share.creditedDate.isNotBlank() &&
            share.zone.isNotBlank() &&
            (share.sessionId == null || share.sessionId > 0L)
    is UnknownTimeAllocation ->
        share.account == contribution.account &&
            share.appId == contribution.appId &&
            share.revision == contribution.revision &&
            share.minutes > 0L &&
            share.pendingDate == (share.date == null)
    else -> false
}

/** Sessions must exist, belong to the share's app, and never carry more funded minutes than their own minutes — summed across all contributions. */
private suspend fun sessionBoundsValid(dao: PlaytimeRecoveryDao, timed: List<TimedContributionPlacement>): Boolean {
    val ids = timed.mapNotNull { it.sessionId }.distinct()
    if (ids.isEmpty()) return true
    for (sessionId in ids) {
        val row = dao.sessionRow(sessionId) ?: return false
        if (timed.any { it.sessionId == sessionId && it.appId != row.appId }) return false
    }
    val funded = dao.fundedMinutesBySession(ids).associate { it.sessionId to it.minutes }
    return ids.all { sessionId ->
        val minutes = dao.sessionRow(sessionId)?.minutes ?: return false
        (funded[sessionId] ?: 0L) <= minutes
    }
}
