# Wire Steam achievements end-to-end

## Why

The `:gamification` engine already computes rarity-tiered achievement XP
(`RarityTier`, `AchievementInput`, `achievementXp`, and the `achievements` parameter on
`xp()`), fully unit-tested — but no app code ever feeds it real data, so achievement XP
is permanently zero. Steam already exposes both signals needed (per-player unlock state
and global unlock percentages); this change is the app-side fetch → persist → feed →
display wiring that turns the dormant engine surface into a live feature.

## What Changes

- **Steam API**: add `GetPlayerAchievements` (per-player unlock state per app) and
  `GetGlobalAchievementPercentagesForApp` (global unlock % per achievement) to the Steam
  data layer, following the same client/boundary as the existing `GetOwnedGames` calls.
- **Persistence**: a new Room `Achievement` entity + DAO keyed by `(appId, apiName)`,
  storing unlock state, unlock time, the current global unlock percent, and a **rarity
  percent snapshotted at first observed unlock**. Registered in `BacklogiumDatabase` with
  a schema migration.
- **Rarity-drift policy (resolves a deferred open question)**: achievement XP is computed
  from the snapshotted-at-unlock percent, not the live one, so a Steam global-percent
  drift never silently changes already-earned XP. This was the open question left
  unanswered by `add-achievement-xp` and `add-gamification-engine`.
- **Feed the engine**: `GamificationUpdater.recompute` builds `List<AchievementInput>`
  from the achievement table and passes it to `Gamification.xp(games, achievements, cfg)`,
  which today omits the argument (so achievement XP is always 0).
- **UI**: a new **game-detail screen** reachable by tapping a Library game — lists that
  game's achievements with rarity tier and XP — plus a compact "X/Y achievements" count on
  existing Library rows.

## Capabilities

### New Capabilities
- `steam-achievements`: fetching per-player and global Steam achievement data, persisting
  it (with a first-unlock rarity snapshot), and feeding it into the gamification recompute
  so unlocked achievements contribute XP.

### Modified Capabilities
- `app-ui`: adds a game-detail screen surfacing per-game achievements with rarity/XP, and
  a per-game achievement count on Library rows. No existing UI requirement's behavior
  changes — additive.

## Impact

- **Affected code (new):** a Steam achievements API surface and a repository, an
  `Achievement` Room entity + DAO, a `BacklogiumDatabase` version bump + migration, a
  game-detail screen + ViewModel, and a navigation route from Library.
- **Affected code (modified):** `SteamApi`/Steam data layer (two new endpoints);
  `SteamSyncWorker`/sync path (fetch achievements alongside owned games);
  `GamificationUpdater.recompute` (build and pass `List<AchievementInput>`);
  `LibraryScreen`/`LibraryViewModel` (achievement count on rows, tap-through navigation).
- **Consumes the already-shipped engine surface** (`AchievementInput`, `achievementXp`,
  `xp(games, achievements, cfg)`); the locked engine math is unchanged by this change.
- **Depends on:** the archived `add-achievement-xp` engine surface. Independent of the
  sibling `add-playtime-backfill` change.

## Non-goals

- **Playtime backfill** — retroactively awarding XP for pre-install playtime is the
  separate `add-playtime-backfill` change.
- **Changing the locked engine math** — tier boundaries, per-tier XP, and the level
  curve are unchanged; this change only supplies inputs and displays outputs.
- **A cross-game achievements hub/tab** — this change scopes achievement display to the
  per-game detail screen and a Library-row count only.
- **Achievement-unlock notifications/toasts** — deferred; not part of this wiring.
