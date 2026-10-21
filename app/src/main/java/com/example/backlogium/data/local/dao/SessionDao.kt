package com.example.backlogium.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.example.backlogium.data.local.entity.PlaytimeContribution
import com.example.backlogium.data.local.entity.RecoveredSharedPlayState
import com.example.backlogium.data.local.entity.Session
import com.example.backlogium.data.local.entity.TimedContributionPlacement
import com.example.backlogium.data.local.entity.TimingInformedSteamPlayState
import com.example.backlogium.data.local.entity.UnknownTimeAllocation
import com.example.backlogium.data.local.entity.UnknownTimeAllocationRecipe
import com.example.backlogium.domain.ContributionCredit
import com.example.backlogium.domain.PlaytimeReadProjector
import com.example.backlogium.domain.SessionCredit
import com.example.backlogium.domain.TimedShareCredit
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {

    @Insert
    suspend fun insert(session: Session): Long

    @Update
    suspend fun update(session: Session)

    /**
     * Update a session unless it would violate the unique open-session key.
     *
     * Used when applying an Extend that may have been derived before the target session was
     * closed. If a newer session for the same game is already open, SQLite leaves this row
     * unchanged instead of aborting the write.
     *
     * @return the number of rows updated; zero means no row was updated, including a conflict.
     */
    @Update(onConflict = OnConflictStrategy.IGNORE)
    suspend fun updateUnlessConflictingOpenSession(session: Session): Int

    /**
     * Opens a session for [appId] only when no open session already exists.
     *
     * This is the enforcement point for "at most one open session per game" (auditfix-session-
     * ledger-integrity, #116). A unique nullable key makes the insert itself atomic; a competing
     * insert is ignored and the caller can fold its observation into the session that won via
     * [getOpenSession], rather than losing it.
     *
     * Deliberately leaves the `(appId, startAt, endAt)` natural key non-unique: the backup/restore
     * merge engine must tolerate a real-world collision among closed sessions. Closed rows have a
     * null open key, so the unique index enforces only the open-session invariant.
     *
     * @return the new row's id if this call opened the session, or -1 if a concurrent caller
     *   already holds one open and the unique open key rejected this insert.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOpenSessionIfAbsent(session: Session): Long

    suspend fun tryOpenSession(
        appId: Long,
        startAt: Long,
        endAt: Long?,
        minutes: Int,
        recoveredSharedPlay: RecoveredSharedPlayState = RecoveredSharedPlayState.NONE,
        timingInformedSteamPlay: TimingInformedSteamPlayState = TimingInformedSteamPlayState.NONE,
    ): Long = insertOpenSessionIfAbsent(
        Session(
            appId = appId,
            startAt = startAt,
            endAt = endAt,
            minutes = minutes,
            open = true,
            recoveredSharedPlay = recoveredSharedPlay,
            timingInformedSteamPlay = timingInformedSteamPlay,
        ),
    )

    @Query("SELECT * FROM sessions WHERE appId = :appId AND open = 1 LIMIT 1")
    suspend fun getOpenSession(appId: Long): Session?

    /**
     * All currently-open sessions, in a single query. Used when reconstructing per-game diff
     * state for a whole-library poll; associating by `appId` in memory avoids an N+1 query
     * against the session table (optimize-steam-sync).
     */
    @Query("SELECT * FROM sessions WHERE open = 1")
    suspend fun getAllOpenSessions(): List<Session>

    /**
     * Sessions starting at or after [cutoff] (epoch millis), for a date-ranged window rather than
     * a fixed row count — the History screen's day-grouped view (regroup-history) needs every
     * session in a window of calendar days, which a row cap cannot guarantee.
     */
    @Query("SELECT * FROM sessions WHERE startAt >= :cutoff ORDER BY startAt DESC")
    fun observeSince(cutoff: Long): Flow<List<Session>>

    /** Sessions whose start date falls within a complete local-day window. */
    @Query(
        "SELECT * FROM sessions WHERE startAt >= :startInclusive AND startAt < :endExclusive " +
            "ORDER BY startAt DESC",
    )
    fun observeBetween(startInclusive: Long, endExclusive: Long): Flow<List<Session>>

    @Query(
        "SELECT * FROM sessions WHERE startAt >= :startInclusive AND startAt < :endExclusive " +
            "ORDER BY startAt DESC",
    )
    suspend fun sessionsBetweenSnapshot(startInclusive: Long, endExclusive: Long): List<Session>

    /**
     * Sessions overlapping a half-open window: started before it ends and ended after it
     * begins (or still open with no end recorded). Backs the cloud-presence diagnostics
     * comparison, where a local session can be wider than the cloud window that motivated
     * looking at it — after an offline stretch every game touched inherits the whole
     * stretch as its recorded span, so a start-inside-window filter drops the very
     * sessions the comparison must show.
     */
    @Query(
        "SELECT * FROM sessions WHERE startAt < :endExclusive " +
            "AND (endAt IS NULL OR endAt > :startInclusive) ORDER BY startAt DESC",
    )
    fun observeOverlapping(startInclusive: Long, endExclusive: Long): Flow<List<Session>>

    /** Closed synthesized sessions starting at or after [cutoff], for Personal Pace training. */
    @Query(
        "SELECT * FROM sessions WHERE startAt >= :cutoff AND open = 0 ORDER BY startAt DESC",
    )
    fun observeClosedSince(cutoff: Long): Flow<List<Session>>

    @Query("SELECT * FROM sessions WHERE startAt >= :cutoff AND open = 0 ORDER BY startAt DESC")
    suspend fun closedSessionsSinceSnapshot(cutoff: Long): List<Session>

    /** All currently open rows; history before the current lookback can still span into it. */
    @Query("SELECT * FROM sessions WHERE open = 1")
    fun observeAllOpenSessions(): Flow<List<Session>>

    @Query("SELECT * FROM sessions WHERE open = 1")
    suspend fun allOpenSessionsSnapshot(): List<Session>

    @Query("SELECT * FROM sessions ORDER BY startAt ASC")
    suspend fun getAll(): List<Session>

    /** Targeted host-session lookup for recovery backup projection; callers chunk below SQLite's bind limit. */
    @Query("SELECT * FROM sessions WHERE id IN (:sessionIds)")
    suspend fun sessionsByIds(sessionIds: List<Long>): List<Session>

    /**
     * One invalidation stream for canonical recovery projections. The scalar result is merely a
     * trigger: Room tracks every referenced table and emits after any committed change, even when
     * row counts stay constant. Consumers then re-read their bounded input bundle in one Room
     * transaction instead of combining independently invalidated snapshots.
     */
    @Query(
        "SELECT " +
            "(SELECT COUNT(*) FROM sessions) + " +
            "(SELECT COUNT(*) FROM playtime_contributions) + " +
            "(SELECT COUNT(*) FROM recovery_timed_placements) + " +
            "(SELECT COUNT(*) FROM recovery_unknown_allocations) + " +
            "(SELECT COUNT(*) FROM recovery_allocation_recipes) + " +
            "(SELECT COUNT(*) FROM hidden_games) + " +
            "(SELECT COUNT(*) FROM player_profile)",
    )
    fun observeRecoveryProjectionInvalidation(): Flow<Long>

    /** Hidden rows are read under the same transaction as the recovery projection bundle. */
    @Query("SELECT appId FROM hidden_games")
    suspend fun hiddenAppIdsSnapshot(): List<Long>

    @Query("DELETE FROM sessions")
    suspend fun deleteAll()

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** Earliest tracked session start, used to keep Analytics anchors inside available history. */
    @Query("SELECT MIN(startAt) FROM sessions")
    fun observeEarliestSessionStart(): Flow<Long?>

    /**
     * Earliest session start among games the player can see. Hidden games contribute to no figure
     * Analytics presents, so they must not stretch its history anchor either (add-hidden-games).
     * Room re-emits when `hidden_games` changes, because the subquery names that table.
     */
    @Query("SELECT MIN(startAt) FROM sessions WHERE appId NOT IN (SELECT appId FROM hidden_games)")
    fun observeEarliestVisibleSessionStart(): Flow<Long?>

    @Query("SELECT MIN(startAt) FROM sessions WHERE appId NOT IN (SELECT appId FROM hidden_games)")
    suspend fun earliestVisibleSessionStartSnapshot(): Long?

    /**
     * Natural-key lookup for the backup/restore merge engine (add-backup-restore): [Session.id]
     * is a surrogate autoincrement that does not survive an export/import round trip, so a
     * merge must find a session by `(appId, startAt, endAt)` instead. `endAt IS :endAt` (not
     * `=`) so a null [endAt] matches other open sessions rather than matching nothing.
     */
    @Query(
        "SELECT * FROM sessions WHERE appId = :appId AND startAt = :startAt AND endAt IS :endAt " +
            "LIMIT 1",
    )
    suspend fun findByNaturalKey(appId: Long, startAt: Long, endAt: Long?): Session?

    /**
     * Tracked minutes summed per game. The gamification engine tapers XP against each game's
     * own completionist length, so it needs the per-`appId` breakdown rather than a single
     * library-wide total.
     */
    @Query("SELECT appId, COALESCE(SUM(minutes), 0) AS minutes FROM sessions GROUP BY appId")
    suspend fun trackedMinutesByGame(): List<GameTrackedMinutes>

    /**
     * The same per-game breakdown, observed. The Library's XP badge is derived from the engine's
     * own inputs (tracked + backfill minutes), so it has to follow tracked minutes live rather
     * than read them once.
     */
    @Query("SELECT appId, COALESCE(SUM(minutes), 0) AS minutes FROM sessions GROUP BY appId")
    fun observeTrackedMinutesByGame(): Flow<List<GameTrackedMinutes>>

    /** Number of synthesized sessions per game, used by collection overviews. */
    @Query("SELECT appId, COUNT(*) AS sessions FROM sessions GROUP BY appId")
    fun observeSessionCountsByGame(): Flow<List<GameSessionCounts>>

    /**
     * Tracked minutes summed per game over sessions starting at or after [cutoff] (epoch millis).
     * Feeds the Analytics screen's most-played-games-in-the-window list, which is distinct from
     * the all-time [observeTrackedMinutesByGame] the Library's XP badge uses.
     */
    @Query(
        "SELECT appId, COALESCE(SUM(minutes), 0) AS minutes FROM sessions " +
            "WHERE startAt >= :cutoff GROUP BY appId",
    )
    fun observeMinutesByGameSince(cutoff: Long): Flow<List<GameTrackedMinutes>>

    /** Tracked minutes summed per game inside an explicit start-inclusive/end-exclusive window. */
    @Query(
        "SELECT appId, COALESCE(SUM(minutes), 0) AS minutes FROM sessions " +
            "WHERE startAt >= :startInclusive AND startAt < :endExclusive GROUP BY appId",
    )
    fun observeMinutesByGameBetween(startInclusive: Long, endExclusive: Long): Flow<List<GameTrackedMinutes>>

    /**
     * Each game's earliest recorded session start — the input that makes the newly-played recency
     * state fire once per game, for life (add-library-recency-signals).
     *
     * One grouped query for the whole library rather than a lookup per row: the Library derives a
     * state for every game it renders, so a per-game query would turn one scan into hundreds.
     */
    @Query("SELECT appId, MIN(startAt) AS at FROM sessions GROUP BY appId")
    fun observeFirstSessionStartByGame(): Flow<List<GameSessionInstant>>

    /**
     * Each game's most recent recorded play, observed. Feeds source-aware detail recency and the
     * derived collections' idle test, where it stands in for Steam's own last-played stamp on a
     * game Steam reports none for.
     */
    @Query("SELECT appId, MAX(COALESCE(endAt, startAt)) AS at FROM sessions GROUP BY appId")
    fun observeLatestSessionInstantByGame(): Flow<List<GameSessionInstant>>

    /**
     * The same most recent recorded play, as `endAt` where the session has one and `startAt` where
     * it is still open.
     *
     * Read by a poll *before* it overwrites `lastPlayedAt`, as one half of the previously-known
     * last-play time the dormancy evaluation compares against. Grouped for the same reason as
     * above: a poll examines the whole library.
     */
    @Query("SELECT appId, MAX(COALESCE(endAt, startAt)) AS at FROM sessions GROUP BY appId")
    suspend fun latestSessionInstantByGame(): List<GameSessionInstant>

    // ------------------------------------------------------------------ canonical recovery reads
    //
    // Recovery storage tables are owned by PlaytimeRecoveryDao's write surface; these are the
    // read-model projections the canonical repository boundary needs (recover-missed-playtime,
    // design.md Decision 7). Each is scoped to the active account from the singleton profile, and
    // Room re-emits when `player_profile` changes so an account switch retargets every flow.

    /** Active (non-superseded) unknown-time allocations for the active account. */
    @Query(
        "SELECT * FROM recovery_unknown_allocations " +
            "WHERE account = (SELECT steamId FROM player_profile WHERE id = 0) AND superseded = 0",
    )
    fun observeActiveRecoveryAllocations(): Flow<List<UnknownTimeAllocation>>

    @Query(
        "SELECT * FROM recovery_unknown_allocations " +
            "WHERE account = (SELECT steamId FROM player_profile WHERE id = 0) AND superseded = 0",
    )
    suspend fun activeRecoveryAllocationsSnapshot(): List<UnknownTimeAllocation>

    /** Active (non-superseded) funded timed shares for the active account. */
    @Query(
        "SELECT * FROM recovery_timed_placements " +
            "WHERE account = (SELECT steamId FROM player_profile WHERE id = 0) AND superseded = 0",
    )
    fun observeActiveRecoveryTimedPlacements(): Flow<List<TimedContributionPlacement>>

    @Query(
        "SELECT * FROM recovery_timed_placements " +
            "WHERE account = (SELECT steamId FROM player_profile WHERE id = 0) AND superseded = 0",
    )
    suspend fun activeRecoveryTimedPlacementsSnapshot(): List<TimedContributionPlacement>

    // ---- bounded window scans (recover-missed-playtime Decision 7) -------------------------
    // Windowed projections must not load the whole history or the whole recovery ledger: date-keyed
    // reads below scope every dated row to the asked window, and host-session reads fetch only the
    // sessions that actually hold a funded share inside the window.

    /** Dated (non-pending) allocation rows whose frozen date is inside the half-open window. */
    @Query(
        "SELECT * FROM recovery_unknown_allocations " +
            "WHERE account = (SELECT steamId FROM player_profile WHERE id = 0) " +
            "AND superseded = 0 AND pendingDate = 0 AND date >= :fromDate AND date < :toDateExclusive",
    )
    fun observeDatedAllocationsBetween(fromDate: String, toDateExclusive: String): Flow<List<UnknownTimeAllocation>>

    @Query(
        "SELECT * FROM recovery_unknown_allocations " +
            "WHERE account = (SELECT steamId FROM player_profile WHERE id = 0) " +
            "AND superseded = 0 AND pendingDate = 0 AND date >= :fromDate AND date < :toDateExclusive",
    )
    suspend fun datedAllocationsBetweenSnapshot(
        fromDate: String,
        toDateExclusive: String,
    ): List<UnknownTimeAllocation>

    /** Funded timed shares whose frozen credited date is inside the half-open window. */
    @Query(
        "SELECT * FROM recovery_timed_placements " +
            "WHERE account = (SELECT steamId FROM player_profile WHERE id = 0) " +
            "AND superseded = 0 AND creditedDate >= :fromDate AND creditedDate < :toDateExclusive",
    )
    fun observeTimedPlacementsByDate(fromDate: String, toDateExclusive: String): Flow<List<TimedContributionPlacement>>

    @Query(
        "SELECT * FROM recovery_timed_placements " +
            "WHERE account = (SELECT steamId FROM player_profile WHERE id = 0) " +
            "AND superseded = 0 AND creditedDate >= :fromDate AND creditedDate < :toDateExclusive",
    )
    suspend fun timedPlacementsByDateSnapshot(
        fromDate: String,
        toDateExclusive: String,
    ): List<TimedContributionPlacement>

    /** The sessions hosting a funded share frozen inside the window — bounded targeted validation. */
    @Query(
        "SELECT * FROM sessions WHERE id IN (" +
            "SELECT sessionId FROM recovery_timed_placements " +
            "WHERE account = (SELECT steamId FROM player_profile WHERE id = 0) " +
            "AND superseded = 0 AND sessionId IS NOT NULL " +
            "AND creditedDate >= :fromDate AND creditedDate < :toDateExclusive)",
    )
    fun observeHostSessionsForDates(fromDate: String, toDateExclusive: String): Flow<List<Session>>

    @Query(
        "SELECT * FROM sessions WHERE id IN (" +
            "SELECT sessionId FROM recovery_timed_placements " +
            "WHERE account = (SELECT steamId FROM player_profile WHERE id = 0) " +
            "AND superseded = 0 AND sessionId IS NOT NULL " +
            "AND creditedDate >= :fromDate AND creditedDate < :toDateExclusive)",
    )
    suspend fun hostSessionsForDatesSnapshot(
        fromDate: String,
        toDateExclusive: String,
    ): List<Session>

    /** All active funded shares hosted by the selected sessions, regardless of frozen date. */
    @Query(
        "SELECT * FROM recovery_timed_placements " +
            "WHERE account = (SELECT steamId FROM player_profile WHERE id = 0) " +
            "AND superseded = 0 AND sessionId IN (:sessionIds)",
    )
    fun observeActiveTimedPlacementsForSessions(sessionIds: List<Long>): Flow<List<TimedContributionPlacement>>

    @Query(
        "SELECT * FROM recovery_timed_placements " +
            "WHERE account = (SELECT steamId FROM player_profile WHERE id = 0) " +
            "AND superseded = 0 AND sessionId IN (:sessionIds)",
    )
    suspend fun activeTimedPlacementsForSessionsSnapshot(sessionIds: List<Long>): List<TimedContributionPlacement>

    /** Earliest dated allocation date among visible games; the canonical-history anchor input. */
    @Query(
        "SELECT MIN(date) FROM recovery_unknown_allocations " +
            "WHERE account = (SELECT steamId FROM player_profile WHERE id = 0) " +
            "AND superseded = 0 AND pendingDate = 0 " +
            "AND appId NOT IN (SELECT appId FROM hidden_games)",
    )
    fun observeEarliestDatedAllocationDate(): Flow<String?>

    @Query(
        "SELECT MIN(date) FROM recovery_unknown_allocations " +
            "WHERE account = (SELECT steamId FROM player_profile WHERE id = 0) " +
            "AND superseded = 0 AND pendingDate = 0 " +
            "AND appId NOT IN (SELECT appId FROM hidden_games)",
    )
    suspend fun earliestDatedAllocationDateSnapshot(): String?

    /** Earliest funded-share frozen date (hosted or detached) among visible games; history anchor. */
    @Query(
        "SELECT MIN(creditedDate) FROM recovery_timed_placements " +
            "WHERE account = (SELECT steamId FROM player_profile WHERE id = 0) " +
            "AND superseded = 0 " +
            "AND appId NOT IN (SELECT appId FROM hidden_games)",
    )
    fun observeEarliestTimedShareDate(): Flow<String?>

    @Query(
        "SELECT MIN(creditedDate) FROM recovery_timed_placements " +
            "WHERE account = (SELECT steamId FROM player_profile WHERE id = 0) " +
            "AND superseded = 0 AND appId NOT IN (SELECT appId FROM hidden_games)",
    )
    suspend fun earliestTimedShareDateSnapshot(): String?

    /** Current-revision allocation recipes for the active account. */
    @Query(
        "SELECT * FROM recovery_allocation_recipes " +
            "WHERE account = (SELECT steamId FROM player_profile WHERE id = 0)",
    )
    fun observeActiveRecoveryRecipes(): Flow<List<UnknownTimeAllocationRecipe>>

    @Query(
        "SELECT * FROM recovery_allocation_recipes " +
            "WHERE account = (SELECT steamId FROM player_profile WHERE id = 0)",
    )
    suspend fun activeRecoveryRecipesSnapshot(): List<UnknownTimeAllocationRecipe>

    /** Funded contributions for the active account. */
    @Query(
        "SELECT * FROM playtime_contributions " +
            "WHERE account = (SELECT steamId FROM player_profile WHERE id = 0)",
    )
    fun observeActiveRecoveryContributions(): Flow<List<PlaytimeContribution>>

    @Query(
        "SELECT * FROM playtime_contributions " +
            "WHERE account = (SELECT steamId FROM player_profile WHERE id = 0)",
    )
    suspend fun activeRecoveryContributionsSnapshot(): List<PlaytimeContribution>

    /**
     * Canonical per-game credited minutes for the active account (design.md Decision 7): each
     * funded contribution counts once and each session contributes its unlinked legacy remainder
     * (session minutes minus its active funded timed shares). Pending date-unknown quantity is
     * included — it feeds cumulative XP. An overfunded mapping (funded shares exceeding a
     * session's minutes) is rejected, never silently erased.
     *
     * This is the shared quantity read for `GamificationUpdater.compute` and the frozen
     * lifetime-backfill subtraction; it supersedes raw session sums so a recovery bucket is never
     * double-counted against a manual estimate or lifetime offset.
     */
    @Transaction
    suspend fun creditedMinutesByGame(): List<GameTrackedMinutes> {
        val creditedByGame = PlaytimeReadProjector.creditedMinutesByGame(
            contributions = activeRecoveryContributionsSnapshot().map {
                ContributionCredit(it.appId, it.authorizedMinutes)
            },
            sessions = getAll().map {
                SessionCredit(it.id, it.appId, it.startAt, it.minutes, it.open, it.endAt)
            },
            timedShares = activeRecoveryTimedPlacementsSnapshot().map {
                TimedShareCredit(
                    placementId = it.placementId,
                    contributionId = it.contributionId,
                    appId = it.appId,
                    sessionId = it.sessionId,
                    minutes = it.minutes,
                    creditedDate = it.creditedDate.toLocalDateOrNull(),
                    frozenZone = it.zone,
                    goalAtCredit = it.goalAtCredit,
                )
            },
        )
        return creditedByGame.map { (appId, minutes) ->
            GameTrackedMinutes(appId, minutes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        }
    }
}

/** Per-game tracked-minutes projection for [SessionDao.trackedMinutesByGame]. */
data class GameTrackedMinutes(val appId: Long, val minutes: Int)

/**
 * Per-game single-instant projection: one timestamp per game, whose meaning is the query's
 * ([SessionDao.observeFirstSessionStartByGame] the earliest start,
 * [SessionDao.latestSessionInstantByGame] the most recent play).
 */
data class GameSessionInstant(val appId: Long, val at: Long)

/** Per-game session-count projection for collection overview metrics. */
data class GameSessionCounts(val appId: Long, val sessions: Int)

private fun String.toLocalDateOrNull(): java.time.LocalDate? =
    runCatching { java.time.LocalDate.parse(this) }.getOrNull()
