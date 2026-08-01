# Tiered achievement refresh instead of a whole-library sweep

## Why

`steam-achievements` currently mandates the maximally expensive strategy available: *"fetch
achievements for every game in the library, regardless of play history or goal tagging,"* gated
only by a one-hour wall-clock freshness window. `AchievementRepository.syncLibraryGames`
(`:85-101`) implements exactly that, and the cost is the entire cost of a sync.

For a 300-game library, roughly 240 of which have achievements, one sweep is:

```
   GetPlayerAchievements                × 300   (every stale game)
   GetGlobalAchievementPercentages      × 240   (games with achievements)
   GetSchemaForGame                     × 240   (games with achievements)
   ─────────────────────────────────────────
   ≈ 780 requests, strictly sequential          ≈ 4 minutes
```

Three properties make this worse than the raw count suggests:

1. **Clustered staleness.** The first sync stamps every game's `fetchedAt` within a single pass, so
   the whole library expires *together*. Sync duration alternates between roughly two seconds and
   roughly four minutes, with the full sweep firing every fourth 15-minute run.

2. **No concurrency and no timeouts.** `AchievementRepository.kt:98-100` is a bare `for` loop, and
   the Steam `OkHttpClient` (`NetworkModule.kt:42-45`) is built with no timeout configuration at
   all — unlike the HLTB client, which sets them explicitly (`:80-82`). One stalled request blocks
   the entire sweep for OkHttp's default read timeout. At 4 minutes the run is also uncomfortably
   close to `CoroutineWorker`'s hard 10-minute execution ceiling; a slower network or a larger
   library turns that into a cliff where the sweep is killed partway and retried indefinitely.

3. **Almost all of it is redundant.** A game not played since the last sync cannot have unlocked a
   new achievement. The sync *already computes* which games were played —
   `diff.playedDeltaByAppId` (`SteamSyncWorker.kt:132`), derived from `playtime_forever` deltas
   that `GetOwnedGames` returns for free — and then discards that knowledge and refreshes
   everything anyway.

Critically, delta detection is **already robust to device downtime**, because the delta is computed
against `lastPlaytime` stored in Room rather than against wall-clock time. Phone off for three
days, ten hours played → the next sync observes the increase and fires. This means the
whole-library sweep is not a catch-up mechanism, as its current framing implies. It is a
**reconciliation net** for a much narrower set of cases:

- a developer patching new achievements into a game the player has not launched
- Steam's achievement data lagging its own playtime data
- a per-game fetch that failed and was swallowed by `runCatching` (`AchievementRepository.kt:99`)
- sub-minute sessions too short to move the playtime counter

Reconciliation is worth keeping. It is not worth doing hourly, and it should never be on the path
of an interactive sync.

Two smaller inefficiencies compound in the same code path:

- **Static data refetched hourly.** `GetSchemaForGame` returns achievement names, descriptions, and
  icons — data that changes only when the developer patches the game. `GetGlobalAchievement-`
  `Percentages` returns global rarity across millions of players, which does not move measurably in
  an hour. Both share the achievement freshness window, so together they are two thirds of every
  sweep's request volume for no benefit.
- **A database N+1 scaling with library size.** `SteamSyncWorker.kt:113-126` calls
  `sessionDao.getOpenSession(appId)` once per owned game, every sync, inside a `mapValues`.

## What Changes

- **Tier achievement refresh by evidence of play**, replacing the single wall-clock window:
  - **Hot** — a positive playtime delta in the current sync: refresh immediately. Zero, one, or two
    games typically. The signal is already computed and free.
  - **Warm** — recent play per Steam's own `playtime_2weeks`, also already returned by
    `GetOwnedGames`: refresh every sync. Typically a handful of games. This tier is not redundant
    with the hot tier — Steam's achievement data can lag its playtime data, so a single fetch at
    the moment of delta-detection can miss the unlock. A trailing window catches it.
  - **Cold** — everything else: an infrequent reconciliation pass, not part of an interactive sync.
- **Never fetch achievements for a game with zero recorded playtime.** A game never launched cannot
  have achievements unlocked, yet each currently costs one request to rediscover that.
- **Separate freshness windows per data kind**, so static data is not tied to per-player data:
  schema on a long window, global percentages on a medium one, per-player unlock state driven by
  the tiers above.
- **Move the cold reconciliation pass into its own deferred worker**, constrained to charging and
  unmetered network, so its duration cannot delay anything a user is waiting on and cannot threaten
  the interactive sync's execution budget.
- **Bound the reconciliation pass**: real timeouts on the Steam client and modest bounded
  concurrency, so total duration is predictable rather than proportional to the slowest request.
- **Collapse the open-session N+1** into a single query.

Expected steady state, 300-game library:

| | now | after |
|---|---|---|
| typical sync | ~783 requests, ~4 min | **~8 requests, ~3 s** |
| worst-case sync | ~783 requests, ~4 min | ~8 requests — sweep is a separate worker |
| reconciliation | inline, hourly | ~300 requests, weekly, on charger |

## Capabilities

### Modified Capabilities
- `steam-achievements`: the freshness-gated whole-library sweep is replaced by tiered refresh
  driven by playtime evidence, with per-data-kind freshness windows and reconciliation moved off
  the interactive sync path. Persistence, the rarity snapshot, and XP feeding are unchanged.
- `steam-sync`: the periodic poll gains an explicit bound on the library-scale work it may perform
  inline, and sheds the per-game open-session query.

## Impact

- **Affected code:** `AchievementRepository` (tier selection replaces
  `syncLibraryGames`' single-window loop; per-kind windows in `syncGame`);
  `AchievementFreshness` (tiering logic — currently a pure, unit-tested function, and should stay
  one); a new reconciliation worker plus its scheduling; `SteamSyncWorker` (pass the computed
  deltas into achievement sync; collapse the session N+1); `NetworkModule` (Steam client
  timeouts); `SessionDao` (a bulk open-sessions query); persisted per-kind fetch timestamps.
- **Data model:** distinguishing schema / global-percentage / player-state freshness needs more
  than the single `fetchedAt` on `Achievement`. Likely a small per-game sync-metadata row rather
  than widening every achievement row — see design.
- **Correctness risk is the real cost here.** Today's strategy is expensive but trivially correct:
  fetch everything, often. Tiering trades that for a set of assumptions about when achievement data
  can change. The reconciliation pass exists precisely to bound the blast radius of a wrong
  assumption — a missed unlock is corrected within a week rather than persisting forever. Any
  reduction in reconciliation frequency should be weighed against that.
- **Relationship to `fix-live-status-detection`:** independent. That change stops presence from
  waiting on the sweep; this one makes the sweep small. Landing the detection fix first is
  preferable — it is low-risk statement reordering, while this change carries real correctness
  surface.
- **Depends on `add-sync-diagnostics`.** The cost figures above are arithmetic, not measurement, and
  the shadow validation this change relies on needs persisted per-run records to exist. Diagnostics
  should land first so the premise can be checked before it is acted on.
- **Steam API courtesy:** the current strategy issues on the order of 19,000 requests per day
  against a 100,000/day key limit for a single user. Tiering reduces that by roughly two orders of
  magnitude.

## Non-goals

- **Changing how achievements are stored, snapshotted, or scored.** The rarity snapshot's
  first-unlock stability and the XP pipeline are untouched.
- **Fetching achievements on game-detail open.** A tempting further step, and it would make the
  data fresher exactly where it is read — but it changes the screen's loading behaviour and belongs
  in its own change.
- **Removing the 15-minute periodic poll.** Session boundary precision depends on
  `previousPollAt`; a coarser interval smears play history. Once the sweep moves out, the poll costs
  a handful of requests, so there is nothing to gain by lengthening it.
- **Touching HLTB fetching.** Already off the sync path entirely, with its own 60-day window and
  request throttle.
- **Achievement fetching for games the player does not own.** Out of scope as ever.
- **Optimising `GetOwnedGames` itself.** One request, unavoidable, and the source of the free
  signals this change depends on.
