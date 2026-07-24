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
import com.example.backlogium.gamification.RuleConfig
import java.time.LocalDate
import javax.inject.Inject

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

        // Recompute each stored day's quest status and persist any change.
        val days = dailyProgressDao.getAllOrdered()
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
                dailyProgressDao.upsert(day.copy(questMet = result.met))
            }
            result
        }

        // Streak over the ordered day list.
        val streak = Gamification.streak(questResults, config)

        // Persist profile aggregates, preserving sync/status fields.
        val profile = playerProfileDao.get() ?: PlayerProfile()
        playerProfileDao.upsert(
            profile.copy(
                totalXp = xpState.totalXp,
                level = xpState.level,
                currentStreak = streak.current,
                longestStreak = streak.longest,
            ),
        )
    }
}
