package com.example.backlogium.domain

import com.example.backlogium.data.local.dao.DailyProgressDao
import com.example.backlogium.data.local.dao.HltbDataDao
import com.example.backlogium.data.local.dao.PlayerProfileDao
import com.example.backlogium.data.local.dao.SessionDao
import com.example.backlogium.data.local.entity.PlayerProfile
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
 * - **XP** is fed per-game tracked `Session.minutes` (only playtime the app tracked), joined
 *   with each game's HowLongToBeat completionist length so the engine can taper XP per game.
 * - **Goal progress** is fed each game's total `playtimeForever` and is derived in the UI
 *   layer via [com.example.backlogium.gamification.Gamification.goalProgress].
 */
class GamificationUpdater @Inject constructor(
    private val sessionDao: SessionDao,
    private val dailyProgressDao: DailyProgressDao,
    private val playerProfileDao: PlayerProfileDao,
    private val hltbDataDao: HltbDataDao,
) {

    /**
     * Recompute and persist all derived gamification values. Called on each sync and on day
     * rollover. [today] is injected so the engine stays clock-free; [config] carries the
     * tunable rules.
     */
    suspend fun recompute(today: LocalDate, config: RuleConfig = RuleConfig()) {
        // XP/level from per-game tracked minutes, each tapered against that game's HLTB
        // completionist average. Games with no HLTB row resolve to null -> flat fallback.
        val games = sessionDao.trackedMinutesByGame().map { row ->
            GamePlaytimeInput(
                gameId = row.appId.toString(),
                minutesPlayed = row.minutes,
                completionistAverageMinutes = hltbDataDao.getByAppId(row.appId)?.completionistMinutes,
            )
        }
        val xpState = Gamification.xp(games, config)

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
