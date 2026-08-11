# Rarity Standing

## Why

The app tells a player what they have unlocked, and how rare each achievement is, but never where
that puts them *among other owners of the game*. "30 of 40 achievements" is a fact about you;
"top 6.7% or better" is a fact about you relative to everyone else, which is the thing a rarity
system is implicitly promising and currently cannot deliver.

The usual objection is that ranking needs data the app does not have — a user base, a leaderboard, a
scraped dataset. It does not. Steam's per-achievement global unlock rates, already fetched and
stored for every in-scope game, are enough to derive a **provable upper bound** on the share of
owners at or above the player's unlocked count, by pigeonhole:

> A player with `n` of `N` unlocked is missing `m = N − n`. Take any `m + k` achievements. Missing at
> most `m` of them, the player holds at least `k`. So every player at `n`-or-better contributes at
> least `k` memberships to that set, and the set's combined population — the sum of its unlock rates
> — cannot be smaller than `k` times the share of such players. Hence that share is at most
> `(sum of the m+k rarest rates) / k`. Minimising over `k` gives the tightest bound.

No independence assumption, no correlation model, no estimation. It is a sort and a running sum over
a list of tens of items, and the result is a theorem, not a guess.

## What Changes

- A **Rarity Standing section** in the achievements UI showing the player's provable standing among
  owners of that game, derived only from Steam's global unlock percentages.
- Headline `Top {ceiling}% or better`, with the player's count against the **average owner's** count,
  and a footnote that the population includes unplayed copies.
- The bound is **always phrased as a ceiling** and **always rounded away from zero**, so every
  number displayed is one the math actually proves.
- Suppressed when uninformative: a bound at or above 50% says nothing useful, so only the average is
  shown.

## Capabilities

### New Capabilities
- `rarity-standing`: the derivation of a provable upper bound on the share of a game's owners at or
  above the player's unlocked count, from per-achievement global unlock rates alone — including its
  degradation rules when rates are missing and its guarantee that a displayed bound is never
  tighter than the proven one.

### Modified Capabilities
- `app-ui`: the achievements UI gains the Rarity Standing section.

## Impact

- **Affected code (new):** a pure `RarityStanding` object in the `:gamification` module with its own
  JVM unit tests; a section composable; ui state on `GameDetailViewModel`.
- **Affected code (modified):** `GameDetailViewModel` (compute from already-observed achievement
  rows); `GameDetailScreen` (render the section below the game summary). The collection bottom sheet
  shares `GameDetailList`, so it inherits the section with no further wiring — intended, not
  incidental.
- **Uses the live `globalPercent`, not the persisted `snapshotPercent`.** The engine's rarity-drift
  rule — snapshot, never live — governs XP and does not apply here; the bound describes the owner
  population as it stands now. See design.
- **No new network calls, no new persistence, no migration.** `p[]`, `n`, and `N` all come from
  achievement rows already stored: `GetPlayerAchievements` returns every achievement including locked
  ones, and `getGlobalAchievementPercentages` is already called alongside it.
- **No new cache.** `AchievementRepository` already gates fetches on a **1-hour** freshness window
  and stores every percentage in Room — tighter than the 24-hour caching this feature needs. Adding a
  second cache layer would duplicate a mechanism that already exists.
- **Sequencing:** unblocked. `enhance-game-detail` landed 2026-08-04, so the summary section this
  belongs beside already exists and the standalone fallback placement is moot.

## Non-goals

- **A point estimate of the player's percentile.** The math yields an upper bound; presenting it as
  "you are in the top 6.7%" would claim precision that does not exist. The "or better" phrasing is
  load-bearing, not hedging.
- **Cross-game or library-wide standing.** The bound is per game, because the population it is
  computed over is that game's owners.
- **Correcting for unplayed copies.** Steam's rates are over owners, including people who never
  launched the game, which makes every bound conservative in the player's favour. Adjusting for that
  would require data the app does not have; the footnote states the caveat instead.
- **Any external data source, scraping, or app-side user base.** The whole point is that Steam's own
  published rates suffice.
- **Ranking within a friends list or against specific players.**
- **A separate 24-hour cache** for global percentages — see Impact.
