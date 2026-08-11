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
                       playtime_2weeks                             (SteamSyncWorker.kt:187-188)
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
| A fetch that failed and was swallowed (`AchievementRepository.kt:130`) | No |
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
to decide what to refresh** — that is what makes the steady state ~12–16 requests rather than ~780.

### Why `playtime_2weeks` for the warm tier

Considered instead: a locally tracked "last delta observed at" timestamp with an explicit decay
window. That is more precise and more controllable. But `playtime_2weeks` is already in the DTO,
already persisted on `Game` (`SteamSyncWorker.kt:169`), needs no new state, and Steam maintains the
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

### The missing-data override must be capped

Left unbounded, that override contradicts this change's own `steam-sync` requirement — inline
requests "proportional to recently played games, not to the library." The override is bounded by how
many games lack data, and there are two ordinary situations where that is *every game*:

```
  ┌───────────────────────────────┬──────────────────────────────────────────────┐
  │ first-ever sync               │ no metadata rows exist yet, whole library     │
  │                               │ has playtime from GetOwnedGames → all eligible │
  ├───────────────────────────────┼──────────────────────────────────────────────┤
  │ first sync after a restore    │ same, and worse: see below                    │
  └───────────────────────────────┴──────────────────────────────────────────────┘
```

Both reintroduce exactly the ~780-request inline sweep this change exists to remove, including its
proximity to the 10-minute worker ceiling. Task 5.3 half-catches this — it checks that a baseline
sync triggers no *play-driven* refresh, which is true, while the override fires library-wide on that
same run.

So the override is capped at a modest number of games per sync (**25**, ~50–75 requests, a few
seconds), ordered oldest-first like the reconciliation pass. A 300-game fresh install converges in
~12 syncs — about three hours at the 15-minute interval — and reconciliation covers the tail anyway.
The cap is what makes the bounded-inline-work requirement true by construction rather than true by
assuming the uncovered set is small.

### Restore must not seed metadata rows

`add-backup-restore` landed after this design was first written, and it interacts in a way worth
stating because the intuitive fix is the wrong one.

`BackupMergeEngine.mergeAchievement` (`:159-181`) restores only *unlocked* achievements, with no
`globalPercent` and no schema, and stamps `fetchedAt = existing?.fetchedAt ?: now`. Under today's
scheme that per-row stamp incidentally makes restored games look *fresh* for an hour. Moving freshness
into `game_achievement_sync` removes that incidental suppression — the table has no rows, so every
restored game reads as never-fetched.

The tempting fix is to have restore seed `game_achievement_sync` rows. **That would be wrong.**
Restored achievement data is deliberately partial: locked achievements are absent by design, as are
percentages and schema. Seeding a `playerStateFetchedAt` would mark those games as covered and
permanently hide every locked achievement in the restored library until the game happened to be
played again.

Correct handling is the opposite — leave the metadata absent so the games are genuinely eligible, let
the capped override drain them a few per sync, and **enqueue the reconciliation pass at restore
completion** so the full set converges as soon as charging and wifi allow rather than waiting out the
weekly interval.

## Per-data-kind freshness

Three kinds of data currently share one one-hour window:

| Data | Endpoint | Actually changes | Window |
|---|---|---|---|
| Per-player unlock state | `GetPlayerAchievements` | when you play | tier-driven |
| Achievement schema (names, icons, descriptions) | `GetSchemaForGame` | on developer patch | ~30 days |
| Global unlock percentages | `GetGlobalAchievementPercentages` | glacially | **none — always fresh** |

Only the schema is cached. That is a deliberate narrowing of this design's original position, which
also put global percentages on a ~7-day window; see below for why that was withdrawn.

The schema is the safe half of the idea: names, descriptions, and icons change only when a developer
patches the game, nothing derives a number from them, and no requirement anywhere asserts they are
current. Caching it removes one request per game with achievements — roughly a third of the sweep's
volume — with no correctness surface at all.

### Why global percentages are *not* cached

The original reasoning here considered only the rarity snapshot, and for that it was sound:
`AchievementMerge` snapshots the global percentage at first observed unlock and never revises it
(`AchievementMerge.kt:39`), so serving a week-old percentage would change which value got frozen by
an immaterial amount. The stability guarantee is what that requirement protects, and it was untouched.

**But the snapshot is no longer the only consumer.** `rarity-standing`, added after this design was
first written, derives a provable bound from the *live* percentages, and its spec is explicit that
currency is the point (`openspec/specs/rarity-standing/spec.md`, "Derived from currently published
rates"):

> **THEN** the bound is derived from the current rates, not from any rate value persisted at the time
> the player unlocked an achievement, because the bound describes the owner population as it stands.

`GameDetailViewModel.kt:294` feeds exactly that live field in — `globalUnlockPercents =
achievements.map { it.globalPercent }`. The existing `steam-achievements` spec makes a smaller
version of the same claim: "the current global percentage is updated for display."

```
   design's model, as written              actual consumers
   ───────────────────────────             ────────────────
   globalPercent ─▶ snapshot               globalPercent ─▶ snapshot        stable, cache-safe
                    ↑ frozen, stable                     ─▶ rarity-standing  wants CURRENT
                    ∴ a cache is safe                    ─▶ locked-row display
```

Numerically the conflict is mild — global rates across millions of owners barely move in a week, so a
cached bound would be near-identical. It is withdrawn on cost/benefit rather than on accuracy:

| | cold pass (weekly, on charger) | typical sync |
|---|---|---|
| schema + global cached | ~300 req | ~8 req |
| **schema only (chosen)** | **~520 req** | **~12–16 req** |
| neither cached | ~780 req | ~20+ req |

The global cache's only material beneficiary is the weekly reconciliation pass — which is by design
charging on unmetered wifi, precisely where 520 requests versus 300 costs the user nothing they can
perceive. On an interactive sync it saves a handful of requests against a handful of warm games. So
the whole `rarity-standing` conflict was being carried for savings on the one pass built not to care.

Two orders of magnitude survives either way, which is the claim the change actually rests on.

This also shrinks the metadata row to two timestamps instead of three.

### Where the timestamps live

`Achievement.fetchedAt` is per achievement row, and the current code derives per-game freshness by
aggregating it (`AchievementDao.fetchedAtByApp()`). Schema freshness is per *game*, not per
achievement, so widening every achievement row to carry it would store the same timestamp hundreds
of times per game.

Preferred: a small `game_achievement_sync` table keyed by `appId`, holding `schemaFetchedAt` and
`playerStateFetchedAt`. One row per game, ~300 rows, one query to load the whole tiering input. It
also gives the reconciliation pass its resumability marker for free — "which games has this pass
already covered" is just `playerStateFetchedAt` ordering — and it subsumes the existing
`NO_ACHIEVEMENTS_MARKER` hack (`AchievementRepository.kt:142-147`), which currently encodes
"checked, nothing here" as a fake achievement row.

The database is at version 13 with hand-written migrations throughout
(`BacklogiumDatabase.kt:45,67-264`), so this is a 13 → 14 migration in that established style.

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
those constraints: the pass is ~520 requests and its latency is irrelevant, so it should cost the
user nothing they can perceive — no battery while in use, no mobile data.

Resumability matters because ~520 requests with bounded concurrency is a few minutes, and
WorkManager can stop a worker at any point. Ordering candidates by ascending
`playerStateFetchedAt` and updating each as it completes makes an interrupted pass resume naturally
rather than restarting.

Plus a Settings action to enqueue it on demand, bypassing interval and constraints — useful for the
player who suspects data is stale, and useful for testing this change.

### Fixing the swallowed cancellation

`runCatching { syncGame(...) }` (`AchievementRepository.kt:130`) catches `CancellationException`, so
a WorkManager stop does not break the loop — it iterates every remaining game, each failing fast.
The bounded pass must rethrow `CancellationException` and catch only real failures, otherwise
"resumable" is undermined by a worker that ignores being stopped.

The matching defect in `SteamSyncWorker`'s outer catch is **already fixed**: it now has a dedicated
`catch (e: CancellationException)` that records the run as incomplete and rethrows
(`SteamSyncWorker.kt:108-110`). `add-sync-diagnostics` shared this task and got there first, as it
anticipated it might. Only the per-game `runCatching` in `AchievementRepository` is outstanding.

## Bounding the pass

Timeouts first: `NetworkModule.provideOkHttpClient` (`:35-40`) sets **none**, so the Steam client
runs on OkHttp defaults with no `callTimeout` at all — still true after `add-sync-diagnostics` added
its `RedactingTimingInterceptor` there. The HLTB client already demonstrates the intended pattern
(`:82-84`). Give the Steam client explicit connect/read/call timeouts.

Then a `Semaphore` around per-game fetches. Modest — 4 to 6 — chosen against OkHttp's default
`maxRequestsPerHost` of 5 and out of courtesy to Steam, not to maximise throughput. The point is to
make duration predictable, not to race. With the cold pass off the interactive path, throughput is
not the constraint anymore.

## The N+1 in the diff path

`SteamSyncWorker.kt:137-150` runs `sessionDao.getOpenSession(appId)` inside `mapValues` over every
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

Steps 1, 2, and 5 all require observability the app did not have when this design was written. That
was the subject of `add-sync-diagnostics`, which has since landed (archived 2026-08-04): its
persisted per-run records, the per-endpoint breakdown, and the diagnostics surface that renders them
are what make the shadow comparison and the tier-size checks possible at all. Timber is available for
freeform logging in debug builds, but the `log`-ing called for above should go to the persisted
records rather than the platform log — those are what survive to be compared.

### The premise gate is satisfied

The cost model in this proposal was arithmetic — ~780 requests and ~4 minutes derived by counting call
sites and assuming a round-trip latency. `add-sync-diagnostics` task group 10 existed to validate that
against a real sweep. The measured figures have now been recovered from the on-device diagnostics
history (emulator-5554, retrieved 2026-08-11) and are recorded below.

**Representative full sweep — sync run #2**

| Property | Value |
|---|---|
| Trigger | `retry` |
| Started | 2026-08-11 00:51:35 local |
| Games examined / updated | 302 / 302 |
| Total requests | **847** |
| Wall duration | **102,033 ms (~102 s, ~1.7 min)** |

**Per-endpoint breakdown (run #2)**

| Endpoint | Status | Count | Duration (ms) |
|---|---|---:|---:|
| `GetPlayerAchievements` | 200 | 260 | 57,380 |
| `GetPlayerAchievements` | 400 | 35 | 9,170 |
| `GetPlayerAchievements` | 403 | 5 | 1,321 |
| `GetPlayerAchievements` | 500 | 2 | 2,197 |
| `GetGlobalAchievementPercentagesForApp` | 200 | 256 | 8,985 |
| `GetSchemaForGame` | 200 | 256 | 9,377 |
| `GetOwnedGames` | 200 | 1 | 230 |
| `GetPlayerSummaries` | 200 | 5 | 3,407 |
| `GetPlayerSummaries` | (no status) | 1 | 1,556 |
| `GetSteamLevel` | 200 | 1 | 35 |
| `store.steampowered.com/api/appdetails` | 200 | 25 | 5,977 |

Achievement-related requests (`GetPlayerAchievements` + `GetGlobalAchievementPercentagesForApp` +
`GetSchemaForGame`) total **814**, which lands right on the proposal's estimate of ~780 for a ~300-game
library. The actual wall duration was ~102 seconds rather than the estimated ~4 minutes, but the
request volume is the controlling cost: it is large, concentrated, and dominated by the three
per-game achievement endpoints.

The alternating fast/slow pattern is also visible in the same history. Run #3, the next scheduled sync
after the sweep, issued only three successful non-failure requests (`GetOwnedGames`, `GetPlayerSummaries`,
`GetSteamLevel`) plus the same 42 failing `GetPlayerAchievements` calls that the sweep had already
encountered. The sweep's clustered staleness is real, and the cost model is close enough to the
estimate that the tiering/caching/reconciliation shape of this change stands as written.

Because the measurement agrees with the premise, the full scope — per-game metadata, tier selection,
per-kind freshness, reconciliation worker, timeouts/concurrency, and the session N+1 fix — remains
justified. If a future measurement on a different library lands materially smaller, that is the point
at which to reconsider scope; for this device, the gate is closed.
