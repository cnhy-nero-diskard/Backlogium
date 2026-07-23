## Context

The base engine (`add-gamification-engine`) computes playtime XP per game, with
diminishing returns relative to each game's HowLongToBeat completionist average:
`xp(games: List<GamePlaytimeInput>, cfg) -> XpState`, where `xp()` sums `gameXp(...)`
across the supplied games before deriving level/progress. This change adds a second XP
source — unlocked Steam achievements, weighted by rarity — that feeds into the *same*
`XpState`, not a parallel currency. It stays inside the engine's existing constraints:
pure functions, no clock, no I/O, no persistence, no Android/networking deps.

## Goals / Non-Goals

**Goals:**
- Award more XP for rarer achievements, using discrete tiers rather than a continuous
  formula, so payouts are bounded and predictable.
- Keep achievement XP and playtime XP as one unified pool feeding the existing level
  curve — no separate "achievement level."
- Compose with the base engine's per-game `xp(games, cfg)` signature as a single
  additional defaulted parameter, keeping one XP entry point rather than a parallel
  `xpWithAchievements()` path.

**Non-Goals:**
- Fetching `GetGlobalAchievementPercentagesForApp` or per-player unlock state (owned by
  `steam-sync`, out of scope here — see proposal.md Non-goals).
- Defining how/when a cached global-percent value gets refreshed as it drifts over
  time (open question below).
- A continuous inverse-rarity formula — explicitly rejected in favor of tiers.

## Boundary

```
   steam-sync                       gamification engine                app-ui
   ──────────                       ───────────────────                ──────
   supplies per-game minutes +  ─▶  GamePlaytimeInput(gameId,    ─▶  renders XP bar,
   HLTB completionist averages      minutesPlayed,                    rarity badges,
                                     completionistAverageMinutes)      level, quest, streak
   fetches achievement          ─▶  AchievementInput(id, unlocked,
   unlock state + global             globalUnlockPercent)
   percentages (per player,          │  (pure functions)
   per app)                          ▼
                       tierFor(percent) -> RarityTier
                       achievementXp(achievements, cfg) -> Int
                       xp(games, achievements, cfg) -> XpState
                              │
                    app persists the returned XpState (Room)
```

The engine never reaches out for rarity data (or HLTB data); the caller supplies both
per achievement and per game, same pattern as the base engine's `GamePlaytimeInput`.

## Public surface (illustrative, extends the base engine)

```kotlin
enum class RarityTier { COMMON, UNCOMMON, RARE, EPIC, LEGENDARY }

data class AchievementInput(
    val id: String,
    val unlocked: Boolean,
    val globalUnlockPercent: Double?,  // 0.0 .. 100.0 from Steam; null = no global stat available
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
    // ...existing functions unchanged (including gameXp, per-game diminishing returns)...

    fun tierFor(globalUnlockPercent: Double): RarityTier
    fun achievementXp(achievements: List<AchievementInput>, cfg: RuleConfig = RuleConfig()): Int

    // Extends the base engine's per-game signature with a defaulted empty list — no
    // existing call site (both changes are still unimplemented) requires modification.
    fun xp(games: List<GamePlaytimeInput>, achievements: List<AchievementInput> = emptyList(), cfg: RuleConfig = RuleConfig()): XpState
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

totalXp = Σ gameXp(g.minutesPlayed, g.completionistAverageMinutes, cfg) over games
          + achievementXp(achievements, cfg)
level, xpIntoLevel, xpForNext  <- unchanged, derived from totalXp exactly as today
```

Playtime XP itself is unchanged by this proposal — it's still the base engine's
per-game diminishing-returns sum. Achievement XP simply adds one more term to the same
total before the level curve is applied.

Boundary values belong to the more-common (higher) tier: exactly 50% is `COMMON`,
exactly 20% is `UNCOMMON`, exactly 5% is `RARE`, exactly 1% is `EPIC`. Locked
achievements contribute zero regardless of rarity.

A concrete `0.0` is a real percentage (genuinely ultra-rare) and tiers as `LEGENDARY`.
A **null** `globalUnlockPercent` means Steam returned no global stat for that
achievement — it cannot be tiered, so it contributes **zero** XP even when unlocked,
rather than defaulting to the top tier. This keeps a missing/absent stat from silently
paying out the maximum award.

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
- **Null rarity contributes zero; `0.0` is legendary (locked).** `globalUnlockPercent`
  is nullable. A null (Steam has no global stat for the achievement) is treated as
  "un-tierable" and awards zero XP even when unlocked; a concrete `0.0` is a genuine
  ultra-rare value and tiers as `LEGENDARY`. This separates "no data" from "vanishingly
  rare" so an absent stat never silently pays out the top award. `tierFor` therefore only
  accepts a non-null percent; `achievementXp` skips achievements whose percent is null.
- **One unified XP pool.** Achievement XP adds directly into the same total that
  drives the level curve, rather than a separate achievement-level or achievement-only
  progress bar. Simpler mental model for the player and reuses the existing level math
  unchanged.
- **Additive signature change to `xp()`.** Adding `achievements` as a defaulted
  parameter alongside the base engine's `games: List<GamePlaytimeInput>` (rather than a
  new `xpWithAchievements()` function) keeps one XP entry point and avoids two
  divergent code paths computing "total XP" differently. The one shipped consumer —
  `GamificationUpdater.recompute` — is migrated to the list-based `xp(games, cfg)` by
  `add-gamification-engine` (this change depends on that migration landing first); the
  defaulted `achievements` parameter then extends that signature without forcing a
  second edit to the consumer, since omitting the argument reproduces playtime-only
  behavior exactly.

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
no persisted schema or shipped call sites yet. `xp()` gains a defaulted `achievements`
parameter on top of the base engine's `games`-based signature, so no existing call site
requires modification.

## Open Questions

- Should a per-app achievement list ever be considered "complete" (100% of that app's
  achievements unlocked) for a bonus, on top of per-achievement tiers? Not proposed
  here — flagged for a future change if wanted.
- Does the app need to snapshot `globalUnlockPercent` at unlock time to keep past XP
  stable, or accept that a resync could shift historical totals? Deferred to
  `add-android-steam-app` design when it wires this engine up.
