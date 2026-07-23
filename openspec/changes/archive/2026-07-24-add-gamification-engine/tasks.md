# Tasks — gamification engine (pure rules module)

> **Starting point (not greenfield).** The `:gamification` module already exists and is
> wired into `settings.gradle.kts` — `add-android-steam-app` shipped a deliberate **stub**
> (`Gamification.kt`) plus a **live consumer** (`GamificationUpdater`) and a passing test
> (`GamificationUpdaterTest`) that lock the *old* flat signature `xp(totalMinutes: Int)`.
> This change **replaces the stub** with the full per-game engine and **migrates the
> consumer** to the new list-based signature. The HLTB completionist length the engine
> consumes is already available (`HltbData.completionistMinutes`, keyed by `appId`,
> archived into main specs) — so per-game data really flows; only the wiring is missing.

## 1. Replace the stub engine (`:gamification` module)
- [x] 1.1 Module already exists and is on `settings.gradle.kts` — confirm the JUnit test
  dependency is present; do **not** recreate the module
- [x] 1.2 Extend the existing `RuleConfig` with the taper fields (`hltbZeroMultiple = 2.0`,
  `hltbDecayExponent = 4`) alongside the current `xpPerMinute`, `levelBase`,
  `questThresholdMin`, `questMode`, `streakGraceDays`
- [x] 1.3 Add the `GamePlaytimeInput` value type (`gameId`, `minutesPlayed`,
  `completionistAverageMinutes: Int?`); keep the existing `XpState`, `GoalProgress`,
  `DayInput`, `QuestResult`, `StreakState`, `QuestMode` types
- [x] 1.4 Keep the existing `levelState(totalXp, cfg)` helper public — it is the shared
  seam `add-achievement-xp` reuses to combine playtime + achievement XP

## 2. Computations (pure functions)
- [x] 2.1 `gameXp(minutesPlayed, completionistAverageMinutes, cfg)` — closed-form
  diminishing-returns XP for a single game; flat/uncapped when
  `completionistAverageMinutes` is null
- [x] 2.2 Replace the stub `xp(totalMinutes: Int, cfg)` with
  `xp(games: List<GamePlaytimeInput>, cfg)` — sums `gameXp` across all games, then derives
  level/progress via `levelState`
- [x] 2.3 `goalProgress`, `quest`, `streak` already exist in the stub — keep as-is
  (unchanged by this change); re-verify against the specs

## 3. Migrate the live consumer (app module — required so it still compiles)
- [x] 3.1 Add a per-game tracked-minutes query to `SessionDao`
  (`SELECT appId, SUM(minutes) … GROUP BY appId`); the current `totalTrackedMinutes()`
  aggregate loses the per-game granularity the new `xp()` needs
- [x] 3.2 Rewrite `GamificationUpdater.recompute` to build `List<GamePlaytimeInput>` by
  joining per-game tracked minutes with `HltbDataDao` completionist minutes
  (`completionistAverageMinutes = HltbData.completionistMinutes`, i.e. `null` when
  unmatched/needs-review → engine's flat fallback), then call the new `xp(games, cfg)`
- [x] 3.3 Inject `HltbDataDao` into `GamificationUpdater` (constructor) and its Hilt wiring
- [x] 3.4 Update `GamificationUpdaterTest` for the new signature — add a fake `HltbDataDao`;
  a game with no HLTB row must reproduce the old flat-rate expectation (300 tracked min →
  300 XP → level 3), guarding the fallback path

## 4. Tests (JVM unit, engine module)
- [x] 4.1 Playtime XP: early/near-full rate, marginal rate exactly at `M = T` (very small,
  matches closed form), zero at and beyond `M = Z = 2T`, monotonic non-decreasing,
  no-completionist-data fallback (flat rate)
- [x] 4.2 XP/level: exact thresholds (100→L2, 300→L3), 99→L1, within-level fraction, 0 XP,
  total summed correctly across multiple games (mixed tapered/untapered)
- [x] 4.3 Goal progress: partial, exact (→1.0), over (clamped), zero target
- [x] 4.4 Quest: at threshold met, below unmet, ANY vs GOAL_ONLY
- [x] 4.5 Streak: empty, all met, single break, longest preserved, non-consecutive dates,
  and grace≥1 — `met,met,unmet,met` with `streakGraceDays=1` yields current 3 (gap
  preserved but not credited); grace resets after a met day; failures beyond grace reset to 0

## 5. Handoff
- [x] 5.1 Document the public surface (KDoc), noting `add-achievement-xp` extends `xp()`
  with a defaulted `achievements` parameter on top of the list signature
- [x] 5.2 Confirm the module still compiles with no Android classpath (enforces the boundary)
- [x] 5.3 Build the app module to confirm the consumer migration compiles and tests pass
