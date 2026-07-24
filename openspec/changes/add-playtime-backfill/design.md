# Design — Opt-in historical playtime backfill

## Context

`SessionDiffer.baseline` records each game's `playtimeForever` as `lastPlaytime` on first
sync and synthesizes **no** sessions; only post-baseline deltas become `Session` rows. XP is
`Σ gameXp(SUM(session.minutes per game), completionistAverage, cfg)`, so pre-install hours
never reach XP. Goal progress, separately, uses total `playtimeForever` — so history already
shows there, which is exactly why its absence from XP feels inconsistent.

The engine's `gameXp(M, T, cfg)` is a closed-form **cumulative** function of total minutes
`M`: it is *not* additive across chunks (`gameXp(a+b) ≠ gameXp(a)+gameXp(b)`). That single
property makes backfill clean: to count history, we just pass a larger `M` — the taper does
the rest — with no change to the engine.

## Goals / Non-Goals

**Goals:**
- Let a player opt in, once, to counting pre-install Steam playtime toward XP.
- Keep XP bounded via the existing taper; add no new capping rule.
- Make the import a one-time, idempotent event that coexists with ongoing session tracking.
- Leave the default (no backfill) behavior and the baseline/session mechanics untouched.

**Non-Goals:**
- Automatic backfill, achievement backfill, session-history rewriting, engine-math changes
  (all rejected / owned elsewhere — see proposal Non-goals).

## Decisions

- **Backfill is a frozen per-game offset, not a re-read of the live total.** At opt-in, each
  game's historical minutes are captured as `backfillMinutes = (playtimeForever −
  trackedMinutesSoFar).coerceAtLeast(0)` and persisted on the `Game` row. Recompute then
  feeds `minutesPlayed = game.backfillMinutes + trackedMinutes(appId)`. *Why:* freezing the
  historical portion makes the import genuinely one-time — future play accrues as normal
  sessions on top, and Steam's ever-growing `playtimeForever` is never re-imported (which
  would double-count). Alternative — always feed live `playtimeForever` as `minutesPlayed` —
  rejected: it turns backfill into a permanent mode switch and makes tracked sessions
  redundant, muddying the "earn from now on" default for users who never opted in.
- **Combine offset + tracked *before* `gameXp`, exploiting its cumulative form.** Because
  `gameXp` tapers over cumulative `M`, passing `backfillMinutes + trackedMinutes` yields the
  correct tapered total in one call — no per-chunk math, no engine change. This is the whole
  reason the "full total, tapered" option is cheap.
- **Idempotent, flag-gated action.** A persisted boolean (e.g. on `PlayerProfile` or
  settings) marks backfill done. The action no-ops if already set; the UI uses the flag to
  show "imported" vs offer "Import". *Why:* re-tapping must never re-import or double-count;
  the flag is the single source of truth for import state.
- **Opt-in over automatic (locked by product decision).** A user-initiated action, not a
  first-sync default. *Why:* the baseline design exists to make XP feel earned; a silent
  instant level jump on install would undermine that for every user. Opt-in confines the
  jump to players who explicitly want history counted.
- **New `playtime-backfill` capability, distinct from the engine.** The engine capability
  owns the rules; this owns the one-time import behavior and how its offset combines with
  tracked minutes — an app-data concern, same separation as `steam-achievements` vs
  `gamification`.
- **The frozen offset must survive syncs.** The Steam poll rebuilds each `Game` row from the
  DTO and upserts it; that rebuild MUST carry `backfillMinutes` over from the existing row
  (alongside `isGoal` / `targetMinutes`), exactly because it is app-owned state absent from the
  Steam payload. Dropping it resets the offset to 0 and the next recompute silently erases all
  imported XP. *Why called out:* it is the one non-obvious coupling between this feature and the
  otherwise-independent sync path, and the failure is silent (XP just collapses on next sync).
- **Reset is the inverse of import.** Undo clears every `backfillMinutes` to 0, unsets the flag,
  and recomputes — returning to the not-imported state. Safe to re-import afterward because the
  offset is computed as `playtimeForever − trackedMinutes` (a *set*, not an accumulate), so a
  second import refreezes the same historical portion. Tracked sessions/streaks are untouched.

## Data / flow

```
opt-in action:
  for each game:
    backfillMinutes = max(0, game.playtimeForever − trackedMinutes(game.appId))
  persist backfillMinutes per game; set profile.playtimeBackfilled = true
  trigger GamificationUpdater.recompute

recompute (changed line):
  minutesPlayed = game.backfillMinutes + trackedMinutes(appId)      // was: trackedMinutes only
  gameXp(minutesPlayed, completionistAverageMinutes, cfg)           // engine unchanged
```

## Risks / Trade-offs

- **[Risk]** Games with no HowLongToBeat completionist average take the engine's **flat,
  uncapped** fallback — so a 400-hour game with no HLTB data backfills to a very large,
  untapered XP amount, unlike HLTB-matched games which cap at 2× the average. →
  **Mitigation**: surface this in the opt-in confirmation (e.g. "matched games are capped;
  unmatched games count in full"), and recommend running the HLTB library refresh first so
  more games taper. Accepted as a consequence of the chosen "full total, tapered" option
  rather than adding a separate cap.
- **[Risk]** A large one-time XP jump can fire the Home level-up animation repeatedly / feel
  jarring. → **Mitigation**: acceptable for an explicit user action; the level-up celebration
  is already change-guarded in `HomeScreen`. No throttling built here.
- **[Trade-off]** Freezing `backfillMinutes` at opt-in means playtime accrued on *other
  devices* after opt-in still flows in only as tracked sessions (via `playtimeForever`
  deltas), not re-backfilled — correct and intended, but worth noting it won't retro-adjust.
- **[Risk]** Room migration (version bump). → **Mitigation**: additive
  `ALTER TABLE games ADD COLUMN backfillMinutes INTEGER NOT NULL DEFAULT 0`; no data rewrite.

## Migration Plan

Additive migration bumping `BacklogiumDatabase.version`:
`ALTER TABLE games ADD COLUMN backfillMinutes INTEGER NOT NULL DEFAULT 0` (default 0 = the
current no-backfill behavior, so existing installs are unchanged until the user opts in).
Rollback: destructive fallback already configured; the column and flag only gate re-computable
XP input. Coordinate the version number with `add-steam-achievements` if both land (each is an
additive step; assign sequential versions).

## Open Questions

- ~~Should opt-in offer an "undo" (clear `backfillMinutes`, unset the flag, recompute) if a
  player dislikes the jump?~~ **Resolved: yes.** A reset control on the Home card clears the
  offsets + flag and recomputes; re-import is safe (set semantics). It also doubles as the
  recovery path for installs whose offsets were wiped by the pre-fix sync bug.
- Where does the control live — Home, or a dedicated settings screen the app does not yet
  have? **Resolved: Home**, as a dedicated "Steam history" card (the app has no settings
  screen yet).
