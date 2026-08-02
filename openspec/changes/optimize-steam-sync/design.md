# Design — tiered achievement refresh

## Context

The sync's cost is entirely `AchievementRepository.syncLibraryGames`. Everything else — owned
games, level, summary, the Room writes, the gamification recompute — is a couple of seconds. So
this design is about one question: **which games actually need an achievement request, and when.**

The current answer is "all of them, hourly," which is expensive but trivially correct. Any change
here trades correctness margin for speed, so the design's job is to make that trade explicit and
bound its failure mode.

## Why play evidence is a sound trigger

An achievement unlock requires playing the game. The sync already knows what was played:

```
   GetOwnedGames  ──▶  playtime_forever  ──▶  SessionDiffer  ──▶  diff.playedDeltaByAppId
                       playtime_2weeks                             (SteamSyncWorker.kt:132)
                            │
                            └──▶ both signals free, already fetched, already parsed
```

The important property: **the delta is computed against `lastPlaytime` stored in Room, not against
wall-clock time.** A three-day gap with ten hours of play still produces a delta on the next sync.
Device downtime is not a hole in this scheme, which is what makes tiering viable at all — the sweep
is not needed to catch up.

### Where play evidence is insufficient

Four cases, all real, none frequent:

| Case | Detectable from playtime? |
|---|---|
| Developer patches new achievements into an unplayed game | No |
| Steam's achievement data lagging its playtime data | Not on the same sync |
| A fetch that failed and was swallowed (`AchievementRepository.kt:99`) | No |
| Sub-minute session below playtime rounding | No |

Case 2 is handled by the warm tier — the trailing window means the game is refetched on the next
several syncs, not just the one where the delta appeared. Cases 1, 3, and 4 are what reconciliation
is for.

## The three tiers

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  HOT     playedDeltaByAppId[appId] > 0                                      │
│          fetch this sync                     ~0–2 games                     │
│          signal: computed by SessionDiffer, free                            │
├─────────────────────────────────────────────────────────────────────────────┤
│  WARM    playtime2Weeks > 0                                                 │
│          fetch every sync                    ~3–8 games                     │
│          signal: GetOwnedGames field, free                                  │
│          purpose: absorb Steam's achievement-vs-playtime lag                │
├─────────────────────────────────────────────────────────────────────────────┤
│  COLD    playtimeForever > 0, not hot or warm                               │
│          reconciliation worker, weekly       ~290 games                     │
│          charging + unmetered, resumable                                    │
├─────────────────────────────────────────────────────────────────────────────┤
│  NEVER   playtimeForever == 0                                               │
│          no request, ever                    untouched backlog              │
└─────────────────────────────────────────────────────────────────────────────┘
```

Both tier signals come from `GetOwnedGames`, which the sync already issues. **No request is needed
to decide what to refresh** — that is what makes the steady state ~8 requests rather than ~780.

### Why `playtime_2weeks` for the warm tier

Considered instead: a locally tracked "last delta observed at" timestamp with an explicit decay
window. That is more precise and more controllable. But `playtime_2weeks` is already in the DTO,
already persisted on `Game` (`SteamSyncWorker.kt:145`), needs no new state, and Steam maintains the
window for us. Its two-week span is generous for the lag it is meant to absorb, and the tier's
cost is bounded by how many games a person actually plays — a handful. Precision buys nothing here;
the tier only needs to be small and to include the right games.

If the warm set ever proves large for a heavy player, capping it by descending `playtime_2weeks`
is a cheap follow-up. Worth `log`-ing the tier sizes during rollout to find out.

### Newly added games

A game added to the library and immediately played is hot. A game added and *not* played is
`playtimeForever == 0`, so never-tier — correct, no achievements possible. But a game added with
existing playtime (family sharing, a re-install after a device change, an imported backup) is
neither, and would wait up to a week. Hence the "missing data is still fetched" scenario:
**absence of stored achievement data is itself an eligibility condition**, independent of tier.
This preserves the current behaviour that a game never fetched is always fetched, which is the one
part of the existing wall-clock gate worth keeping.

## Per-data-kind freshness

Three kinds of data currently share one one-hour window:

| Data | Endpoint | Actually changes | Window |
|---|---|---|---|
| Per-player unlock state | `GetPlayerAchievements` | when you play | tier-driven |
| Achievement schema (names, icons, descriptions) | `GetSchemaForGame` | on developer patch | ~30 days |
| Global unlock percentages | `GetGlobalAchievementPercentages` | glacially | ~7 days |

This is where two thirds of the sweep's volume goes, for data that is static or near-static. With
these cached, even the weekly cold pass drops from ~780 requests to ~300 — it becomes one
`GetPlayerAchievements` per game.

**The rarity snapshot is unaffected.** `AchievementMerge` snapshots the global percentage at first
observed unlock and never revises it. Serving that percentage from a seven-day cache rather than a
fresh request changes which value gets snapshotted by an immaterial amount — global rarity across
millions of players does not move meaningfully in a week — and the stability guarantee, which is
what the requirement actually protects, is untouched.

### Where the timestamps live

`Achievement.fetchedAt` is per achievement row, and the current code derives per-game freshness by
aggregating it (`AchievementDao.fetchedAtByApp()`). Schema and global-percentage freshness are
per *game*, not per achievement, so widening every achievement row to carry them would store the
same two timestamps hundreds of times per game.

Preferred: a small `game_achievement_sync` table keyed by `appId`, holding
`schemaFetchedAt`, `globalFetchedAt`, and `playerStateFetchedAt`. One row per game, ~300 rows,
one query to load the whole tiering input. It also gives the reconciliation pass its resumability
marker for free — "which games has this pass already covered" is just `playerStateFetchedAt`
ordering — and it subsumes the existing `NO_ACHIEVEMENTS_MARKER` hack
(`AchievementRepository.kt:111-116`), which currently encodes "checked, nothing here" as a fake
achievement row.

Migration: the marker rows can be translated into sync-metadata rows, or simply dropped and
allowed to re-derive on the first pass. Dropping is simpler and costs one extra request per
achievement-less game, once.

## Keep the tiering logic pure

`AchievementFreshness.selectStaleOrMissing` is already a pure function with unit tests
(`data/achievement/AchievementFreshness.kt:10-18`). Tier selection should replace it in the same
shape — inputs being the owned-games list with playtimes, the delta map, the per-game sync
metadata, and `now`; output being the set to fetch and why. That keeps the interesting logic
testable on the JVM without WorkManager, Room, or a network, which is how the current code is
structured and worth preserving.

## The reconciliation worker

Separate `CoroutineWorker`, weekly, `requiresCharging` + `NetworkType.UNMETERED`. Rationale for
those constraints: the pass is ~300 requests and its latency is irrelevant, so it should cost the
user nothing they can perceive — no battery while in use, no mobile data.

Resumability matters because ~300 requests with bounded concurrency is a few minutes, and
WorkManager can stop a worker at any point. Ordering candidates by ascending
`playerStateFetchedAt` and updating each as it completes makes an interrupted pass resume naturally
rather than restarting.

Plus a Settings action to enqueue it on demand, bypassing interval and constraints — useful for the
player who suspects data is stale, and useful for testing this change.

### Fixing the swallowed cancellation

`runCatching { syncGame(...) }` (`AchievementRepository.kt:99`) catches `CancellationException`, so
a WorkManager stop does not break the loop — it iterates every remaining game, each failing fast.
The same pattern exists at `SteamSyncWorker.kt:90`. The bounded pass must rethrow
`CancellationException` and catch only real failures, otherwise "resumable" is undermined by a
worker that ignores being stopped.

## Bounding the pass

Timeouts first: `NetworkModule.provideOkHttpClient` (`:42-45`) sets **none**, so the Steam client
runs on OkHttp defaults with no `callTimeout` at all. The HLTB client already demonstrates the
intended pattern (`:80-82`). Give the Steam client explicit connect/read/call timeouts.

Then a `Semaphore` around per-game fetches. Modest — 4 to 6 — chosen against OkHttp's default
`maxRequestsPerHost` of 5 and out of courtesy to Steam, not to maximise throughput. The point is to
make duration predictable, not to race. With the cold pass off the interactive path, throughput is
not the constraint anymore.

## The N+1 in the diff path

`SteamSyncWorker.kt:113-126` runs `sessionDao.getOpenSession(appId)` inside `mapValues` over every
owned game: 300 queries per sync, unconditionally. A `getAllOpenSessions()` returning all open rows,
associated by `appId` in memory, is equivalent — open sessions are few, and the existing per-game
query is doing a lookup that a single scan answers. Purely mechanical; the diff output must be
byte-identical, which is worth an explicit test since `SessionDiffer` drives XP.

## Rollout and verification

The correctness risk is missed unlocks, and that is invisible by construction — nothing surfaces an
achievement that failed to appear. So verification should be comparative rather than assertive:

1. **Shadow comparison.** Before switching, run tier selection alongside the existing sweep and
   `log` the difference: games the sweep would fetch that tiering skips, and whether any of those
   actually returned changed data. If tiering's skipped set is consistently unchanged data, the
   assumption holds.
2. **Tier sizes.** `log` hot/warm/cold/never counts per sync during rollout. Warm being routinely
   large would mean revisiting the `playtime_2weeks` choice.
3. **Unlock latency.** Unlock an achievement on device, confirm it appears within one sync interval.
4. **Sync duration.** Confirm the typical sync no longer alternates between ~2s and ~4min.
5. **No silent truncation.** The pass must `log` when it stops early with games uncovered —
   otherwise a chronically interrupted reconciliation reads as "everything reconciled."

Steps 1, 2, and 5 all require observability the app does not have — there is no logging anywhere in
`app/src/main/java`. That is the subject of `add-sync-diagnostics`, which should land first: its
persisted per-run records are what make the shadow comparison and the tier-size checks possible at
all.

It also matters for a reason beyond convenience. **The cost model in this proposal is arithmetic,
not measurement** — ~780 requests and ~4 minutes were derived by counting call sites and assuming a
round-trip latency. `add-sync-diagnostics` task 10 validates exactly that figure against a real
sweep. If the measurement disagrees materially, this change's premise needs revisiting before any
of it is implemented, and that is much better learned from a run record than from having shipped
tiering.
