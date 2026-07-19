## Context

The base engine (`add-gamification-engine`) computes XP from tracked minutes only:
`xp(totalMinutes, cfg) -> XpState`. This change adds a second XP source — unlocked
Steam achievements, weighted by rarity — that feeds into the *same* `XpState`, not a
parallel currency. It stays inside the engine's existing constraints: pure functions,
no clock, no I/O, no persistence, no Android/networking deps.

## Goals / Non-Goals

**Goals:**
- Award more XP for rarer achievements, using discrete tiers rather than a continuous
  formula, so payouts are bounded and predictable.
- Keep achievement XP and playtime XP as one unified pool feeding the existing level
  curve — no separate "achievement level."
- Preserve backward compatibility: existing callers of `xp(totalMinutes, cfg)` keep
  working unchanged.

**Non-Goals:**
- Fetching `GetGlobalAchievementPercentagesForApp` or per-player unlock state (owned by
  `steam-sync`, out of scope here — see proposal.md Non-goals).
- Defining how/when a cached global-percent value gets refreshed as it drifts over
  time (open question below).
- A continuous inverse-rarity formula — explicitly rejected in favor of tiers.

## Boundary

```
   steam-sync                    gamification engine                app-ui
   ──────────                    ───────────────────                ──────
   fetches achievement    ─▶  AchievementInput(id, unlocked,  ─▶  renders XP bar,
   unlock state +             globalUnlockPercent)                 rarity badges,
   global percentages         │  (pure functions)                  level, quest, streak
   (per player, per app)      ▼
                    tierFor(percent) -> RarityTier
                    achievementXp(achievements, cfg) -> Int
                    xp(totalMinutes, achievements, cfg) -> XpState
                              │
                    app persists the returned XpState (Room)
```

The engine never reaches out for rarity data; the caller supplies it per achievement,
same pattern as `DayInput` for minutes.

## Public surface (illustrative, extends the base engine)

```kotlin
enum class RarityTier { COMMON, UNCOMMON, RARE, EPIC, LEGENDARY }

data class AchievementInput(
    val id: String,
    val unlocked: Boolean,
    val globalUnlockPercent: Double,   // 0.0 .. 100.0, from Steam's global stats
)

data class RuleConfig(
    // ...existing fields unchanged...
    val commonAchievementXp: Int = 5,
    val uncommonAchievementXp: Int = 15,
    val rareAchievementXp: Int = 40,
    val epicAchievementXp: Int = 100,
    val legendaryAchievementXp: Int = 250,
)

object Gamification {
    // ...existing functions unchanged...

    fun tierFor(globalUnlockPercent: Double): RarityTier
    fun achievementXp(achievements: List<AchievementInput>, cfg: RuleConfig = RuleConfig()): Int

    // Extends the existing signature with a default empty list — old call sites unaffected.
    fun xp(totalMinutes: Int, achievements: List<AchievementInput> = emptyList(), cfg: RuleConfig = RuleConfig()): XpState
}
```

## Key math (locked)

```
tierFor(percent):
  percent >= 50            -> COMMON
  20 <= percent < 50        -> UNCOMMON
  5  <= percent < 20        -> RARE
  1  <= percent < 5         -> EPIC
  percent < 1               -> LEGENDARY

achievementXp(achievements, cfg) =
  sum over unlocked achievements of xpFor(tierFor(a.globalUnlockPercent), cfg)

totalXp = (totalMinutes * cfg.xpPerMinute) + achievementXp(achievements, cfg)
level, xpIntoLevel, xpForNext  <- unchanged, derived from totalXp exactly as today
```

Boundary values belong to the more-common (higher) tier: exactly 50% is `COMMON`,
exactly 20% is `UNCOMMON`, exactly 5% is `RARE`, exactly 1% is `EPIC`. Locked
achievements contribute zero regardless of rarity.

## Decisions

- **Tiers over a continuous formula.** A continuous `xp = base × (100 / percent)`
  scales cleanly but requires a cap to avoid a 0.01%-unlock achievement paying out
  absurdly, and that cap itself becomes an arbitrary tuning knob. Discrete tiers make
  the bound explicit (five fixed values) and match the engine's existing style of
  named, documented constants (`RuleConfig`) rather than a formula with a hidden
  ceiling. Trade-off: two achievements at 4% and 19% score identically even though
  one is meaningfully rarer — accepted as the cost of predictability.
- **Tier boundaries are fixed, not configurable.** `RuleConfig` makes XP-per-tier
  tunable, but the cut points (50/20/5/1) are left as fixed constants rather than
  config, because they define the taxonomy itself (what "rare" *means*) rather than a
  payout amount. Alternative considered: put boundaries in `RuleConfig` too — rejected
  for now to avoid config drift where two builds disagree on what counts as "rare";
  can be revisited if real data shows the cut points are wrong.
- **One unified XP pool.** Achievement XP adds directly into the same total that
  drives the level curve, rather than a separate achievement-level or achievement-only
  progress bar. Simpler mental model for the player and reuses the existing level math
  unchanged.
- **Additive signature change to `xp()`.** Adding `achievements` as a defaulted
  parameter (rather than a new `xpWithAchievements()` function) keeps one XP entry
  point and avoids two divergent code paths computing "total XP" differently. Existing
  callers passing only `totalMinutes` are unaffected.

## Risks / Trade-offs

- **[Risk]** A game's global unlock percentage shifts over time (achievements get
  easier to find guides for, player base changes), so the same achievement could cross
  a tier boundary between two syncs, silently changing past-earned XP if recomputed.
  → **Mitigation**: out of scope for the engine (pure function, no memory of "when" it
  was computed); the app layer (`add-android-steam-app`, not this change) must decide
  whether to snapshot the percent at first-unlock time or accept recomputation drift.
- **[Risk]** Tier boundaries are a judgment call with no real Steam data behind them
  yet. → **Mitigation**: boundaries and per-tier XP are documented, named constants,
  reviewable and retunable with no schema impact (same mitigation the base engine uses
  for `xpPerMinute`/`levelBase`).
- **[Trade-off]** Losing intra-tier differentiation (4% vs 19% both `RARE`) is accepted
  in exchange for bounded, predictable payouts — matches the user's explicit choice of
  tiers over a continuous formula.

## Migration Plan

Not applicable — additive change to a not-yet-archived capability (`gamification`) with
no persisted schema or shipped call sites yet. `xp()` gains a defaulted parameter, so no
existing call site requires modification.

## Open Questions

- Should a per-app achievement list ever be considered "complete" (100% of that app's
  achievements unlocked) for a bonus, on top of per-achievement tiers? Not proposed
  here — flagged for a future change if wanted.
- Does the app need to snapshot `globalUnlockPercent` at unlock time to keep past XP
  stable, or accept that a resync could shift historical totals? Deferred to
  `add-android-steam-app` design when it wires this engine up.
