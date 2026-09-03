package com.example.backlogium.domain

import com.example.backlogium.data.local.dao.AchievementDao
import com.example.backlogium.data.local.dao.DailyProgressDao
import com.example.backlogium.data.local.dao.GameDao
import com.example.backlogium.data.local.dao.HltbDataDao
import com.example.backlogium.data.local.dao.PlayerProfileDao
import com.example.backlogium.data.local.dao.SessionDao
import com.example.backlogium.data.local.entity.PlayerProfile
import com.example.backlogium.gamification.AchievementInput
import com.example.backlogium.gamification.DayInput
import com.example.backlogium.gamification.Gamification
import com.example.backlogium.gamification.GamePlaytimeInput
import com.example.backlogium.gamification.QuestResult
import com.example.backlogium.gamification.RuleConfig
import com.example.backlogium.gamification.XpState
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** The only field a gamification recompute is allowed to change on a progress row. */
data class QuestStatusUpdate(
    val date: String,
    val questMet: Boolean,
)

/**
 * Everything one recompute pass derives, before any of it is written back.
 *
 * Exists so a candidate [RuleConfig] can be evaluated without committing it — the settings
 * confirmation dialog states the concrete before/after, which requires running the real
 * computation rather than approximating it. [GamificationUpdater.persist] writes exactly what
 * this holds, so a previewed result and an applied one can never disagree.
 */
data class GamificationResult(
    val xpState: XpState,
    /** Per-day quest outcomes, including ephemeral unmet entries synthesized for calendar gaps. */
    val questResults: List<QuestResult>,
    val currentStreak: Int,
    /**
     * The high-water longest streak: already floored at the stored value, so this is the
     * number [GamificationUpdater.persist] will write, not the raw per-day computation.
     */
    val longestStreak: Int,
    /** Stored days whose `questMet` differs; raw playtime fields are deliberately absent. */
    val changedDays: List<QuestStatusUpdate>,
    /** The injected local date against which this result was evaluated. */
    val evaluationDate: LocalDate,
)

/**
 * Consumes the pure `:gamification` engine: builds its inputs from Room, then persists the
 * returned XP/level, per-day quest results, and streaks back to Room. Owns none of the rule
 * logic — only the I/O and the injected "today".
 *
 * Two distinct playtime inputs (kept separate, per design):
 * - **XP** is fed per-game tracked `Session.minutes` (only playtime the app tracked) plus any
 *   frozen `Game.backfillMinutes` from an opt-in Steam-history import, plus any family-shared
 *   game's own `Game.manualSharedMinutes` estimate (add-shared-game-playtime-and-filter) — the
 *   two are mutually exclusive per game (a game's source gates which one can ever be nonzero) —
 *   joined with each game's HowLongToBeat completionist length so the engine can taper XP per
 *   game. Because `gameXp` tapers over *cumulative* minutes, feeding `backfill + manual +
 *   tracked` as one total yields the correctly bounded XP with no engine change.
 * - **Goal progress** is fed each game's total `playtimeForever` and is derived in the UI
 *   layer via [com.example.backlogium.gamification.Gamification.goalProgress].
 */
class GamificationUpdater @Inject constructor(
    private val sessionDao: SessionDao,
    private val dailyProgressDao: DailyProgressDao,
    private val playerProfileDao: PlayerProfileDao,
    private val hltbDataDao: HltbDataDao,
    private val achievementDao: AchievementDao,
    private val gameDao: GameDao,
    private val progressMarksStore: ProgressMarksStore = InMemoryProgressMarksStore(),
    /**
     * Serializes [persist] against every other participant in the transition protocol. The default
     * is a private instance, matching [progressMarksStore]'s: usable for tests that ignore progress
     * events, never correct for a caller that shares state with a [ProgressEventRepository]-side
     * consumer, which must be handed the same coordinator instance.
     */
    private val transitionCoordinator: ProgressTransitionCoordinator = ProgressTransitionCoordinator(),
) {

    /**
     * Recompute and persist all derived gamification values. [source] is required so every write
     * declares whether the resulting transition was earned progress or a baseline reset.
     */
    suspend fun recompute(
        today: LocalDate,
        source: RecomputeSource,
        config: RuleConfig = RuleConfig(),
        configVersion: Long = 0L,
    ) {
        persist(compute(today, config), source, configVersion)
    }

    /**
     * Run the full recompute and return the result **without writing anything**. Callers that
     * want the values stored hand the result to [persist]; callers previewing a candidate
     * [config] simply discard it. Progress-event marks are intentionally untouched here.
     */
    suspend fun compute(today: LocalDate, config: RuleConfig = RuleConfig()): GamificationResult {
        // XP/level from each game's cumulative minutes = frozen backfill offset (0 unless the
        // player opted in to importing Steam history) + a family-shared game's manual estimate
        // (0 for an owned game) + tracked session minutes, tapered against that game's HLTB
        // completionist average. Games with no HLTB row resolve to null -> flat fallback. The
        // union covers backfilled/manually-estimated games with no tracked sessions.
        val trackedByGame = sessionDao.trackedMinutesByGame().associate { it.appId to it.minutes }
        val allGames = gameDao.getAll()
        val backfillByGame = allGames.associate { it.appId to it.backfillMinutes }
        val manualByGame = allGames.associate { it.appId to it.manualSharedMinutes }
        val hltbByGame = hltbDataDao.getAllWithDataset().associateBy { it.appId }
        val games = (trackedByGame.keys + backfillByGame.keys + manualByGame.keys)
            .map { appId ->
                appId to (backfillByGame[appId] ?: 0) + (manualByGame[appId] ?: 0) +
                    (trackedByGame[appId] ?: 0)
            }
            .filter { (_, minutes) -> minutes > 0 }
            .map { (appId, minutes) ->
                GamePlaytimeInput(
                    gameId = appId.toString(),
                    minutesPlayed = minutes,
                    completionistAverageMinutes = hltbByGame[appId]?.completionistMinutes,
                )
            }
        // Unlocked achievements, rarity-tiered by their first-unlock snapshot percent (never the
        // live one — see the add-steam-achievements rarity-drift policy). Locked/un-snapshotted
        // achievements are excluded here and would contribute 0 XP anyway.
        val achievements = achievementDao.getAllUnlocked().map { row ->
            AchievementInput(
                id = row.apiName,
                unlocked = row.unlocked,
                globalUnlockPercent = row.snapshotPercent,
            )
        }
        val xpState = Gamification.xp(games, achievements, cfg = config)

        // Recompute each stored day's quest status; collect (don't write) the rows that changed.
        val days = dailyProgressDao.getAllOrdered()
        val daysByDate = days.associateBy { LocalDate.parse(it.date) }
        val changedDays = mutableListOf<QuestStatusUpdate>()
        // The pure engine folds by list order, so the caller must make calendar order explicit. Do
        // not persist these synthesized gaps: they only prevent an offline interval from looking
        // like adjacent met days, while stored rows remain the sole source of changedDays.
        val questResults = daysByDate.keys.minOrNull()?.let { firstDate ->
            val lastDate = maxOf(firstDate, today)
            // One linear pass over the evaluated calendar span; no rows are synthesized before
            // the first stored day or persisted for the missing dates.
            generateSequence(firstDate) { date ->
                date.plusDays(1).takeUnless { it.isAfter(lastDate) }
            }.map { date ->
                val day = daysByDate[date]
                if (day == null) {
                    QuestResult(date = date, met = false)
                } else {
                    val result = Gamification.quest(
                        DayInput(
                            date = date,
                            anyMinutes = day.minutesPlayed,
                            goalMinutes = day.goalMinutesPlayed,
                        ),
                        config,
                    )
                    if (result.met != day.questMet) {
                        changedDays += QuestStatusUpdate(day.date, result.met)
                    }
                    result
                }
            }.toList()
        }.orEmpty()

        // Split at today: the engine only ever sees completed days, never one still in
        // progress. A missing today is represented by the synthesized unmet entry when there is a
        // stored history, and still carries the intact past streak forward.
        val pastDays = questResults.filter { it.date < today }
        val todayResult = questResults.firstOrNull { it.date == today }
        val pastStreak = Gamification.streak(pastDays, config)
        val currentStreak = if (todayResult?.met == true) pastStreak.current + 1 else pastStreak.current
        val computedLongest = maxOf(pastStreak.longest, currentStreak)

        // Longest streak is a high-water mark, not a derivation: a record is a historical fact,
        // and recomputing under a stricter config must not be able to erase one. Flooring here
        // (as well as in `persist`) means a preview reports the number that will actually land.
        val storedLongest = playerProfileDao.get()?.longestStreak ?: 0

        return GamificationResult(
            xpState = xpState,
            questResults = questResults,
            currentStreak = currentStreak,
            longestStreak = maxOf(storedLongest, computedLongest),
            changedDays = changedDays,
            evaluationDate = today,
        )
    }

    /**
     * Write a [compute] result back, then update progress-event delivery state. [source] has no
     * default deliberately: a future derived-value writer cannot compile without declaring why
     * the values changed.
     *
     * The whole protocol — resolve prior pending transition, capture previous state, write the
     * pending-transition write-ahead record, perform the Room writes, finalize the marks, clear the
     * record — runs inside [transitionCoordinator], so a second `persist()` cannot enter it until
     * the first has finalized and a recovery pass cannot mistake a live call's write-ahead record
     * for an abandoned one. The phases are individually atomic but jointly ordered; interleaving two
     * of them is what would let one provenance's recovery state be cleared or claimed by the other.
     */
    suspend fun persist(
        result: GamificationResult,
        source: RecomputeSource,
        configVersion: Long = 0L,
    ) {
        transitionCoordinator.withTransition {
            persistWithinProtocol(result, source, configVersion)
        }
    }

    /**
     * The protocol body, run with the coordinator held.
     *
     * The previous state is captured and durably recorded (as a [PendingTransition]) *before* the
     * Room write below, and only cleared after the marks finalize succeeds. Once the Room write
     * lands, the pre-write profile is gone from Room; the pending-transition record is what lets a
     * crash between the two be resolved correctly — as a real transition if the Room write
     * happened, as a no-op if it didn't — rather than either fabricating an event that was never
     * earned or losing one that was (see [resolvePendingTransition]).
     */
    private suspend fun persistWithinProtocol(
        result: GamificationResult,
        source: RecomputeSource,
        configVersion: Long,
    ) {
        val today = result.evaluationDate

        // Resolve any transition left dangling by a prior crashed persist() before starting a new
        // one — otherwise this call's own previous-state read could observe a Room profile that
        // already reflects an unresolved earlier write. The within-protocol variant: this call
        // already owns the coordinator, which is not reentrant.
        resolvePendingTransitionWithinProtocol(progressMarksStore, playerProfileDao, dailyProgressDao)

        val previousProfile = playerProfileDao.get()
        val previousTodayQuestMet = dailyProgressDao.getByDate(today.toString())?.questMet == true
        val previousState = previousProfile?.let {
            ProgressState(
                level = it.level,
                currentStreak = it.currentStreak,
                todayQuestMet = previousTodayQuestMet,
            )
        }

        if (previousState != null) {
            progressMarksStore.update { marks ->
                marks.copy(
                    pendingTransition = PendingTransition(
                        source = source,
                        previousLevel = previousState.level,
                        previousStreak = previousState.currentStreak,
                        previousTodayQuestMet = previousState.todayQuestMet,
                        evaluationDate = today,
                    ),
                )
            }
        }

        try {
            writeAndFinalize(result, source, previousState, previousProfile, today, configVersion)
        } catch (t: Throwable) {
            // A pending transition suppresses event derivation by design, so returning from this
            // call with our own record still in place would freeze delivery for the rest of the
            // process — the WAL exists to survive process death, not to outlive a caught failure.
            // Resolve it against whatever Room actually committed, then let the failure propagate.
            withContext(NonCancellable) {
                runCatching {
                    resolvePendingTransitionWithinProtocol(
                        progressMarksStore,
                        playerProfileDao,
                        dailyProgressDao,
                    )
                }
            }
            throw t
        }
    }

    /** The Room half of the protocol plus its marks finalize; see [persistWithinProtocol]. */
    private suspend fun writeAndFinalize(
        result: GamificationResult,
        source: RecomputeSource,
        previousState: ProgressState?,
        previousProfile: PlayerProfile?,
        today: LocalDate,
        configVersion: Long,
    ) {
        result.changedDays.forEach { dailyProgressDao.updateQuestMet(it.date, it.questMet) }

        // Persist profile aggregates, preserving sync/status fields. `currentStreak` is written
        // as computed — only the record is protected — while `longestStreak` takes the maximum
        // against whatever is stored now, so a concurrent write between compute and persist
        // still cannot lower it.
        val profile = previousProfile ?: PlayerProfile()
        if (previousProfile == null) {
            // A first recompute needs a row, but creation must not replace a row another writer
            // inserted while this result was being computed. Subsequent writes remain scoped.
            playerProfileDao.insertIfMissing()
        }
        val updatedProfile = profile.copy(
            totalXp = result.xpState.totalXp,
            level = result.xpState.level,
            currentStreak = result.currentStreak,
            longestStreak = maxOf(profile.longestStreak, result.longestStreak),
            gamificationConfigVersion = configVersion,
        )
        playerProfileDao.updateGamification(
            totalXp = updatedProfile.totalXp,
            level = updatedProfile.level,
            currentStreak = updatedProfile.currentStreak,
            longestStreak = updatedProfile.longestStreak,
            gamificationConfigVersion = configVersion,
        )

        // Event evaluation is deliberately after the successful Room write. `compute()` never
        // touches marks, while non-earned sources reseed them to the values just persisted. The
        // transform always reads the atomic update's *live* parameter, never `previousState`'s
        // enclosing marks snapshot, so a concurrent acknowledge() can never be clobbered by a
        // finalize computed against a value it has already superseded.
        val currentTodayQuestMet = result.questResults
            .firstOrNull { it.date == today }
            ?.met == true
        val currentState = ProgressState(
            level = updatedProfile.level,
            currentStreak = updatedProfile.currentStreak,
            todayQuestMet = currentTodayQuestMet,
        )
        progressMarksStore.update { marks ->
            ProgressEventDetector.detect(
                marks = marks,
                previous = previousState,
                current = currentState,
                source = source,
                today = today,
            ).marks.copy(pendingTransition = null)
        }
    }
}
