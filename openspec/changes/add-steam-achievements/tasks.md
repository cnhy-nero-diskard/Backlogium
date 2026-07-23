# Tasks — Wire Steam achievements end-to-end

> The `:gamification` engine surface (`AchievementInput`, `tierFor`, `achievementXp`,
> `xp(games, achievements, cfg)`) already exists and is unit-tested (archived
> `add-achievement-xp`). This change is app-side wiring only — no engine math changes.

## 1. Steam API (per-app achievement endpoints)
- [ ] 1.1 Add `getPlayerAchievements(key, steamId, appId)` to `SteamApi`
  (`ISteamUserStats/GetPlayerAchievements/v1/`) with its response DTO (apiName, achieved,
  unlocktime)
- [ ] 1.2 Add `getGlobalAchievementPercentages(gameId)` to `SteamApi`
  (`ISteamUserStats/GetGlobalAchievementPercentagesForApp/v2/`) with its DTO (name, percent)
- [ ] 1.3 Add `getSchemaForGame(key, appId)` to `SteamApi`
  (`ISteamUserStats/GetSchemaForGame/v2/`) with its DTO (displayName, icon per apiName);
  tolerate games that expose no achievement schema

## 2. Persistence (Room)
- [ ] 2.1 `Achievement` entity — composite PK `(appId, apiName)`, FK `appId → games(appId)`
  `ON DELETE CASCADE`; fields `displayName?`, `iconUrl?`, `unlocked`, `unlockedAt?`,
  `globalPercent?`, `snapshotPercent?`, `fetchedAt`
- [ ] 2.2 `AchievementDao` — upsert; query by appId; unlocked/total counts per appId (for the
  Library row); observe a game's achievements (for the detail screen)
- [ ] 2.3 Register `Achievement` in `BacklogiumDatabase`, bump version 2 → 3, add additive
  `MIGRATION_2_3` (`CREATE TABLE achievements …`, no existing table altered); provide the DAO
  in `DatabaseModule`

## 3. Fetch + persist pipeline
- [ ] 3.1 `AchievementRepository` (or extend the Steam repo): given an appId, fetch player
  achievements + global percentages (+ schema), merge into `Achievement` rows
- [ ] 3.2 Snapshot rule: on merge, if `unlocked` and `snapshotPercent` is null, set
  `snapshotPercent` from the currently-known `globalPercent`; never overwrite an existing
  snapshot; always refresh `globalPercent` for display
- [ ] 3.3 Scope + freshness gate: determine in-scope appIds (games with tracked sessions +
  goal games) whose achievement data is stale/missing, and fetch only those
- [ ] 3.4 Resilience: a per-game fetch failure (private profile, no stats, transport error)
  is skipped without failing the sync and leaves prior rows intact; a game with no
  achievements is recorded as such
- [ ] 3.5 Invoke the achievement sync from `SteamSyncWorker` alongside the owned-games poll

## 4. Feed the engine
- [ ] 4.1 Inject `AchievementDao` into `GamificationUpdater`; build `List<AchievementInput>`
  (`id = apiName`, `unlocked`, `globalUnlockPercent = snapshotPercent`)
- [ ] 4.2 Pass the list to `Gamification.xp(games, achievements, cfg)` in `recompute`
  (currently omits it → achievement XP is always 0)
- [ ] 4.3 Update `GamificationUpdaterTest`: fake `AchievementDao`; assert combined XP =
  playtime XP + achievement XP, and that an empty achievement set reproduces the current
  playtime-only totals (regression guard)

## 5. UI (app-ui)
- [ ] 5.1 Game-detail route with an `appId` argument (non-tab route, following the
  `ROUTE_HLTB_REVIEW` pattern in `BacklogiumAppRoot`); wire Library row taps to navigate
- [ ] 5.2 `GameDetailViewModel` + `GameDetailScreen`: list the game's achievements with
  unlock state, rarity tier, and contributed XP (via engine `tierFor` + `RuleConfig`),
  using display name/icon when present; empty-state when no achievements
- [ ] 5.3 Add a compact "unlocked / total" achievement count to Library rows
  (`LibraryViewModel` + `LibraryScreen`), shown only when achievement data exists

## 6. Validation
- [ ] 6.1 `:app` compiles; `:app:testDebugUnitTest` passes (engine + updater tests green)
- [ ] 6.2 On device: play a game with achievements, sync, confirm unlocked count appears on
  its Library row and the detail screen lists rarity + XP, and total XP reflects the
  achievement contribution
- [ ] 6.3 On device: verify a private-profile / no-achievements game does not break the sync
  or the Library
