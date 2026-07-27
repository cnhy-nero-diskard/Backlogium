package com.example.backlogium.domain

import com.example.backlogium.data.local.dao.AchievementDao
import com.example.backlogium.data.local.dao.DailyProgressDao
import com.example.backlogium.data.local.dao.GameDao
import com.example.backlogium.data.local.dao.HltbDataDao
import com.example.backlogium.data.local.dao.PlayerProfileDao
import com.example.backlogium.data.local.dao.SessionDao
import com.example.backlogium.data.local.entity.DailyProgress
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
    /** Per-day quest outcomes for every stored day, oldest first. */
    val questResults: List<QuestResult>,
    val currentStreak: Int,
    /**
     * The high-water longest streak: already floored at the stored value, so this is the
     * number [GamificationUpdater.persist] will write, not the raw per-day computation.
     */
    val longestStreak: Int,
    /** Stored days whose `questMet` differs from the recomputed value; the only rows to write. */
    val changedDays: List<DailyProgress>,
)

/**
 * Consumes the pure `:gamification` engine: builds its inputs from Room, then persists the
 * returned XP/level, per-day quest results, and streaks back to Room. Owns none of the rule
 * logic — only the I/O and the injected "today".
 *
 * Two distinct playtime inputs (kept separate, per design):
 * - **XP** is fed per-game tracked `Session.minutes` (only playtime the app tracked) plus any
 *   frozen `Game.backfillMinutes` from an opt-in Steam-history import, joined with each game's
 *   HowLongToBeat completionist length so the engine can taper XP per game. Because `gameXp`
 *   tapers over *cumulative* minutes, feeding `backfill + tracked` as one total yields the
 *   correctly bounded XP with no engine change.
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
) {

    /**
     * Recompute and persist all derived gamification values. Called on each sync and on day
     * rollover. [today] is injected so the engine stays clock-free; [config] carries the
     * tunable rules.
     */
    suspend fun recompute(today: LocalDate, config: RuleConfig = RuleConfig()) {
        persist(compute(today, config))
    }

    /**
     * Run the full recompute and return the result **without writing anything**. Callers that
     * want the values stored hand the result to [persist]; callers previewing a candidate
     * [config] simply discard it.
     */
    suspend fun compute(today: LocalDate, config: RuleConfig = RuleConfig()): GamificationResult {
        // XP/level from each game's cumulative minutes = frozen backfill offset (0 unless the
        // player opted in to importing Steam history) + tracked session minutes, tapered
        // against that game's HLTB completionist average. Games with no HLTB row resolve to
        // null -> flat fallback. The union covers backfilled games with no tracked sessions.
        val trackedByGame = sessionDao.trackedMinutesByGame().associate { it.appId to it.minutes }
        val backfillByGame = gameDao.getAll().associate { it.appId to it.backfillMinutes }
        val games = (trackedByGame.keys + backfillByGame.keys)
            .map { appId -> appId to (backfillByGame[appId] ?: 0) + (trackedByGame[appId] ?: 0) }
            .filter { (_, minutes) -> minutes > 0 }
            .map { (appId, minutes) ->
                GamePlaytimeInput(
                    gameId = appId.toString(),
                    minutesPlayed = minutes,
                    completionistAverageMinutes = hltbDataDao.getByAppId(appId)?.completionistMinutes,
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
        val changedDays = mutableListOf<DailyProgress>()
        val questResults = days.map { day ->
            val result = Gamification.quest(
                DayInput(
                    date = LocalDate.parse(day.date),
                    anyMinutes = day.minutesPlayed,
                    goalMinutes = day.goalMinutesPlayed,
                ),
                config,
            )
            if (result.met != day.questMet) {
                changedDays += day.copy(questMet = result.met)
            }
            result
        }

        // Split at today: the engine only ever sees completed days, never one still in
        // progress. Assumes at most one `DailyProgress` row per date (true today: one row
        // per date, upserted), so at most one entry can match `date == today`.
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
        )
    }

    /** Write a [compute] result back: the changed quest days, then the profile aggregates. */
    suspend fun persist(result: GamificationResult) {
        result.changedDays.forEach { dailyProgressDao.upsert(it) }

        // Persist profile aggregates, preserving sync/status fields. `currentStreak` is written
        // as computed — only the record is protected — while `longestStreak` takes the maximum
        // against whatever is stored now, so a concurrent write between compute and persist
        // still cannot lower it.
        val profile = playerProfileDao.get() ?: PlayerProfile()
        playerProfileDao.upsert(
            profile.copy(
                totalXp = result.xpState.totalXp,
                level = result.xpState.level,
                currentStreak = result.currentStreak,
                longestStreak = maxOf(profile.longestStreak, result.longestStreak),
            ),
        )
    }
}
