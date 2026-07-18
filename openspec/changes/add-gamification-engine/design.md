# Design — gamification engine (pure rules module)

## Context

Phase 2 of the README is a design lock, not a coding phase. Making it a standalone
module enforces that separation: the rules are decided and unit-tested in isolation,
then consumed by the app. The engine has **zero Android / networking / persistence
dependencies** so it runs in plain JVM tests (millisecond feedback) and could later
be shared with the OBS overlay's logic if desired.

## Boundary

```
   steam-sync            gamification engine            app-ui
   ──────────            ───────────────────            ──────
   synthesizes    ─▶  minutes, per-day totals,   ─▶  renders XP bar,
   sessions           goal targets  ───────────▶     level, quest, streak,
   (tracked minutes)  │  (pure functions)             goal progress
                      ▼
              XpState · GoalProgress · QuestResult · StreakState
                      │
              app persists the returned values (Room)
```

The engine never reaches out; callers pass inputs and store outputs. This keeps it
pure and makes the app responsible for I/O and schema.

## Public surface (illustrative)

```kotlin
data class RuleConfig(
    val xpPerMinute: Int = 1,
    val levelBase: Int = 50,        // xpAt(L) = levelBase · (L-1) · L
    val questThresholdMin: Int = 30,
    val questMode: QuestMode = QuestMode.ANY,   // ANY | GOAL_ONLY
    val streakGraceDays: Int = 0,
)

data class XpState(val totalXp: Int, val level: Int, val xpIntoLevel: Int, val xpForNext: Int)
data class GoalProgress(val fraction: Double)          // 0.0 .. 1.0
data class DayInput(val date: LocalDate, val anyMinutes: Int, val goalMinutes: Int)
data class QuestResult(val date: LocalDate, val met: Boolean)
data class StreakState(val current: Int, val longest: Int)

object Gamification {
    fun xp(totalMinutes: Int, cfg: RuleConfig = RuleConfig()): XpState
    fun goalProgress(playtimeMin: Int, targetMin: Int): GoalProgress
    fun quest(day: DayInput, cfg: RuleConfig = RuleConfig()): QuestResult
    fun streak(days: List<QuestResult>, cfg: RuleConfig = RuleConfig()): StreakState
}
```

Everything is a pure function of its arguments — no clocks, no storage. The app passes
"today" and the ordered day list; the engine does not read the system clock (keeps
tests deterministic).

## Key math (locked)

```
xpAt(L)      = 50 · (L − 1) · L          // cumulative XP to reach level L
next cost    = 100 · L                   // minutes to go L → L+1
level(xp)    = floor((1 + sqrt(1 + 0.08·xp)) / 2)   // closed form, no loop
xpIntoLevel  = xp − xpAt(level)
xpForNext    = 100 · level

  L1  0xp | L2  100xp(1.7h) | L3  300xp(5h) | L4  600xp(10h) | L5  1000xp(16.7h)
```

Constants live in `RuleConfig`; a few days of real data can retune `xpPerMinute` and
`levelBase` with no schema impact (persisted values are totals/day-results, not
level thresholds).

## Testing strategy

Pure JVM unit tests, no instrumentation:
- **XP/level:** thresholds exact (100 → L2, 300 → L3), just-below (99 → L1),
  within-level progress fractions, zero XP.
- **Goal progress:** partial, exact target (→1.0), over target (clamped), zero target guard.
- **Quest:** exactly at threshold (met), one below (unmet), ANY vs GOAL_ONLY.
- **Streak:** empty list, all met, single break, break with grace ≥1, longest
  preserved after reset, non-consecutive dates.

## Decisions / notes

- **No clock inside the engine** — determinism; the app injects the current date.
- **Ints for minutes/XP** — avoids float drift in XP; only `GoalProgress.fraction`
  and level derivation use doubles.
- **Module vs package:** a dedicated `:gamification` Gradle module makes the
  "no Android deps" boundary compiler-enforced; a `domain.gamification` package is
  acceptable if module overhead isn't wanted. Recommend the module.
