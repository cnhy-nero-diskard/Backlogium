## Context

See proposal.md - Why. Two existing code paths are involved:

- `FamilySharedGameRepository.importManually` → `verifyStoreAndImport` → `probePlayerData`
  (`data/repo/FamilySharedGameRepository.kt`) calls `SteamApi.getPlayerAchievements` once after a
  successful manual import, but only counts the returned DTOs for a toast message. It never calls
  `AchievementRepository`.
- `AchievementRepository.applyRefresh` (`data/repo/AchievementRepository.kt`) is the only code path
  that writes real `Achievement` rows via `AchievementMerge.merge` + `AchievementDao.upsertAll`, and
  upserts `GameAchievementSync`. It is reached today only from `AchievementRepository.syncLibraryGames`
  during a full sync, which itself is driven by `AchievementFreshness.selectByTier`
  (`data/achievement/AchievementFreshness.kt`).
- `AchievementFreshness.selectByTier` classifies a game as `NEVER` — "no recorded playtime" —
  whenever its `playtimeForever` input is zero, and NEVER games are excluded from fetching
  entirely (not just deferred). For an owned game this input is Steam's own `playtimeForever`, an
  accurate signal. For a family-shared game, per the archived `add-family-shared-games` design,
  this input is *locally tracked session minutes*, because Steam reports no owned-library playtime
  for a game the player doesn't own. `game-sources`' existing requirement already establishes that
  tracked time for a shared game is "observed, not total" — so zero tracked minutes does not mean
  zero real play, only that Backlogium never watched it happen (e.g. it was completed before the
  game was admitted, or while presence monitoring was off). Feeding that weaker signal into a rule
  designed around Steam's authoritative signal is what silently and permanently excludes a
  completed shared game from ever having its achievements fetched.

## Goals / Non-Goals

**Goals:**
- Manual paste-link import persists real achievement data instead of a throwaway probe.
- Admission of a family-shared game (either path) triggers a one-time achievement fetch that does
  not depend on locally tracked playtime being nonzero.
- Family-shared games already in the library with no stored achievement data become eligible for a
  bounded backfill, without a full unbounded reconciliation sweep.

**Non-Goals:**
- No change to `SmartCollections`'s Completed rule or any other derived-collection predicate — its
  achievements-first/playtime-fallback logic, and its treatment of "not fetched" as unknown, are
  already correct once the underlying data gap is fixed.
- No change to `HltbContributionExporter`'s owned-only gating of the crowd-sourced HLTB
  contribution export — out of scope, left as-is.
- No change to the NEVER-tier rule for *owned* games; `playtimeForever == 0` remains authoritative
  there.
- No change to how tracked playtime for shared games is computed or disclosed — only to what
  triggers an achievement fetch.

## Decisions

### 1. Reuse `AchievementRepository.applyRefresh` for a single-game, source-agnostic fetch-and-persist

Rather than duplicating merge/persist logic inside `FamilySharedGameRepository`, expose a
single-game entry point on `AchievementRepository` (e.g. `refreshOne(appId)`) that both the manual
import path and the new admission-triggered fetch call. This is the same code path
`syncLibraryGames` already uses per-game internally, so behavior — schema caching, rarity
snapshotting, retirement handling — is identical to a normal sync's fetch for that one game.
Alternative considered: keep `probePlayerData`'s direct `SteamApi` call and hand its result to
`AchievementMerge` inline in `FamilySharedGameRepository`. Rejected — it would duplicate the merge
call site and schema-freshness handling that `applyRefresh` already owns, and any future change to
merge/persist semantics would need to be made in two places.

### 2. Family-shared games skip the `NEVER` tier entirely; owned games are unaffected

`AchievementFreshness.selectByTier` takes `OwnedGame.playtimeForever` as an undifferentiated signal
today. The fix threads the game's source into tier selection so a family-shared game is never
classified `NEVER` on the strength of zero playtime alone — it instead always lands at minimum in
the missing-data-eligible set when it has no stored sync metadata, regardless of playtime. Owned
games keep the existing behavior unchanged: `playtimeForever == 0` still means `NEVER`, still never
fetched. This keeps the pure, unit-tested `AchievementFreshness` function as the single place tier
logic lives, rather than special-casing shared games at each call site.

Alternative considered: treat every family-shared game as permanently "hot" or "warm" so it is
refreshed on every sync. Rejected — most shared games have no achievement changes between syncs;
this would repeatedly re-fetch settled data for no benefit. The one-time-on-admission-plus-backfill
approach fetches once and then falls back to the same delta/recency signals as any other game for
subsequent refreshes (a shared game's *derived sessions*, once observed, do produce nonzero tracked
minutes and participate in hot/warm classification normally from then on).

### 3. Manual import fetches synchronously; automatic admission relies on Decision 2 plus the next sync, not a presence-layer fetch

Manual paste-link import already receives `apiKey`/`steamId` as call parameters (the player is in
Settings, actively waiting on a result), so it calls the Task 1 entry point synchronously and the
toast reflects genuinely persisted data.

Automatic presence-based admission (`PresenceSessionRecorder` → `FamilySharedGameRepository.
considerAdmission` → `admit`) is different: it runs on every successful presence observation, on a
tight polling cadence (`LiveStatusRepository`'s ~30s tick), and has no Steam Web API key or steamId
in scope — those live in `SettingsDataStore`/credentials storage, not on this call path, and it is
not a resting point where a new async work item should be enqueued (WorkManager one-offs, a new
credential lookup, and a new failure/retry surface, all for a path that already has one). Rather
than thread credentials through a hot 30-second loop, this design leans entirely on Decision 2:
once a family-shared game is admitted, it has no `GameAchievementSync` row and (per Decision 2)
is never excluded as `NEVER` regardless of tracked playtime, so it is missing-data eligible from
the moment it is admitted — and picked up automatically at the *library's very next periodic sync*
(`SteamSyncWorker`, which already includes shared games in its achievement scope; ordinarily within
its 15-minute schedule), with no separate enqueue step required. This is "eligible immediately,
fetched at the next sync" rather than "fetched at the instant of admission" — a difference in
degree, not in whether the game is ever reachable, and it costs no new failure mode or credential
plumbing for a path that already runs unattended in the background.

Both paths persist the `Game` row before any achievement fetch is attempted, exactly as today, so a
slow or failing Steam achievement request never blocks or fails admission — consistent with
`steam-achievements`' existing "Achievement fetch fails for a game" requirement (skip without
failing the overall operation, leave prior data intact).

### 4. Backfill is a bounded, resumable pass, not a blanket reconciliation trigger

Rather than enqueueing a full reconciliation pass (which already exists and is unbounded/whole
library, gated on device conditions), the backfill for already-admitted family-shared games reuses
the existing `MISSING_DATA_CAP`-bounded mechanism: on the next sync, family-shared games with no
`GameAchievementSync` row are included in the missing-data-eligible set regardless of tier,
oldest-admitted-first, same as any other missing-data game. This avoids introducing a second bulk
migration path with its own scheduling and cancellation semantics — it rides the existing bounded,
per-sync mechanism the spec already describes ("Missing-data eligibility is bounded per sync").

## Risks / Trade-offs

- **A shared game genuinely without achievements still costs one wasted fetch on admission.** →
  Accepted; `steam-achievements`' "Game has no achievements" scenario already handles this without
  error, and it is a single one-time request, not a recurring cost.
- **Backfilling existing NEVER-classified shared games could, in a library with many long-dormant
  shared games, exceed the per-sync missing-data cap for a while.** → Accepted per Decision 4: the
  existing oldest-first, bounded, multi-sync catch-up behavior already handles this for any other
  cause of a missing-data backlog (first install, restore from backup); shared games join the same
  queue rather than getting a separate unbounded path.
- **Threading source into `AchievementFreshness.selectByTier` changes a pure, unit-tested
  function's signature.** → Mitigated by keeping owned-game behavior byte-for-byte identical and
  adding new unit test cases for the family-shared branch alongside the existing ones.
