## Why

Opening the app early in the day currently shows "0 days" on the Streak card even when the player
has an unbroken streak going into today. `GamificationUpdater.recompute()` feeds
`Gamification.streak()` the full ordered day list from `dailyProgressDao.getAllOrdered()`, which
includes today's row. Today's row starts with `questMet = false` and only flips to `true` once the
day's quest threshold is reached, so for every sync before that point the engine sees today as an
*unmet* day and — correctly, per its own contract — breaks the streak to zero. The day isn't over;
nothing has actually broken. The player just hasn't played yet today, and reads it as their streak
being gone.

## What Changes

- `GamificationUpdater.recompute()` computes the persisted streak from **completed days only**
  (everything strictly before today), then folds today in as an extension **only once today's quest
  is actually met** — it never lets an in-progress, not-yet-evaluated today zero out a streak that
  is still intact through yesterday.
- The Home screen's Streak card reflects this: while today's quest is unmet and the day is still in
  progress, it shows the intact streak count (not "0"), with copy that reads as still-in-progress
  rather than "days" (e.g. distinguishing "streak intact, play today to extend it" from "N days").
- No change to `Gamification.streak()` itself — the engine's contract (consecutive met days, break on
  the first unmet day, honoring grace) is correct for a list of *completed* days. The fix is what the
  call site feeds it: today is not evaluated as complete until the day is over or its quest is met.

## Capabilities

### Modified Capabilities
- `app-ui`: the Home screen's streak presentation changes so the streak count never reads as broken
  purely because today hasn't concluded.

## Impact

- **Affected code:** `GamificationUpdater.recompute()` (app/src/main/java/com/example/backlogium/domain/GamificationUpdater.kt)
  splits the day list into completed-days-through-yesterday and today, and derives the persisted
  `currentStreak`/`longestStreak` from that split. `HomeScreen.kt` / `HomeViewModel.kt` gain whatever
  state is needed to distinguish "today already extended the streak" from "streak intact, today still
  open" for copy purposes.
- **No engine change.** `Gamification.streak()` in `:gamification` is untouched; this is entirely a
  call-site and presentation fix.
- **No schema change.** `PlayerProfile.currentStreak`/`longestStreak` keep their existing shape.
- **No new network calls.**
