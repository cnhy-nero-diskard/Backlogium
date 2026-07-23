# Design — gamification engine (pure rules module)

## Context

Phase 2 of the README is a design lock, not a coding phase. Making it a standalone
module enforces that separation: the rules are decided and unit-tested in isolation,
then consumed by the app. The engine has **zero Android / networking / persistence
dependencies** so it runs in plain JVM tests (millisecond feedback) and could later
be shared with the OBS overlay's logic if desired.

## Boundary

```
   steam-sync / hltb data      gamification engine            app-ui
   ──────────────────────      ───────────────────            ──────
   synthesizes sessions   ─▶  per-game minutes,        ─▶  renders XP bar,
   (tracked minutes) +        completionist averages,       level, quest, streak,
   completionist averages ─▶  per-day totals,               goal progress
   (per game, from HLTB)      goal targets  ──────────▶
                               │  (pure functions)
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
    val hltbZeroMultiple: Double = 2.0,  // playtime >= this × completionist average earns zero
    val hltbDecayExponent: Int = 4,      // shapes the taper; see "Key math" below
    val questThresholdMin: Int = 30,
    val questMode: QuestMode = QuestMode.ANY,   // ANY | GOAL_ONLY
    val streakGraceDays: Int = 0,
)

data class XpState(val totalXp: Int, val level: Int, val xpIntoLevel: Int, val xpForNext: Int)
data class GamePlaytimeInput(
    val gameId: String,
    val minutesPlayed: Int,
    val completionistAverageMinutes: Int?,   // HowLongToBeat completionist average; null if unavailable
)
data class GoalProgress(val fraction: Double)          // 0.0 .. 1.0
data class DayInput(val date: LocalDate, val anyMinutes: Int, val goalMinutes: Int)
data class QuestResult(val date: LocalDate, val met: Boolean)
data class StreakState(val current: Int, val longest: Int)

object Gamification {
    fun xp(games: List<GamePlaytimeInput>, cfg: RuleConfig = RuleConfig()): XpState
    fun gameXp(minutesPlayed: Int, completionistAverageMinutes: Int?, cfg: RuleConfig = RuleConfig()): Int
    fun goalProgress(playtimeMin: Int, targetMin: Int): GoalProgress
    fun quest(day: DayInput, cfg: RuleConfig = RuleConfig()): QuestResult
    fun streak(days: List<QuestResult>, cfg: RuleConfig = RuleConfig()): StreakState
}
```

Everything is a pure function of its arguments — no clocks, no storage. The app passes
"today" and the ordered day list; the engine does not read the system clock (keeps
tests deterministic). `xp()` sums `gameXp(...)` across the supplied games before
deriving level and progress.

## Key math (locked)

### Playtime XP — per-game diminishing returns

Playtime XP is no longer a flat `minutes × rate` across an aggregate total. Each game's
XP tapers off relative to its own HowLongToBeat **completionist average** length, so
grinding one game past what a completionist would spend on it stops paying out:

```
T = completionistAverageMinutes (HLTB completionist average, converted to minutes)
Z = cfg.hltbZeroMultiple · T        // the "zero point"; default 2.0 → 2× average earns nothing further
k = cfg.hltbDecayExponent           // default 4; shapes how fast the taper falls off

marginal rate at minute m (0 ≤ m < Z):  xpPerMinute · (1 − m/Z)^k
marginal rate at m ≥ Z:                 0

gameXp(M, T, cfg) = xpPerMinute · (Z / (k+1)) · (1 − (1 − min(M,Z)/Z)^(k+1))
```

`gameXp` is the closed-form integral of the marginal rate over `M` minutes played — a
smooth cumulative function, not a per-minute loop. With the documented defaults
(`hltbZeroMultiple = 2.0`, `hltbDecayExponent = 4`):

- At `M = 0`: `gameXp = 0`.
- At `M = T` (played exactly the completionist average): marginal rate is
  `(1 − 0.5)^4 = 0.0625` of the base rate — a **very small** but non-zero trickle.
- At `M = Z = 2T` (twice the completionist average) and beyond: marginal rate is
  **exactly zero** — no further XP from that game.
- Cumulative XP is monotonically non-decreasing and caps out once `M ≥ Z`.

**Games with no completionist-average data** (`completionistAverageMinutes = null`)
fall back to the flat, uncapped rate (`xpPerMinute` per minute, no taper) — the
engine cannot compute a taper against a length it doesn't have, and refusing to award
any XP would be a worse default than simply not tapering.

Total playtime XP = `Σ gameXp(...)` across every game in the supplied list.

### Level curve (unchanged)

```
xpAt(L)      = 50 · (L − 1) · L          // cumulative XP to reach level L
next cost    = 100 · L                   // minutes to go L → L+1
level(xp)    = floor((1 + sqrt(1 + 0.08·xp)) / 2)   // closed form, no loop
xpIntoLevel  = xp − xpAt(level)
xpForNext    = 100 · level

  L1  0xp | L2  100xp(1.7h) | L3  300xp(5h) | L4  600xp(10h) | L5  1000xp(16.7h)
```

Level thresholds are unaffected by how the underlying XP was earned (flat or tapered) —
`level()` only ever sees a total. Constants live in `RuleConfig`; a few days of real
data can retune `xpPerMinute`, `levelBase`, `hltbZeroMultiple`, and `hltbDecayExponent`
with no schema impact (persisted values are totals/day-results, not level thresholds).

## Testing strategy

Pure JVM unit tests, no instrumentation:
- **Playtime XP (per game):** near-full rate early in a game, marginal rate exactly at
  `M = T` (very small, matches the closed-form value), exactly at and beyond `M = Z`
  (zero, capped), monotonic non-decreasing cumulative XP, and the no-completionist-data
  fallback (flat rate, untapered).
- **XP/level:** thresholds exact (100 → L2, 300 → L3), just-below (99 → L1),
  within-level progress fractions, zero XP, and total XP correctly summed across
  multiple games with mixed tapered/untapered inputs.
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
- **Closed-form integral over a per-minute loop for playtime XP.** `gameXp` is derived
  as the integral of the marginal-rate function rather than summed minute-by-minute in
  a loop, keeping it a pure closed-form expression consistent with `level(xp)` above —
  no iteration, same evaluation cost regardless of `M`.
- **`hltbZeroMultiple = 2.0` and `hltbDecayExponent = 4` as the documented defaults**
  are not arbitrary: they're the two constants that satisfy the exact anchor points
  requested — a 2× multiple pins "zero at twice the completionist average" exactly, and
  an exponent of 4 pins "very small" at 1× to `0.5⁴ = 6.25%` of the base rate. Both are
  still ordinary `RuleConfig` fields and can be retuned like any other constant.
- **No-HLTB-data fallback is flat, uncapped XP** — treated as a decision, not left
  open: an unresolved completionist average is common (new/obscure games,
  HowLongToBeat gaps) and shouldn't silently zero out or block XP for those games.
  Revisit if real data suggests a stricter default is better.
- **Streak grace preserves but does not grow the streak (locked).** A day that fails
  the quest but falls within the `streakGraceDays` allowance is *forgiven*: the current
  streak keeps its value and grace is consumed, but the streak does **not** increment
  for that day — only met days increment it. A met day resets the grace budget back to
  full. Once failures exceed the grace allowance, the current streak resets to zero.
  So with `streakGraceDays = 1`, the sequence `met, met, unmet, met` yields a current
  streak of 3 (the gap is forgiven and the following met day extends it), not 4. This
  matches the shipped stub's implementation and keeps grace a "don't punish one slip"
  rule rather than a "credit the slip" rule. Default `streakGraceDays = 0` means any
  unmet day breaks immediately.
