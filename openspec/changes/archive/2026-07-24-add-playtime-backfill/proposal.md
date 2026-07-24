# Opt-in historical playtime backfill

## Why

The app deliberately baselines each game's playtime on first sync (`SessionDiffer.baseline`
records `playtimeForever` and creates no sessions), so XP only ever reflects playtime tracked
*after* install. A player with hundreds of pre-install hours sees goal progress from that
history but earns zero XP for it — which reads as the app "not counting" games they've
already sunk time into. This change lets a player **opt in** to importing that history so it
counts toward XP, once, on demand — without changing the default "earn from now on" behavior.

## What Changes

- A one-time, user-initiated **"Import Steam history"** action that retroactively feeds each
  game's total Steam playtime into XP.
- Historical minutes run through the **existing per-game diminishing-returns taper**, so XP
  is naturally bounded — a game caps at 2× its HowLongToBeat completionist average regardless
  of how many lifetime hours it holds. No new capping logic; the engine math is unchanged.
- Per-game frozen **backfill minutes** persisted on the `Game` row (the historical portion,
  captured at opt-in), so the import is a one-time event: ongoing tracked sessions accrue on
  top of it, and re-reading Steam's growing total never re-imports.
- A persisted flag marking backfill as done, making the action **idempotent** (running it
  again is a no-op) and letting the UI reflect imported vs not-yet-imported state.

## Capabilities

### New Capabilities
- `playtime-backfill`: the one-time, opt-in import of historical Steam playtime into the XP
  total, including how historical minutes combine with tracked minutes when XP is computed
  and the idempotence guarantee.

### Modified Capabilities
- `app-ui`: adds the opt-in "Import Steam history" control and its imported/not-imported
  state. No existing UI requirement's behavior changes — additive.

## Impact

- **Affected code (new):** a backfill use-case/domain action, a persisted "backfilled" flag,
  and the opt-in UI control (likely on Home or a settings surface).
- **Affected code (modified):** `Game` entity gains a `backfillMinutes` column
  (`BacklogiumDatabase` version bump + additive migration); `GamificationUpdater.recompute`
  adds each game's `backfillMinutes` to its tracked minutes when building
  `GamePlaytimeInput.minutesPlayed`; `GamificationUpdaterTest` updated.
- **Consumes the already-shipped engine** (`gameXp`/`xp`) unchanged — because `gameXp` is a
  cumulative function of total minutes, passing `backfillMinutes + trackedMinutes` yields the
  correctly tapered total with no engine change.
- **Independent of** `add-steam-achievements`; both extend the same recompute but touch
  different inputs.

## Non-goals

- **Automatic backfill on install** — explicitly rejected in favor of an opt-in action, so
  the default experience still earns XP from the install point forward.
- **Backfilling achievements** — achievement retroactivity is inherent to the first fetch and
  is owned by `add-steam-achievements`, not this change.
- **Changing the baseline/session mechanics** — sessions and the diff baseline are unchanged;
  backfill is an additive per-game offset, not a rewrite of history into synthetic sessions.
- **Changing the engine math** — the taper, tiers, and level curve are untouched.
