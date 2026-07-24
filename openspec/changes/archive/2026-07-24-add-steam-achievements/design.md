# Design — Wire Steam achievements end-to-end

## Context

The `:gamification` engine already exposes and unit-tests the full achievement-XP surface
(`RarityTier`, `AchievementInput`, `tierFor`, `achievementXp`, and the defaulted
`achievements` parameter on `xp(games, achievements, cfg)`). The one consumer,
`GamificationUpdater.recompute`, calls `xp(games, cfg = config)` and omits achievements, so
`achievementXp` always returns 0. No Steam achievement data is fetched or stored anywhere
(only `GetOwnedGames`, `GetSteamLevel`, `GetPlayerSummaries` exist in `SteamApi`).

This change is purely app-side plumbing under the engine's existing boundary: the engine
still just consumes already-fetched values, exactly like tracked minutes and HLTB lengths.

## Goals / Non-Goals

**Goals:**
- Fetch per-player unlock state and global unlock percentages from Steam.
- Persist achievements durably, with a rarity snapshot that keeps earned XP stable.
- Feed `List<AchievementInput>` into the existing recompute so achievement XP is real.
- Show per-game achievements (rarity + XP) on a detail screen, and a count on Library rows.

**Non-Goals:**
- Playtime backfill (`add-playtime-backfill`), engine-math changes, a cross-game
  achievements hub, and unlock notifications (all deferred / separate).

## Boundary

```
   steam-sync (this change)                gamification engine (unchanged)      app-ui (this change)
   ────────────────────────                ───────────────────────────────      ────────────────────
   GetPlayerAchievements (per app)     ─▶  AchievementInput(id, unlocked,   ─▶  game-detail screen
   GetGlobalAchievementPercentages…    ─▶    globalUnlockPercent = snapshot)     (rarity + XP per ach.)
   GetSchemaForGame (names/icons)          achievementXp / xp(games, ach.)      Library row "X/Y" count
        │  persist to Room                       │
        ▼                                        ▼
   achievements table (snapshot %)      app persists returned XpState
```

## Decisions

- **Rarity is snapshotted at first observed unlock (resolves the deferred open question).**
  The `Achievement` row stores `snapshotPercent` — the global unlock percent captured the
  first sync that observes `unlocked = true`. XP is computed from `snapshotPercent`, never
  the live `globalPercent`. *Why:* Steam's global percentages drift as more players unlock
  an achievement, so recomputing against the live value could silently downgrade a
  legendary unlock to epic and change historical totals. Snapshotting freezes the tier at
  the moment of accomplishment. Alternative — always use the live percent — rejected because
  it makes past XP non-deterministic. The live `globalPercent` is still stored (refreshed
  each fetch) for display ("currently 3% of players"), just not for XP.
- **Achievement sync is freshness-gated and scoped, not whole-library every poll.**
  `GetPlayerAchievements` is one call per app; fetching the entire owned library on every
  30s poll is untenable. Scope: games the player actually engages with — those with tracked
  sessions plus goal games — refreshed only when stale (a `fetchedAt` cutoff, mirroring the
  HLTB freshness gate). *Why:* bounds API volume to a handful of relevant apps. Alternative —
  fetch all owned games — rejected for rate-limit and latency cost. The scope can widen later
  without schema change.
- **Global percentages and the achievement schema are cached per app.**
  `GetGlobalAchievementPercentagesForApp` (percent per achievement) and `GetSchemaForGame`
  (display name + icon per achievement) are per-app, not per-player, so they are fetched
  once per app and cached alongside the rows. Display name/icon populate the detail screen;
  apiName is the fallback label when the schema is unavailable.
- **A new `steam-achievements` capability, not an extension of `gamification`.**
  The engine capability owns the *rules*; this owns *fetching/persisting/feeding* — an I/O
  and orchestration concern. Keeping them separate preserves the engine's "no I/O" boundary
  in the spec tree, same split as `hltb-data` (ingestion) vs `gamification` (rules).
- **Failures and no-achievement games degrade quietly.** A private profile, a game with no
  achievements, or a per-app 403/500 must not fail the whole sync: that app simply has no
  achievement rows (or keeps its last-good cache), and the rest of the poll proceeds — same
  resilience pattern the sync path already uses for HLTB.
- **Detail screen is a non-tab route with an `appId` argument.** Reached by tapping a
  Library game, following the existing `ROUTE_HLTB_REVIEW` non-tab-route pattern in
  `BacklogiumAppRoot`. *Why:* it's a drill-in, not a top-level destination, so it stays out
  of the bottom nav.

## Data model

`Achievement` entity, composite primary key `(appId, apiName)`, FK `appId → games(appId)`
`ON DELETE CASCADE` (same as `hltb_data` / `sessions`):

| field | type | notes |
|-------|------|-------|
| `appId` | Long | FK to games |
| `apiName` | String | Steam achievement api name (stable id) |
| `displayName` | String? | from GetSchemaForGame; null → show apiName |
| `iconUrl` | String? | unlocked icon from schema |
| `unlocked` | Boolean | from GetPlayerAchievements |
| `unlockedAt` | Long? | epoch seconds from Steam (0 → null) |
| `globalPercent` | Double? | live global unlock %, refreshed each fetch (display) |
| `snapshotPercent` | Double? | global % frozen at first observed unlock (drives XP) |
| `fetchedAt` | Long | freshness gate |

`AchievementInput` mapping: `id = apiName`, `unlocked = unlocked`,
`globalUnlockPercent = snapshotPercent` (so a not-yet-snapshotted or un-tierable
achievement contributes 0, exactly as the engine specifies).

## Risks / Trade-offs

- **[Risk]** Snapshot never gets taken if the very first sync already sees the achievement
  unlocked but Steam returns no global percent for it that sync → `snapshotPercent` stays
  null → 0 XP even though unlocked. → **Mitigation**: on each fetch, if `unlocked` and
  `snapshotPercent` is still null, backfill it from the current `globalPercent` when one is
  available; only the *unlock tier* is frozen, and it freezes the first sync a percent is
  actually known.
- **[Risk]** Scoping to played + goal games means an achievement unlocked in a game the
  player hasn't touched since install contributes no XP until that game is in scope. →
  **Mitigation**: accepted; scope is wideable later. Documented, not silent.
- **[Trade-off]** Extra per-app Steam calls add sync latency and rate-limit pressure. →
  **Mitigation**: freshness gate + narrow scope keep it to a few apps per sync.
- **[Risk]** Room schema migration (v2 → v3). → **Mitigation**: additive `CREATE TABLE`
  only, no existing table altered, same shape as the v1 → v2 `hltb_data` migration.

## Migration Plan

Additive Room migration `MIGRATION_2_3`: `CREATE TABLE achievements (...)` with the FK and
composite PK above; bump `BacklogiumDatabase.version` to 3 and register the migration. No
existing data altered or backfilled. Rollback: destructive fallback already configured on
the builder; dropping the table loses only re-fetchable cache.

## Open Questions

- Should achievement sync scope widen to the full owned library behind a manual "refresh
  achievements" action (like the HLTB batch refresh), for players who want completeness over
  API frugality? Flagged for a follow-up; not built here.
- Should a 100%-of-a-game's-achievements completion grant a bonus on top of per-achievement
  tiers? Still deferred (was already open in `add-achievement-xp`); out of scope here.
