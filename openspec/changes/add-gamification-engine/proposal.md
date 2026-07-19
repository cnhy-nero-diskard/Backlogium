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
- **Affected code:** new `:gamification` Gradle module (pure Kotlin/JVM) or a
  dependency-free `domain.gamification` package. Depended on by
  `add-android-steam-app`. No Android, Retrofit, or Room imports.
- **Sequencing:** independent of Steam wiring; can be built and fully tested before
  or in parallel with `add-android-steam-app`, which consumes it.
- **New external input:** a completionist-average length (minutes) per game, sourced
  from HowLongToBeat. The engine only consumes an already-fetched value per game — it
  does not query HowLongToBeat itself, same boundary as tracked minutes today.

## Non-goals

- **Persistence** — storing XP totals, daily rows, or profile state belongs to the
  app (`add-android-steam-app`); the engine only computes.
- **Where playtime comes from** — session synthesis is `steam-sync`'s job; the engine
  receives already-tracked minutes per game.
- **Fetching HowLongToBeat data** — looking up or caching a game's completionist
  average is an ingestion concern (`steam-sync` or a future dedicated sync), not the
  engine's job; the engine receives an already-resolved value (or none) per game.
- **UI** — progress bars, level displays, and quest widgets belong to `app-ui`.
- **Achievement-based XP** and **auto-detected goal games** — deferred, same as the
  overall MVP.
