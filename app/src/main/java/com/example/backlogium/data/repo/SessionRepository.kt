package com.example.backlogium.data.repo

import com.example.backlogium.data.local.dao.SessionDao
import com.example.backlogium.data.local.dao.PlayerProfileDao
import com.example.backlogium.data.local.RecoveryReadStatusStore
import com.example.backlogium.data.local.entity.PlaytimeContribution
import com.example.backlogium.data.local.entity.RecoveredSharedPlayState
import com.example.backlogium.data.local.entity.Session
import com.example.backlogium.data.local.entity.TimedContributionPlacement
import com.example.backlogium.data.local.entity.TimingInformedSteamPlayState
import com.example.backlogium.data.local.entity.UnknownTimeAllocation
import com.example.backlogium.data.local.entity.UnknownTimeAllocationRecipe
import com.example.backlogium.domain.ContributionCredit
import com.example.backlogium.domain.CreditedAllocation
import com.example.backlogium.domain.DatedAllocationCredit
import com.example.backlogium.domain.DatedCreditedTotal
import com.example.backlogium.domain.DatedPlayTotal
import com.example.backlogium.domain.PlaytimeActivity
import com.example.backlogium.domain.PlaytimeAllocationMethod
import com.example.backlogium.domain.PlaytimeReadProjector
import com.example.backlogium.domain.PlaytimeRecoveryStatus
import com.example.backlogium.domain.RecipeCredit
import com.example.backlogium.domain.RecoveryEstimateWindow
import com.example.backlogium.domain.SessionCredit
import com.example.backlogium.domain.TimeProvider
import com.example.backlogium.domain.TimedShareCredit
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A synthesized play session as consumers see it. Timestamps are epoch milliseconds; [open] is
 * true while the session is still being extended by successive polls.
 */
data class PlaySession(
    val id: Long,
    val appId: Long,
    val startAt: Long,
    val minutes: Int,
    val open: Boolean,
    val endAt: Long? = null,
    val cloudContribution: SessionCloudContribution = SessionCloudContribution(),
)

/** A fact is unknown for migrated rows, not evidence of an absence of cloud contribution. */
enum class ContributionState { UNKNOWN, NONE, FULL, PARTIAL }

data class SessionCloudContribution(
    val recoveredSharedPlay: ContributionState = ContributionState.UNKNOWN,
    val timingInformedSteamPlay: ContributionState = ContributionState.UNKNOWN,
)

/**
 * Read access to synthesized play sessions and canonical dated activity.
 *
 * Every flow here excludes hidden games (add-hidden-games). A hidden game's sessions are still
 * *recorded* — hiding destroys nothing, and unhiding has to restore the full history — they are
 * simply not readable through this repository while the game is hidden, so History, Analytics,
 * and the Library's XP badge cannot show or count them.
 *
 * **Canonical quantity boundary** (recover-missed-playtime, design.md Decision 7): [trackedMinutesByGame]
 * returns *credited* minutes — each funded recovery contribution counts once and every session
 * contributes its unlinked legacy remainder — so Library/detail/XP/collections/smart-collections
 * and gap-plan consumers reconcile with recovered buckets without importing a storage type.
 * Recency, session counts and clock-time metrics deliberately stay session-only: [earliestSessionStart],
 * [latestSessionAtByGame], [firstSessionAtByGame], [sessionCountByGame] and [sessionsBetween] never
 * convert an allocation date or observation instant into an exact gameplay instant.
 */
@Singleton
class SessionRepository @Inject constructor(
    private val sessionDao: SessionDao,
    private val hiddenGamesRepository: HiddenGamesRepository,
    private val time: TimeProvider,
    private val playerProfileDao: PlayerProfileDao? = null,
    private val recoveryReadStatusStore: RecoveryReadStatusStore? = null,
) {
    /** Device zone used to derive session-start dates for dated canonical projections. */
    private val zone: ZoneId get() = time.zone()
    /**
     * Sessions starting at or after [cutoffMillis]. Backs the History screen's day-grouped view
     * (regroup-history), which needs every session in a window of calendar days rather than a
     * fixed row count.
     */
    fun sessionsSince(cutoffMillis: Long): Flow<List<PlaySession>> =
        sessionDao.observeSince(cutoffMillis).visibleSessions()

    /** Sessions whose start timestamps fall inside an explicit start-inclusive/end-exclusive window. */
    fun sessionsBetween(startInclusiveMillis: Long, endExclusiveMillis: Long): Flow<List<PlaySession>> =
        sessionDao.observeBetween(startInclusiveMillis, endExclusiveMillis).visibleSessions()

    /**
     * Sessions overlapping an explicit start-inclusive/end-exclusive window: started before
     * it ends and ended after it begins (or still open). For the cloud-presence diagnostics
     * comparison only — Analytics and History attribute by session start and keep
     * [sessionsBetween].
     */
    fun sessionsOverlapping(startInclusiveMillis: Long, endExclusiveMillis: Long): Flow<List<PlaySession>> =
        sessionDao.observeOverlapping(startInclusiveMillis, endExclusiveMillis).visibleSessions()

    /**
     * Earliest visible session start, or null before the first session is recorded. Hidden games
     * are excluded, so an Analytics window can never stretch back over history it cannot show.
     * Session-only by design: period availability that must reach allocation-only history uses
     * [earliestCanonicalActivityDate] instead.
     */
    val earliestSessionStart: Flow<Long?> = sessionDao.observeEarliestVisibleSessionStart()

    /** Closed synthesized sessions used by Personal Pace; open sessions are excluded in Room. */
    fun closedSessionsSince(cutoffMillis: Long): Flow<List<PlaySession>> =
        sessionDao.observeClosedSince(cutoffMillis).visibleSessions()

    /**
     * **Canonical** credited minutes summed per game, keyed by appId (design.md Decision 7). A
     * funded recovery contribution's authorized quantity counts exactly once; each session
     * contributes only its unlinked legacy remainder (minutes minus active funded timed shares),
     * and date-unknown allocations, pending date-unknown quantity and unmaterialized recipe
     * residual are all carried by the contribution totals. Overfunded shares (more funded minutes
     * than a session row holds) are rejected rather than erasing the session's mixed legacy.
     *
     * A family-shared game has no owned contributions, so it keeps exactly its existing session
     * total — shared-session figures are unchanged. Games with no activity are absent rather than
     * zero — a caller deriving XP treats a missing entry as zero credited minutes.
     */
    val trackedMinutesByGame: Flow<Map<Long, Int>> =
        combine(
            sessionDao.observeSince(Long.MIN_VALUE),
            sessionDao.observeActiveRecoveryContributions(),
            sessionDao.observeActiveRecoveryTimedPlacements(),
        ) { sessions, contributions, timedPlacements ->
            PlaytimeReadProjector.creditedMinutesByGame(
                contributions = contributions.map(PlaytimeContribution::toCredit),
                sessions = sessions.map(Session::toCredit),
                timedShares = timedPlacements.map(TimedContributionPlacement::toCredit),
            )
        }
            .map { byAppId ->
                byAppId.mapValues { (_, minutes) ->
                    minutes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                }
            }
            .visibleKeys()

    /** The same canonical per-game totals, as a one-shot read for non-flow consumers. */
    suspend fun creditedMinutesByGameOnce(): Map<Long, Int> {
        val hidden = hiddenGamesRepository.hiddenAppIdSet()
        return sessionDao.creditedMinutesByGame()
            .associate { it.appId to it.minutes }
            .filterKeys { it !in hidden }
    }

    /**
     * Each game's earliest recorded session start, keyed by appId. Games with no session are absent
     * rather than zero: "never had a first session" and "first session at the epoch" are different
     * answers, and the recency derivation must not confuse them. Session-only on purpose — a
     * recovered bucket must not fabricate a first gameplay instant.
     */
    val firstSessionAtByGame: Flow<Map<Long, Long>> = sessionDao.observeFirstSessionStartByGame()
        .map { rows -> rows.associate { it.appId to it.at } }

    /** Most recent tracked session timestamp per game, absent when no session exists. */
    val latestSessionAtByGame: Flow<Map<Long, Long>> = sessionDao.observeLatestSessionInstantByGame()
        .map { rows -> rows.associate { it.appId to it.at } }

    /** Synthesized session count per game, keyed by appId. Buckets never increment these. */
    val sessionCountByGame: Flow<Map<Long, Int>> = sessionDao.observeSessionCountsByGame()
        .map { rows -> rows.associate { it.appId to it.sessions } }
        .visibleKeys()

    /**
     * Tracked minutes summed per game over sessions starting at or after [cutoffMillis], keyed
     * by appId. Feeds the Analytics screen's most-played-games-in-the-window list — distinct
     * from [trackedMinutesByGame] (all-time) the Library's XP badge reads. Remains session-only:
     * the dated-canonical analogue is [datedCreditedTotalsBetween].
     */
    fun minutesByGameSince(cutoffMillis: Long): Flow<Map<Long, Int>> =
        sessionDao.observeMinutesByGameSince(cutoffMillis)
            .map { rows -> rows.associate { it.appId to it.minutes } }
            .visibleKeys()

    /** Tracked minutes per game inside an explicit start-inclusive/end-exclusive window. */
    fun minutesByGameBetween(startInclusiveMillis: Long, endExclusiveMillis: Long): Flow<Map<Long, Int>> =
        sessionDao.observeMinutesByGameBetween(startInclusiveMillis, endExclusiveMillis)
            .map { rows -> rows.associate { it.appId to it.minutes } }
            .visibleKeys()

    /**
     * Earliest canonical dated activity in the device zone: the first session start date, first
     * *positive* dated allocation, first frozen detached-share date or first positive recipe-
     * derived date. Unlike [earliestSessionStart] (an exact session instant) this is a *date*, so
     * History and Analytics can anchor period availability over allocation-only days that precede
     * the first timestamped session. Excluded/zero rows never anchor history. Bounded reads: each
     * source contributes one min row (or the small recipe set), never the whole ledger.
     */
    val earliestCanonicalActivityDate: Flow<LocalDate?> =
        combine(
            sessionDao.observeEarliestVisibleSessionStart(),
            sessionDao.observeEarliestDatedAllocationDate(),
            sessionDao.observeEarliestDetachedShareDate(),
            sessionDao.observeActiveRecoveryRecipes(),
            hiddenGamesRepository.hiddenAppIds,
        ) { earliestSessionMillis, earliestAllocationDate, earliestDetachedDate, recipes, hidden ->
            buildSet {
                earliestSessionMillis?.let { add(PlaytimeReadProjector.sessionDate(it, zone)) }
                earliestAllocationDate?.toLocalDateOrNull()?.let(::add)
                earliestDetachedDate?.toLocalDateOrNull()?.let(::add)
                recipes.filterNot { it.appId in hidden }
                    .mapNotNull { PlaytimeReadProjector.earliestPositiveRecipeDate(it.toCredit()) }
                    .forEach(::add)
            }.minOrNull()
        }

    /**
     * Canonical dated activity for [startDateInclusive, endDateExclusive) evaluated in [zone]:
     * timestamped sessions (with their funded/unlinked split), dated unknown-time allocations,
     * detached timed shares and the unmaterialized tail of long residual recipes. Recipe-derived
     * rows are bounded to the requested slice and combined with materialized rows exactly once.
     * Results are ordered newest date first. Reads are window-scoped: the session query uses the
     * date window (widened a day for DST-safe midnight edges), funded shares are filtered by their
     * frozen credited date, and only the sessions hosting those shares are fetched for validation.
     */
    fun activityBetween(
        zone: ZoneId,
        startDateInclusive: LocalDate,
        endDateExclusive: LocalDate,
    ): Flow<List<PlaytimeActivity>> {
        val fromIso = startDateInclusive.toString()
        val toExclusiveIso = endDateExclusive.toString()
        val startMillis = startDateInclusive.atStartOfDay(zone).toInstant().toEpochMilli() - DAY_MILLIS
        val endMillis = endDateExclusive.atStartOfDay(zone).toInstant().toEpochMilli() + DAY_MILLIS
        return combine(
            combine(
                sessionDao.observeBetween(startMillis, endMillis),
                sessionDao.observeHostSessionsForDates(fromIso, toExclusiveIso),
                sessionDao.observeTimedPlacementsByDate(fromIso, toExclusiveIso),
                sessionDao.observeDatedAllocationsBetween(fromIso, toExclusiveIso),
                sessionDao.observeActiveRecoveryRecipes(),
            ) { windowSessions, hostSessions, placements, allocations, recipes ->
                ActivityInput(windowSessions, hostSessions, placements, allocations, recipes)
            },
            hiddenGamesRepository.hiddenAppIds,
        ) { input, hidden ->
            PlaytimeReadProjector.datedActivityBetween(
                sessions = input.relevantSessions(hidden).map(Session::toCredit),
                timedShares = input.placements.filterNot { it.appId in hidden }
                    .map(TimedContributionPlacement::toCredit),
                allocations = input.allocations.filterNot { it.appId in hidden }
                    .map(UnknownTimeAllocation::toCredit),
                recipes = input.recipes.filterNot { it.appId in hidden }
                    .map(UnknownTimeAllocationRecipe::toCredit),
                zone = zone,
                startDateInclusive = startDateInclusive,
                endDateExclusive = endDateExclusive,
            )
        }
    }

    /** Canonical dated activity from [cutoffDate] onward (inclusive), newest first. */
    fun activitySince(zone: ZoneId, cutoffDate: LocalDate): Flow<List<PlaytimeActivity>> =
        activityBetween(zone, cutoffDate, LocalDate.MAX)

    /**
     * Dated unknown-time allocations over an *inclusive* `[startInclusiveDate, endInclusiveDate]`
     * window, as History (7.1) and Analytics (7.2) render them: materialized rows, detached timed
     * shares and the unmaterialized recipe tail, newest first. Every row carries the identity,
     * frozen zone, residual-window dates/total and credit-time Focus eligibility its `!`
     * explanation needs. Pending date-unknown quantity is excluded by construction.
     */
    fun datedAllocationsBetween(
        startInclusiveDate: LocalDate,
        endInclusiveDate: LocalDate,
    ): Flow<List<CreditedAllocation>> {
        val toExclusiveIso = endInclusiveDate.plusDays(1).toString()
        return combine(
            combine(
                sessionDao.observeDatedAllocationsBetween(startInclusiveDate.toString(), toExclusiveIso),
                sessionDao.observeTimedPlacementsByDate(startInclusiveDate.toString(), toExclusiveIso),
                sessionDao.observeActiveRecoveryRecipes(),
            ) { allocations, timedPlacements, recipes ->
                AllocationInput(allocations, timedPlacements, recipes)
            },
            hiddenGamesRepository.hiddenAppIds,
        ) { input, hidden ->
            PlaytimeReadProjector.creditedAllocationsBetween(
                timedShares = input.timedPlacements.filterNot { it.appId in hidden }
                    .map(TimedContributionPlacement::toCredit),
                allocations = input.allocations.filterNot { it.appId in hidden }
                    .map(UnknownTimeAllocation::toCredit),
                recipes = input.recipes.filterNot { it.appId in hidden }
                    .map(UnknownTimeAllocationRecipe::toCredit),
                startDateInclusive = startInclusiveDate,
                endDateInclusive = endInclusiveDate,
            )
        }
    }

    /**
     * Pending date-unknown quantity (inverted/no-window recovery): eligible for cumulative XP,
     * disclosed separately from dated rows, never a dated quest/chart or habit observation.
     */
    val pendingDateUnknownQuantity: Flow<Int> =
        combine(
            sessionDao.observeActiveRecoveryAllocations(),
            hiddenGamesRepository.hiddenAppIds,
        ) { allocations, hidden ->
            allocations.asSequence()
                .filter { it.pendingDate && it.appId !in hidden }
                .sumOf { it.minutes }
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
        }

    /**
     * Recovery availability status for the honest disclosure vocabulary (task 7.4).
     *
     * Account-scoped durable read outcomes distinguish remote failures, a locally observed play
     * that Steam has not yet published, a successful unchanged read with no known pending play and
     * the default known-good state. Pending date-unknown quantity is surfaced separately via
     * [pendingDateUnknownQuantity] and takes precedence in the History status row.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val recoveryStatus: Flow<PlaytimeRecoveryStatus> = if (
        playerProfileDao == null || recoveryReadStatusStore == null
    ) {
        flowOf(PlaytimeRecoveryStatus.KNOWN_GOOD)
    } else {
        playerProfileDao.observe().flatMapLatest { profile ->
            profile?.steamId?.trim()?.takeIf(String::isNotEmpty)
                ?.let(recoveryReadStatusStore::observe)
                ?: flowOf(PlaytimeRecoveryStatus.KNOWN_GOOD)
        }
    }

    /**
     * Per-date canonical credited totals for a window (design.md Decision 7): each session's
     * unlinked legacy remainder on its own start date, every funded timed share (hosted or
     * detached) on its frozen credited date, dated allocations and recipe-derived residual.
     * Pending date-unknown quantity stays out - it feeds cumulative XP, never dated charts,
     * quests or habit training. Reads are window-scoped: sessions come from the date window
     * (widened a day for DST-safe midnight edges) plus only the hosts of the window's funded
     * shares, and shares/allocations are filtered by their frozen dates.
     */
    fun datedCreditedTotalsBetween(
        zone: ZoneId,
        startDateInclusive: LocalDate,
        endDateExclusive: LocalDate,
    ): Flow<List<DatedCreditedTotal>> {
        val fromIso = startDateInclusive.toString()
        val toExclusiveIso = endDateExclusive.toString()
        val startMillis = startDateInclusive.atStartOfDay(zone).toInstant().toEpochMilli() - DAY_MILLIS
        val endMillis = endDateExclusive.atStartOfDay(zone).toInstant().toEpochMilli() + DAY_MILLIS
        return combine(
            combine(
                sessionDao.observeBetween(startMillis, endMillis),
                sessionDao.observeHostSessionsForDates(fromIso, toExclusiveIso),
                sessionDao.observeTimedPlacementsByDate(fromIso, toExclusiveIso),
                sessionDao.observeDatedAllocationsBetween(fromIso, toExclusiveIso),
                sessionDao.observeActiveRecoveryRecipes(),
            ) { windowSessions, hostSessions, placements, allocations, recipes ->
                ActivityInput(windowSessions, hostSessions, placements, allocations, recipes)
            },
            hiddenGamesRepository.hiddenAppIds,
        ) { input, hidden ->
            PlaytimeReadProjector.datedCreditedTotals(
                sessions = input.relevantSessions(hidden).map(Session::toCredit),
                timedShares = input.placements.filterNot { it.appId in hidden }
                    .map(TimedContributionPlacement::toCredit),
                allocations = input.allocations.filterNot { it.appId in hidden }
                    .map(UnknownTimeAllocation::toCredit),
                recipes = input.recipes.filterNot { it.appId in hidden }
                    .map(UnknownTimeAllocationRecipe::toCredit),
                zone = zone,
                startDateInclusive = startDateInclusive,
                endDateExclusive = endDateExclusive,
            )
        }
    }

    /**
     * Personal Pace training totals over the inclusive [firstDateInclusive, lastDateInclusive]
     * window (tasks 9.1-9.3). Closed sessions contribute their unlinked legacy remainder on their
     * current-zone date; funded timed shares of *closed* sessions (and detached shares) train on
     * their frozen credited dates; dated allocations and the bounded recipe tail add their frozen
     * dates. Open sessions contribute nothing even when a hosted share's frozen date falls inside
     * the window. Masking of unresolved multi-day estimates stays with the caller (56-day horizon).
     */
    fun closedTrainingTotalsBetween(
        zone: ZoneId,
        firstDateInclusive: LocalDate,
        lastDateInclusive: LocalDate,
    ): Flow<List<DatedPlayTotal>> {
        val toExclusiveIso = lastDateInclusive.plusDays(1).toString()
        return combine(
            combine(
                sessionDao.observeClosedSince(firstDateInclusive.atStartOfDay(zone).toInstant().toEpochMilli()),
                sessionDao.observeHostSessionsForDates(firstDateInclusive.toString(), toExclusiveIso),
                sessionDao.observeTimedPlacementsByDate(firstDateInclusive.toString(), toExclusiveIso),
                sessionDao.observeDatedAllocationsBetween(firstDateInclusive.toString(), toExclusiveIso),
                sessionDao.observeActiveRecoveryRecipes(),
            ) { closedSessions, hostSessions, placements, allocations, recipes ->
                TrainingTotalsInput(closedSessions, hostSessions, placements, allocations, recipes)
            },
            hiddenGamesRepository.hiddenAppIds,
        ) { input, hidden ->
            PlaytimeReadProjector.trainingTotalsBetween(
                sessions = input.relevantSessions(hidden).map(Session::toCredit),
                timedShares = input.placements.filterNot { it.appId in hidden }
                    .map(TimedContributionPlacement::toCredit),
                allocations = input.allocations.filterNot { it.appId in hidden }
                    .map(UnknownTimeAllocation::toCredit),
                recipes = input.recipes.filterNot { it.appId in hidden }
                    .map(UnknownTimeAllocationRecipe::toCredit),
                zone = zone,
                firstDateInclusive = firstDateInclusive,
                lastDateInclusive = lastDateInclusive,
            ).entries.sortedBy { it.key }.map { (date, minutes) ->
                DatedPlayTotal(date, minutes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            }
        }
    }

    /**
     * Completed local dates touched by a still-open session, from its start through the bounded
     * horizon end. These dates are unknown, not synthesized zeros, while it remains in progress.
     */
    fun openSessionDatesBetween(
        zone: ZoneId,
        firstDateInclusive: LocalDate,
        lastDateInclusive: LocalDate,
    ): Flow<Set<LocalDate>> {
        return combine(
            sessionDao.observeAllOpenSessions(),
            hiddenGamesRepository.hiddenAppIds,
        ) { rows, hidden ->
            buildSet {
                rows.filterNot { it.appId in hidden }.forEach { session ->
                    val firstUnknown = maxOf(
                        firstDateInclusive,
                        PlaytimeReadProjector.sessionDate(session.startAt, zone),
                    )
                    if (firstUnknown > lastDateInclusive) return@forEach
                    var date = firstUnknown
                    while (!date.isAfter(lastDateInclusive)) {
                        add(date)
                        date = date.plusDays(1)
                    }
                }
            }
        }
    }

    /**
     * All active dated (non-pending) recovery allocations as domain activity, including detached
     * funded timed shares by their frozen credited date. Personal Pace combines these with closed
     * sessions; hidden games are excluded.
     */
    fun datedRecoveryActivity(): Flow<List<PlaytimeActivity.DatedAllocation>> =
        combine(
            sessionDao.observeActiveRecoveryAllocations(),
            sessionDao.observeActiveRecoveryTimedPlacements(),
            hiddenGamesRepository.hiddenAppIds,
        ) { allocations, timedPlacements, hidden ->
            (PlaytimeReadProjector.allocationActivities(
                allocations.map(UnknownTimeAllocation::toCredit),
            ) + PlaytimeReadProjector.detachedTimedActivities(
                timedPlacements.map(TimedContributionPlacement::toCredit),
            ))
                .filterNot { it.appId in hidden }
        }

    /**
     * Unresolved multi-day estimate windows for Personal Pace eligibility (design.md Decision 9).
     * Every window date — including zero-share and mixed dates — is unknown for training unless
     * independently fully resolved; [RecoveryEstimateWindow.unknownDatesBetween] yields the mask
     * bounded to the lookback horizon.
     */
    fun unresolvedRecoveryWindows(): Flow<List<RecoveryEstimateWindow>> =
        combine(
            sessionDao.observeActiveRecoveryRecipes(),
            sessionDao.observeActiveRecoveryAllocations(),
            hiddenGamesRepository.hiddenAppIds,
        ) { recipes, allocations, hidden ->
            val fromRecipes = PlaytimeReadProjector.recoveryEstimateWindows(
                recipes.filterNot { it.appId in hidden }.map(UnknownTimeAllocationRecipe::toCredit),
            )
            // A multi-day estimate materialized without a retained recipe still owns its positive
            // dates; without a recipe those dates are at least masked. When a recipe IS retained it
            // is authoritative (its fully-resolved exclusions restore eligibility), so the same
            // dates are not re-added by their allocation rows.
            // Without a retained recipe the allocation rows still own the *full* observed window
            // (window instants + frozen zone), so zero-share gap dates stay unknown - masking only
            // positive-share dates would synthesize invented zero-play days for the gap. A recipe
            // for the same contribution wins because its fully-resolved exclusions are known.
            val recipeContributions = fromRecipes.map { it.contributionId }.toSet()
            val fromAllocations = PlaytimeReadProjector.allocationEstimateWindows(
                allocations
                    .filterNot { it.appId in hidden }
                    .filter { it.contributionId !in recipeContributions }
                    .map(UnknownTimeAllocation::toCredit),
            )
            fromRecipes + fromAllocations
        }

    private fun Flow<List<Session>>.visibleSessions(): Flow<List<PlaySession>> =
        combine(hiddenGamesRepository.hiddenAppIds) { rows, hidden ->
            rows.asSequence()
                .filterNot { it.appId in hidden }
                .map(Session::toDomain)
                .toList()
        }

    private fun Flow<Map<Long, Int>>.visibleKeys(): Flow<Map<Long, Int>> =
        combine(hiddenGamesRepository.hiddenAppIds) { byAppId, hidden ->
            if (hidden.isEmpty()) byAppId else byAppId.filterKeys { it !in hidden }
        }
}

/** Window-scoped session+share+bucket inputs shared by the dated projections. */
private class ActivityInput(
    val windowSessions: List<Session>,
    val hostSessions: List<Session>,
    val placements: List<TimedContributionPlacement>,
    val allocations: List<UnknownTimeAllocation>,
    val recipes: List<UnknownTimeAllocationRecipe>,
) {
    /** Sessions actually carrying the window's activity: window rows plus host-session rows, deduped. */
    fun relevantSessions(hidden: Set<Long>): List<Session> =
        (windowSessions + hostSessions)
            .filterNot { it.appId in hidden }
            .distinctBy(Session::id)
}

/** Window-scoped allocation-leaf inputs (no sessions are needed to render unknown-time leaves). */
private class AllocationInput(
    val allocations: List<UnknownTimeAllocation>,
    val timedPlacements: List<TimedContributionPlacement>,
    val recipes: List<UnknownTimeAllocationRecipe>,
)

/** Window-scoped closed-session training inputs for Personal Pace. */
private class TrainingTotalsInput(
    val closedSessions: List<Session>,
    val hostSessions: List<Session>,
    val placements: List<TimedContributionPlacement>,
    val allocations: List<UnknownTimeAllocation>,
    val recipes: List<UnknownTimeAllocationRecipe>,
) {
    fun relevantSessions(hidden: Set<Long>): List<Session> =
        (closedSessions + hostSessions)
            .filterNot { it.appId in hidden }
            .distinctBy(Session::id)
}

/** One day of margin around a date window, so DST midnight edges cannot drop a session. */
private const val DAY_MILLIS: Long = 24L * 60 * 60 * 1000

private fun Session.toDomain() = PlaySession(
    id = id,
    appId = appId,
    startAt = startAt,
    minutes = minutes,
    open = open,
    endAt = endAt,
    cloudContribution = SessionCloudContribution(
        recoveredSharedPlay = recoveredSharedPlay.toDomain(),
        timingInformedSteamPlay = timingInformedSteamPlay.toDomain(),
    ),
)

private fun RecoveredSharedPlayState?.toDomain(): ContributionState =
    this?.let { ContributionState.valueOf(it.name) } ?: ContributionState.UNKNOWN

private fun TimingInformedSteamPlayState?.toDomain(): ContributionState =
    this?.let { ContributionState.valueOf(it.name) } ?: ContributionState.UNKNOWN

private fun Session.toCredit() = SessionCredit(id, appId, startAt, minutes, open, endAt)

private fun PlaytimeContribution.toCredit() = ContributionCredit(appId, authorizedMinutes)

private fun TimedContributionPlacement.toCredit() = TimedShareCredit(
    placementId = placementId,
    contributionId = contributionId,
    appId = appId,
    sessionId = sessionId,
    minutes = minutes,
    creditedDate = creditedDate.toLocalDateOrNull(),
    frozenZone = zone,
    evidenceQuality = evidenceQuality,
    goalAtCredit = goalAtCredit,
)

private fun UnknownTimeAllocation.toCredit() = DatedAllocationCredit(
    placementId = placementId,
    contributionId = contributionId,
    appId = appId,
    date = date?.toLocalDateOrNull(),
    minutes = minutes,
    method = method.toAllocationMethod(),
    frozenZone = zone,
    windowStartAt = windowStartAt,
    windowEndAt = windowEndAt,
    pendingDate = pendingDate,
    hiddenAtCredit = hiddenAtCredit,
    goalAtCredit = goalAtCredit,
)

private fun UnknownTimeAllocationRecipe.toCredit(): RecipeCredit {
    val tokens = excludedDatesCsv?.split(',').orEmpty().map(String::trim).filter(String::isNotEmpty)
    val excludedDates = mutableListOf<LocalDate>()
    val excludedRanges = mutableListOf<ClosedRange<LocalDate>>()
    tokens.forEach { token ->
        val separator = token.indexOf("..")
        val start = (if (separator < 0) token else token.substring(0, separator)).toLocalDateOrNull()
            ?: return@forEach
        val end = (if (separator < 0) token else token.substring(separator + 2)).toLocalDateOrNull()
            ?: return@forEach
        if (end.isBefore(start)) return@forEach
        if (separator < 0) excludedDates += start else excludedRanges += start..end
    }
    return RecipeCredit(
        contributionId = contributionId,
        appId = appId,
        method = method.toAllocationMethod(),
        dateStart = dateStart?.toLocalDateOrNull(),
        dateEnd = dateEnd?.toLocalDateOrNull(),
        baseMinutes = quotient ?: 0L,
        remainderCount = remainderCount ?: 0L,
        eligibleCount = eligibleCount,
        fullyResolvedDates = excludedDates,
        materializedThroughDate = materializedThroughDate?.toLocalDateOrNull(),
        pending = pending,
        frozenZone = zone,
        windowStartAt = windowStartAt,
        windowEndAt = windowEndAt,
        goalAtCredit = goalAtCredit,
        fullyResolvedDateRanges = excludedRanges,
    )
}

/** Storage allocation-method strings map into the domain vocabulary used by the projector. */
private fun String.toAllocationMethod(): PlaytimeAllocationMethod = when (this) {
    com.example.backlogium.data.local.entity.RecoveryAllocationMethods.SINGLE_DAY ->
        PlaytimeAllocationMethod.SINGLE_DAY_ESTIMATE
    com.example.backlogium.data.local.entity.RecoveryAllocationMethods.MULTI_DAY_ESTIMATE ->
        PlaytimeAllocationMethod.MULTI_DAY_ESTIMATE
    com.example.backlogium.data.local.entity.RecoveryAllocationMethods.PENDING_DATE_UNKNOWN ->
        PlaytimeAllocationMethod.PENDING_DATE_UNKNOWN
    else -> PlaytimeAllocationMethod.SINGLE_DAY_ESTIMATE
}

private fun String.toLocalDateOrNull(): LocalDate? = runCatching { LocalDate.parse(this) }.getOrNull()
