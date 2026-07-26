## Context

`GamificationUpdater.recompute()` is the only call site of `Gamification.streak()`. It runs on every
sync (`SteamSyncWorker`) and manual "Sync now", and does three things in order: recompute XP/level,
recompute each stored day's `questMet` (including today's, which is upserted with whatever it
currently is), then compute the streak over `dailyProgressDao.getAllOrdered()` — every stored day,
today included — and persist `currentStreak`/`longestStreak` onto `PlayerProfile`.

`Gamification.streak()` (in `:gamification`, locked by `add-gamification-engine`) is pure and
correct for what it's asked: given an ordered list of `QuestResult`, it counts consecutive met days
and resets to zero on the first unmet one (past the grace allowance). It has no notion of "today" —
that's a calendar concept the engine deliberately doesn't own (it takes injected inputs, no clock).

The bug isn't in the engine's logic; it's in what gets fed to it. Today's `QuestResult` is `met =
false` from the moment the day's `DailyProgress` row is created until the quest threshold is
crossed. Feeding that row into `streak()` alongside genuinely-completed past days makes an
in-progress day indistinguishable from a day that ended without meeting its quest — and the engine,
correctly per its contract, treats it as a break.

## Goals / Non-Goals

**Goals:**
- The persisted/displayed streak never reads as broken solely because today hasn't concluded or
  hasn't been played yet.
- Once today's quest is met, the streak extends immediately (no reason to wait for the day to end).
- No change to the pure engine — `Gamification.streak()`'s contract (break on the first unmet day,
  honor grace) stays exactly as specified for a list of completed days.

**Non-Goals:**
- Changing streak grace behavior, weekly milestone detection, or the milestone animation.
- Retroactively recomputing historical streaks — this only changes how *today* is treated at
  computation time.
- A live countdown or "time left today" indicator. Out of scope; this is about not showing a false
  zero, not about surfacing a timer.

## Decisions

- **Split the day list at today; compute the persisted streak from completed days only, then fold
  today in as an increment when (and only when) its quest is met.**
  Concretely: `recompute()` partitions `questResults` into `pastDays` (`date < today`) and
  `todayResult` (`date == today`, present once the day's row exists). It calls
  `Gamification.streak(pastDays, config)` to get the streak through yesterday. If `todayResult.met`,
  the persisted `currentStreak` is `pastStreak.current + 1` and `longestStreak` is
  `max(pastStreak.longest, currentStreak)`. If `todayResult` is absent or unmet, the persisted
  `currentStreak` is `pastStreak.current` — i.e. unchanged from yesterday, never zeroed for a day
  still in progress.
  *Why:* this keeps the engine untouched and pure — it only ever sees a list of *completed* days —
  while the call site (which already owns "what is today", via the injected clock) supplies the one
  piece of calendar knowledge the engine deliberately doesn't have.
  *Alternative rejected:* teaching `Gamification.streak()` to accept an "in-progress, don't break"
  marker on the last day. Rejected because it adds a special case to a function whose contract is
  locked by a prior design, for behavior that is really about *which days get fed in*, not about how
  streaks are counted.
  *Consequence:* once a day ends (rolls into `pastDays` at the next sync on or after the next
  calendar day) without its quest ever having been met, `Gamification.streak()` naturally zeroes the
  streak on that recompute — the break still happens, just only once the day has actually concluded
  unmet, not while it's still open.

- **"Today" is whatever `LocalDate` the day list's own injected clock says it is** — the same
  `LocalDate.now()`-equivalent already used elsewhere in `GamificationUpdater`/`SteamSyncWorker`, not
  a second source of truth.
  *Why:* avoids clock skew between what counts as "today" for quest evaluation and what counts as
  "today" for streak folding.

- **Home's copy distinguishes "streak intact, today still open" from "streak extended today."** While
  today is unmet, the Streak card shows the streak count without implying today already counts (e.g.
  "N-day streak" without also implying today added to it); once today's quest is met, the same field
  reflects the extended count exactly as it does for any other day.
  *Why:* the whole point is that a player checking mid-morning should see their real streak, not a
  false zero — but the copy shouldn't overclaim that today has already been secured either.
  *Alternative rejected:* adding a distinct "on track" badge/second number. Bigger UI surface than the
  ask; the existing single streak number already carries the right information once it stops
  zeroing prematurely.

## Risks / Trade-offs

- **A player who breaks their streak mid-day (quest was met yesterday, definitely won't be met
  today, e.g. they uninstalled) still sees yesterday's streak count until the day rolls over.**
  Accepted: the alternative (showing 0 the instant the day starts) is exactly the false-zero problem
  this change fixes. The count is honest as of "streak through the last completed day, plus today if
  already secured" at all times.
- **`recompute()`'s partition depends on `dailyProgressDao.getAllOrdered()` including at most one row
  with `date == today`.** True today (one `DailyProgress` row per date, upserted), but worth a
  comment at the call site since it's an assumption the split relies on.

## Migration Plan

None. No schema change, no new persistence, no new network calls. Existing `PlayerProfile.currentStreak`/
`longestStreak` columns are reused; only the value computed for them changes.
