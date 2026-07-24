# Tasks — Opt-in historical playtime backfill

> The `:gamification` engine is unchanged. Because `gameXp` tapers over *cumulative* minutes,
> backfill is just a larger `minutesPlayed` fed to the existing call — no engine edits.

## 1. Persistence
- [x] 1.1 Add `backfillMinutes: Int = 0` to the `Game` entity
- [x] 1.2 Add a persisted "history imported" flag (e.g. `playtimeBackfilled: Boolean` on
  `PlayerProfile`, or a settings key)
- [x] 1.3 Bump `BacklogiumDatabase` version; add an additive migration
  (`ALTER TABLE games ADD COLUMN backfillMinutes INTEGER NOT NULL DEFAULT 0`, plus the flag's
  column if stored in Room). No data rewrite; default 0 preserves current behavior
- [x] 1.4 `GameDao`: query/update to persist per-game `backfillMinutes` in a batch

## 2. Backfill action (domain)
- [x] 2.1 A use-case that, when the flag is unset: for each game computes
  `backfillMinutes = max(0, playtimeForever − trackedMinutes(appId))`, persists it, sets the
  imported flag, and triggers `GamificationUpdater.recompute`
- [x] 2.2 Idempotence: if the flag is already set, the use-case is a no-op (no re-import, no
  double-count)

## 3. Feed the offset into XP
- [x] 3.1 `GamificationUpdater.recompute`: build `GamePlaytimeInput.minutesPlayed =
  game.backfillMinutes + trackedMinutes(appId)` (join per-game backfill with tracked minutes)
- [x] 3.2 Confirm untouched behavior when `backfillMinutes = 0` everywhere (pre-import installs
  compute exactly as today)
- [x] 3.3 Update `GamificationUpdaterTest`: a game with `backfillMinutes` set yields the
  cumulative-tapered XP for the combined total; a zero-backfill game reproduces the current
  playtime-only totals (regression guard)
- [x] 3.4 `SteamSyncWorker.persistPoll`: preserve `backfillMinutes` when rebuilding each `Game`
  row from the poll DTO (carry it over from the existing row, like `isGoal`/`targetMinutes`).
  Without this the next sync resets every offset to 0 and recompute erases all imported XP

## 4. UI (app-ui)
- [x] 4.1 An "Import Steam history" control that invokes the backfill use-case, reflecting
  not-imported vs imported state from the flag
- [x] 4.2 A brief confirmation before importing, noting it counts past playtime toward XP,
  is one-time, and that unmatched (no-HLTB) games count in full while matched games are capped
- [x] 4.3 Reflect the resulting XP/level change on Home after import (existing observers
  already update; verify no stale state)
- [x] 4.4 A "Reset import" control (shown in the imported state) that undoes the import via a
  use-case `reset()` (clear offsets, unset flag, recompute), behind a confirmation; afterward
  the import is offered again. Serves as the recovery path for the sync-wipe bug

## 5. Validation
- [x] 5.1 `:app` compiles; `:app:testDebugUnitTest` passes
- [ ] 5.2 On device: with pre-install playtime, tap Import; confirm XP/level jump is bounded
  for HLTB-matched games and reflects history; verify a second tap does nothing
- [ ] 5.3 On device: confirm ongoing play after import still accrues XP without re-importing
