# Tasks — gamification engine (pure rules module)

## 1. Module setup
- [ ] 1.1 Create `:gamification` Gradle module (pure Kotlin/JVM, no Android/Retrofit/Room deps)
- [ ] 1.2 Wire it into `settings.gradle.kts`; add JUnit test dependency only

## 2. Config & types
- [ ] 2.1 `RuleConfig` with documented defaults (xpPerMinute, levelBase, hltbZeroMultiple, hltbDecayExponent, questThresholdMin, questMode, streakGraceDays)
- [ ] 2.2 Value types: `XpState`, `GamePlaytimeInput`, `GoalProgress`, `DayInput`, `QuestResult`, `StreakState`, `QuestMode`

## 3. Computations (pure functions)
- [ ] 3.1 `gameXp(minutesPlayed, completionistAverageMinutes, cfg)` — closed-form diminishing-returns XP for a single game; flat/uncapped when `completionistAverageMinutes` is null
- [ ] 3.2 `xp(games: List<GamePlaytimeInput>, cfg)` — sums `gameXp` across all games, then closed-form level, xpIntoLevel, xpForNext
- [ ] 3.3 `goalProgress(playtimeMin, targetMin)` — clamp 0.0–1.0, zero-target guard
- [ ] 3.4 `quest(day, cfg)` — threshold + ANY / GOAL_ONLY mode
- [ ] 3.5 `streak(days, cfg)` — current + longest, grace, break handling; no internal clock

## 4. Tests (JVM unit)
- [ ] 4.1 Playtime XP: early/near-full rate, marginal rate exactly at `M = T` (very small, matches closed form), zero at and beyond `M = Z = 2T`, monotonic non-decreasing, no-completionist-data fallback (flat rate)
- [ ] 4.2 XP/level: exact thresholds (100→L2, 300→L3), 99→L1, within-level fraction, 0 XP, total summed correctly across multiple games (mixed tapered/untapered)
- [ ] 4.3 Goal progress: partial, exact (→1.0), over (clamped), zero target
- [ ] 4.4 Quest: at threshold met, below unmet, ANY vs GOAL_ONLY
- [ ] 4.5 Streak: empty, all met, single break, break with grace≥1, longest preserved, non-consecutive dates

## 5. Handoff
- [ ] 5.1 Document the public surface (KDoc) so `add-android-steam-app` can depend on it
- [ ] 5.2 Confirm the module compiles with no Android classpath (enforces the boundary)
