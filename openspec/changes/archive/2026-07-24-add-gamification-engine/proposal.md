# Add gamification engine (pure rules module)

## Why

The README's Phase 2 is explicit: *"lock the rules before they're baked into a
schema"* — XP, goal games, quests, and streaks are design decisions that should be
settled and testable **before** they entangle with Steam networking, Room schema, or
Compose UI. Isolating them as a standalone, platform-agnostic module means the rules
can be exercised entirely in fast JVM unit tests, tuned freely, and reused by the
Android app (and later the OBS overlay) without duplication.

This change owns the **computation** only. It does not fetch Steam data, persist
anything, or render UI. It is a library of pure functions and value types: inputs in,
derived gamification state out.

## Current state (not greenfield)

Since this proposal was first written, `add-android-steam-app` shipped the surrounding
scaffolding, so this is a **replace-and-migrate**, not a from-scratch build:

- The `:gamification` module **already exists** and is wired into `settings.gradle.kts`.
  It holds a deliberate **stub** (`Gamification.kt`) with the *flat* signature
  `xp(totalMinutes: Int, cfg)` and a `levelState(totalXp, cfg)` helper.
- A **live consumer** — `GamificationUpdater.recompute` — already calls that stub, backed
  by a passing test (`GamificationUpdaterTest`). Replacing the signature therefore requires
  migrating the consumer in the same change or the app stops compiling.
- The **HLTB completionist length is available**: `HltbData.completionistMinutes` (keyed by
  `appId`, its own table, archived into main specs). The engine's per-game input can be fed
  for real; games with no match resolve to `null` → the engine's flat fallback.

## What Changes

- A platform-agnostic `gamification` module (no Android or networking dependencies)
  exposing:
  - **`RuleConfig`** — all tunable constants (XP rate, level-curve constants, quest
    threshold, quest mode `ANY`/`GOAL_ONLY`, streak grace) with documented defaults.
  - **XP / level** — per-game diminishing-returns playtime XP, relative to each game's
    HowLongToBeat completionist-average length: near-full rate early, tapering to a
    very small marginal rate at 1× that average, and to zero at 2× and beyond. Summed
    across a player's library into total XP; level via `xpAt(L) = 50·(L−1)·L`, with
    closed-form level derivation and within-level progress, unchanged.
  - **Goal progress** — `min(playtime / target, 1.0)`.
  - **Daily quest** — met/unmet for a day given qualifying minutes and mode.
  - **Streaks** — current + longest from an ordered list of per-day quest results,
    honoring grace.
- Comprehensive unit tests covering boundaries (level thresholds, empty history,
  quest-exactly-at-threshold, streak break + grace, longest preserved).

## Impact

- **Affected specs (new capability):** `gamification`.
- **Affected code (engine):** the existing `:gamification` module (pure Kotlin/JVM) —
  replace the stub `xp` with the per-game implementation and extend `RuleConfig`. No
  Android, Retrofit, or Room imports.
- **Affected code (consumer, in this change):** `GamificationUpdater` must be migrated to
  the new list signature, `SessionDao` gains a per-game tracked-minutes query, and
  `GamificationUpdaterTest` is updated — otherwise the app module won't compile.
- **Sequencing:** the engine itself is independent and JVM-testable in isolation; the
  consumer migration lands with it. HowLongToBeat ingestion is **already done and archived**
  (`add-hltb-integration`), so there is no external dependency left to wait on.
- **External input (now satisfied):** the completionist-average length (minutes) per game
  comes from `HltbData.completionistMinutes`. The engine still only *consumes* an
  already-fetched value — it does not query HowLongToBeat itself, same boundary as tracked
  minutes.

## Non-goals

- **Persistence** — storing XP totals, daily rows, or profile state belongs to the
  app (`add-android-steam-app`); the engine only computes.
- **Where playtime comes from** — session synthesis is `steam-sync`'s job; the engine
  receives already-tracked minutes per game.
- **Fetching HowLongToBeat data** — looking up or caching a game's completionist
  average is an ingestion concern owned by the already-shipped `add-hltb-integration`, not
  the engine's job; the engine receives an already-resolved value (or none) per game.
- **UI** — progress bars, level displays, and quest widgets belong to `app-ui`.
- **Achievement-based XP** and **auto-detected goal games** — deferred, same as the
  overall MVP.
