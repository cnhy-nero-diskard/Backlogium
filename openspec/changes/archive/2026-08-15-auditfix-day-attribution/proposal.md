## Why

The app answers "which day did this playtime happen on?" two different ways, and it
answers "which days are adjacent?" by ignoring the calendar. Both produce numbers the
user can see and disagree with.

**Two day-attribution rules.** `SteamSyncWorker.kt:199-206` credits a poll's entire
observed delta to `today` — the local date at poll time. History assigns the whole
synthesized session to the local date of its `startAt`. A session that crosses midnight
therefore appears under Tuesday in History while its minutes satisfy Wednesday's daily
quest and extend Wednesday's streak. Same play activity, two contradictory readings, both
displayed.

**Streak adjacency ignores the calendar.** `GamificationUpdater.kt:125` passes
`dailyProgressDao.getAllOrdered()` — a set of rows that exists only where progress was
recorded — into `Gamification.streak()`, which folds by list order. Rows for Monday and
Thursday with nothing between them are treated as adjacent, so a two-day streak is
reported where the user played twice in four days.

**A correction to the audit, because it changes what to fix.** The audit attributes this
to the engine and to a unit test that asserts order-only folding, concluding that the
test preserves a bug. That is wrong, and following it would break things. The
`gamification` spec defines the engine as pure — "It performs no I/O, no networking, and
no persistence — callers supply inputs and persist outputs" — and requires streaks be
computed "from an ordered set of per-day quest results". *Ordered* describes the list,
not the calendar. The engine, its test, and the spec all agree. The defect is the caller
handing it a sparse list. `Gamification.streak()` must not change, and neither must
`streak_ignoresGapsBetweenDatesUsesOrderOnly`.

**How exposed this is depends on row density, which needs checking first.** If a
`DailyProgress` row is written for every calendar day the app is alive — including
zero-playtime days — then rows are dense in practice and the gap collapse only bites
after the device is off or offline across a day boundary. `SteamSyncWorker.kt:200`
ensures *today's* row exists on every poll, which suggests density holds while the app
polls normally and breaks precisely when it does not. That window is the bug's real
size, and task 1.1 measures it before anything is designed around it.

## What Changes

- **One canonical attribution rule, written down.** A session's minutes belong to one
  date, chosen by a rule stated in the spec, and both daily progress and History use it.
  Design proposes attributing to the session's start date and explains the alternative.
- **Deltas are attributed per session, not per poll.** The sync currently sums all
  deltas and credits them to the poll's date. Once attribution is a property of the
  session rather than of the poll, a poll can credit two different dates — which is what
  a midnight-crossing session actually requires.
- **The streak day sequence is densified at the caller.** `GamificationUpdater` builds a
  contiguous calendar sequence over the span it evaluates, synthesizing unmet days for
  dates with no stored row, then folds that. The engine keeps its pure order-only
  contract and receives a list where order and calendar finally agree.
- **The streak test gains a comment saying why it is correct.** Its absence is what let a
  thorough audit reach a confident wrong conclusion.
- **Home re-resolves "today" at the day boundary.** `HomeViewModel.kt:116` computes
  `todayKey` inside a `combine` of five *data* flows, none of them time-driven, so after
  midnight with no sync the screen keeps presenting yesterday's row — totals and quest tick
  — as the current day's. This is a second, independent route to the same reported symptom
  as the attribution split, which is why it lands here rather than separately: fixing only
  one of the two leaves the bug report open.
- **The poll-gap start estimate is written down as a bound, not narrowed.** A session's
  `startAt` is the previous poll's timestamp, and a poll deferred by Doze can put that on
  the wrong calendar day. Recorded in the `steam-sync` spec; design Decision 5 records why
  the obvious clamp is a regression rather than a fix, and what a real fix would need.

## Capabilities

### Modified Capabilities

- `gamification`: add a requirement that the day sequence supplied to streak computation
  is contiguous, locating that obligation explicitly on the caller so the engine's purity
  is preserved rather than quietly eroded.
- `steam-sync`: change delta attribution from per-poll to per-session, so a poll spanning
  midnight credits both dates; and state the accuracy bound on a session's start, since a
  rule that names the start date is only as good as the start it names.
- `app-ui`: require History and daily progress to agree on a session's date, and require a
  surface labelling activity "today" to follow the calendar rather than its last data
  emission.

## Impact

| Path | Change |
|---|---|
| `domain/GamificationUpdater.kt` | densify the day sequence before folding; per-session attribution |
| `work/SteamSyncWorker.kt` | attribute deltas per session instead of per poll |
| `domain/SessionDiffer.kt` | may need to expose per-session date boundaries |
| `ui/history/HistoryGrouping.kt` | align with the canonical rule |
| `ui/home/HomeViewModel.kt` | drive `todayKey` from a date flow in the combine, not a call inside it |
| `gamification/.../GamificationTest.kt` | comment only — no expectation changes |

**User-visible numbers will move.** Correcting streak adjacency can *shorten* a displayed
streak, and `longestStreak` is a stored high-water mark that recomputation deliberately
cannot lower (`GamificationUpdater.kt:151-154`, and `backup-restore` requires the same).
So a longest streak inflated by the gap bug will persist after the fix. Design covers
whether to leave it, and the answer is not obviously "recompute" — the spec treats a
record as a historical fact on purpose.

**Should land after `auditfix-sync-write-integrity`**, which gives this change a
transaction to write inside and removes the read-add-write on `DailyProgress` that
per-session attribution would otherwise have to duplicate per date.

**Not addressed here**: time zone changes and DST. Attribution uses the local date, and a
user who travels across zones mid-session gets whatever the local date says. Noted as a
known limitation rather than solved, because no finding raises it and the correct
behaviour is genuinely unclear.
