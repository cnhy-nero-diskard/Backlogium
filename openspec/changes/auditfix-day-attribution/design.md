# Design

## Context

```
  a session from 23:40 Tue to 00:50 Wed  (70 minutes)

  History        ──▶  Tuesday   (local date of startAt, all 70 min)
  Daily quest    ──▶  Wednesday (local date of the poll that observed it, all 70 min)
  Streak         ──▶  Wednesday extends

  the user sees: "Tuesday: 70 min played"  and  "Wednesday's quest: met"
```

Nothing here is a coding mistake. Two call sites each made a locally reasonable choice
and no spec said which was canonical. That is what makes it worth fixing at the spec
level rather than by editing whichever one looks wrong.

## Exposure finding

`DailyProgress` rows are sparse in practice. The only production path that creates a
zero-minute row is `SteamSyncWorker.commitRawPoll`, which calls `ensureDate(today)` after
a successful poll. There is no startup or day-rollover writer that creates rows for dates
the device did not observe, so a device that is off or offline across a day boundary can
leave Monday and Thursday rows with no Tuesday or Wednesday rows between them. The
streak gap is therefore a live defect after an offline interval, not merely a theoretical
case after data corruption.

The prerequisite `auditfix-sync-write-integrity` is present on `origin/master`:
`DailyProgressDao.addMinutes` performs the additive SQL update used by the raw sync
transaction. Per-date attribution can therefore reuse that transaction without restoring
a read-add-write or duplicating the preceding change.

## Decision 1: Attribute to the session's start date

**Chosen**: a session's minutes belong entirely to the local date of its `startAt`.

**Rejected: split at midnight**, proportionally crediting each date. It is the most
"correct" answer and the wrong engineering choice here. The app does not know when within
a poll interval the minutes were played — `SessionDiffer` synthesizes a session from a
playtime *delta* observed between two polls, so the minute-level distribution inside that
window is already an estimate. Splitting an estimate at midnight produces two
precise-looking numbers from one imprecise one, and it makes a session's contribution
non-atomic, which every consumer then has to handle.

**How wide that window really is.** `SyncScheduler.kt:116` requests a 15-minute period,
but a `PeriodicWorkRequest` period is a floor, not a guarantee: under Doze the OS defers
work, and an overnight gap between polls can be hours. `SessionDiffer.kt:110` sets a new
session's `startAt` to `previousPollAt`, so a session opened after such a gap is dated to
the last poll before the gap — which may be the previous calendar day. **The attributed
date can therefore be wrong, and this change does not fix that.** It is recorded rather
than solved, because the fix that first suggests itself does not work; see Decision 5.

**Rejected: attribute to the poll date** (today's behaviour for quests). It makes a
session's day depend on when the app happened to observe it, so the same play activity
lands on different dates depending on whether the phone had signal at midnight. That is
not a rule, it is a side effect.

Start-date attribution has one visible consequence worth stating plainly: **a late-night
session credits the day it began, so playing 23:40–02:00 satisfies the earlier day's
quest.** For a personal progression app this matches how people talk about their own
evenings, and it is at least a rule the user can learn. The alternative rules cannot be
learned because they depend on poll timing.

**Consequence for the quest**: a session starting before midnight can credit a day whose
quest was already evaluated as unmet. The evaluation must therefore be able to revisit a
past day — which `GamificationUpdater` already does, since `:127-140` recomputes every
stored day's quest status on each run and collects `changedDays`. The machinery exists;
this change just gives it a reason to fire on a past date.

## Decision 2: Per-session attribution in the sync

Today `SteamSyncWorker.kt:196-206` sums all deltas into `addedAny` / `addedGoal` and
credits one date. Under Decision 1 a single poll can produce sessions with different
start dates, so the credit becomes a map:

```
  diff.actions ──▶ per session: (startAt → localDate, minutes, isGoal)
                        │
                        ▼
              group by localDate  ──▶  additive update per date
```

`SessionDiffer` already carries `startAt` on its `Open` action, and `Extend`/`Close`
reference an existing open session whose `startAt` is stored. The information is present;
it is being discarded by the summation at `:197-198`.

**Open sessions are the subtle case.** A session opened Tuesday and extended across
Wednesday accumulates minutes on later polls. Under start-date attribution *all* of those
minutes credit Tuesday, including ones observed on Wednesday. This is consistent — the
session's date is fixed at open — and it means a long open session keeps crediting a past
date until it closes. That is the intended reading of Decision 1, and it needs a test.

**Ordering note**: this depends on `auditfix-sync-write-integrity` having replaced the
`DailyProgress` read-add-write with an additive SQL update, or this change has to add
that itself for each affected date. Land that one first.

## Decision 3: Densify at the caller, never in the engine

The engine's contract is correct and must not move. `GamificationUpdater` builds the
contiguous sequence:

```
  stored rows (sparse):     Mon ──── Thu ── Fri
                             │
                             ▼  densify over [min(date), today]
  supplied sequence:        Mon  Tue  Wed  Thu  Fri
                            met  ---  ---  met  met      ← synthesized days are unmet
                                  │
                                  ▼
                    Gamification.streak(...)  → current = 2 (Thu, Fri), not 3
```

Synthesized days are **not persisted**. They exist only to make the supplied list's order
match the calendar. Writing zero-minute rows for every gap day would inflate the table
without adding information, and `changedDays` at `:126` would then try to persist them.

**Span choice**: from the earliest stored row to `today`. Not from a fixed epoch — that
would synthesize thousands of unmet days for a new install with one row, which is correct
but wasteful. Not from the most recent break either, since `longestStreak` needs the
whole history.

**Grace interacts with this and the interaction is the point.** `RuleConfig.streakGraceDays`
forgives unmet days. Once gaps become real unmet days, grace starts applying to them —
which is the desired behaviour (a configured grace of 1 should forgive one missed day)
and also means a user with grace configured may see *less* change than expected. Worth
verifying explicitly rather than discovering.

**Do not touch `streak_ignoresGapsBetweenDatesUsesOrderOnly`.** Add a comment explaining
that order-only folding is the engine's intended pure contract per the `gamification`
spec, and that calendar densification is the caller's job. The audit misread this test
precisely because nothing in it said so.

## Decision 4: The inflated `longestStreak` problem

`longestStreak` is a stored high-water mark that recomputation floors rather than
recalculates (`GamificationUpdater.kt:151-154`), and `backup-restore` requires the same
of imports. A streak that was only ever long because of the gap bug is therefore already
banked and will survive this fix.

| Option | Effect |
|---|---|
| **A.** Leave it | user keeps a record they did not earn; no code |
| **B.** One-shot recompute, allow it to lower | record corrected; deliberately breaks the never-decreases invariant once |
| **C.** Recompute, keep the higher, note it | no behaviour change, honest but pointless |

**Chosen: A, and say so.** The invariant exists because "a record is a historical fact,
and recomputing under a stricter config must not be able to erase one" — the reasoning in
the code comment. A bug-fix is a stricter recomputation. Adding a one-shot exception for
it establishes that the invariant yields to a sufficiently good reason, which is exactly
what an invariant must not do.

The counter-argument is real: the number is wrong and the user may notice their current
streak reset while the longest stayed. **This is the one decision in this change I would
flag for the owner rather than settle.** For a single-user personal app, "wipe it and
start honest" is a legitimate answer that a multi-user product could not choose. If that
is wanted, take option B as an explicit, commented, one-time migration — not as a
softening of the rule.

For this implementation, the proposal's recommended option A is the owner decision:
leave `longestStreak` banked and do not add a corrective migration. The existing
never-decreases invariant remains intact; only newly recomputed current streaks use the
corrected calendar sequence.

## Decision 5: The poll-gap start estimate is recorded, not narrowed

**Chosen**: `startAt` stays `previousPollAt`, and the bound is written into the
`steam-sync` delta spec as a stated limitation.

**Rejected: clamp to `max(previousPollAt, now - addedMinutes)`.** This looks like a
tightening and is not one. `delta` minutes of play happened somewhere in
`(previousPollAt, now]`, so the true start lies in `[previousPollAt, now - delta]`:
`previousPollAt` is the *earliest* feasible start and `now - delta` is the *latest*. The
clamp does not narrow the interval, it moves to the opposite end of it. Worked against the
overnight case this change exists to serve — play 23:50–00:00, last poll 21:15, next poll
02:00 — the current rule credits the correct date and the clamp credits the following one.
It only wins when play ended immediately before the observing poll, and loses whenever the
device slept through the evening. Adopting it would regress the common case.

**Rejected: the midpoint of the feasible interval.** Same objection as splitting at
midnight in Decision 1, one level up: it manufactures a precise-looking timestamp out of an
interval the app has no evidence about, and it is no more likely to be right than either
endpoint.

Between two endpoints that are both guesses, `previousPollAt` is kept because it is the
incumbent, because it is correct for evening play followed by a slept-through night, and
because changing it would move historical session dates for no gain in accuracy.

**What would actually fix it**: the app already observes presence directly — a foreground
service polls every 30 seconds while a game is running (`PresenceServiceStarter`,
`live-status`). Anchoring a session's start to the first presence observation of a game,
rather than to whenever the owned-games poll next happened to run, would replace the
estimate with evidence. That is a materially larger change with its own session-identity
questions, and it belongs to `live-status`, not here. Named so the next reader does not
re-derive the clamp and re-reject it.

## Decision 6: Home re-resolves the current date on a ticker

`HomeViewModel.kt:116` computes `todayKey = time.today()` *inside* the `combine` of five
data flows. Nothing in that combine is time-driven, so the lambda only re-runs when the
profile, daily progress, rule config, credentials, or sync status emits. Cross midnight
with no sync and `todayKey` is still yesterday, `days.firstOrNull { it.date == todayKey }`
still resolves yesterday's row, and Home presents yesterday's minutes and yesterday's
satisfied quest tick as the current day's.

This is a second, independent route to the same user-visible symptom as the attribution
split — "today's total includes play from before the day change" — which is why it belongs
in this change rather than a separate one. The two are easily confused when reading the
screen: poll-time attribution puts pre-midnight *minutes* on the new day's row, while this
puts the *old day's row* on the new day's screen. Fixing only one leaves the report open.

**Chosen**: add a date flow to the combine — one that emits the current local date and
re-emits when it changes — and read `todayKey` from it rather than calling `time.today()`
inside the lambda. The combine then re-runs on a day boundary for the same reason it re-runs
on a sync.

**Rejected: recompute in the composable.** It would fix the reading but leave the ViewModel
still producing a state object whose `todayMinutes` and `questMet` describe a stale date,
which every other consumer of that state would inherit.

**Rejected: a WorkManager job at midnight.** Far too heavy for a display concern, and it
would not help a screen already open at the boundary.

The emission interval is a design detail for implementation, not a spec concern: the
`app-ui` requirement states the boundary must be observed, not how often to look.

## Decision 7: Historical totals are corrected once, from the session ledger

Per-session attribution fixes what the sync records *from now on*. It does not touch rows already
written, so every date recorded under the old rule keeps its poll-bucketed total — and keeps
displaying the contradiction this change exists to remove. On the owner's device, 20 of 24 stored
dates disagreed with their sessions, one by the full 31 minutes of a single midnight-crossing
session.

**Chosen**: a one-time backfill that recomputes `minutesPlayed` and `goalMinutesPlayed` from the
sessions, then lets `GamificationUpdater` re-derive quest status and streaks from the corrected
totals.

**Why this is safe to do at all**: sessions are an append-only ledger. Nothing in the app deletes
a session row — there is no `DELETE FROM sessions`, no `@Delete` on `SessionDao`, and no retention
policy — so for any date at or after the first session, the ledger is complete and recomputation
is authoritative rather than lossy. That property is what makes `daily_progress` a cache that can
be rebuilt; if sessions were ever pruned, this decision would be wrong.

**Dates before the first session are left alone.** The first sync baselines the library without
synthesizing sessions, so the earliest `DailyProgress` row can predate any session. Rebuilding
such a date would write a zero, reporting "no records" as "no play". Preserving it keeps the one
case the ledger genuinely cannot speak to.

**`goalMinutesPlayed` is recomputed against today's Focus flags**, because nothing records what a
game's `isGoal` flag was on a past date. This is not a faithful reconstruction and is not claimed
as one — it is the same basis History already displays (`HistoryGrouping.kt:132` filters by the
current `isGoal` set), so the two agree afterwards. The visible consequence: toggling a game's
Focus flag retroactively changes what past days report as Focus time. That was already true of
History; the backfill makes `DailyProgress` match rather than introducing it.

**A shortened current streak is the correct outcome, not a regression.** The owner's Aug 14 held
52 stored minutes against 21 minutes of actual sessions, and the 31-minute difference was one
session that began at 23:54 the night before. Correcting it puts Aug 14 below the 30-minute quota
and breaks a 6-day streak — which is precisely the discrepancy that prompted this work. The same
recomputation lengthens the longest streak from 10 to 15, because a day stored as 0 held 72
minutes of sessions and joined two runs. `longestStreak` remains a protected high-water mark, so
it can only rise here.

**Rejected: a Room migration.** Attribution needs the local time zone to turn a session's
`startAt` into a date, and DST makes that more than an offset. Expressing it in migration SQL
would mean a second, dumber implementation of the rule this change spent Decision 1 defining.
A Kotlin one-shot reuses the real rule.

**Rejected: running it inside the sync worker.** It would never run for a user who is offline or
whose credentials have lapsed, and those are exactly the users whose rows are most likely to be
skewed. It runs on start-up instead, guarded by a persisted flag, so a fresh install never pays
for it and an existing install pays once.

## Testing strategy

- midnight-crossing session credits its start date in both History and daily progress
- open session extended across midnight keeps crediting its start date
- a poll producing sessions on two dates credits both
- Mon/Thu stored rows yield current streak 2, not 3, with grace 0
- same rows with grace 1 yield the grace-adjusted result, verified explicitly
- densification over a long span with one stored row does not synthesize before it
- synthesized days are never persisted — assert the row count is unchanged
- a session starting before midnight flips a previously-unmet past day to met, and that
  day appears in `changedDays`

## Real-data verification

On emulator `emulator-5554`, using the existing persisted data on 2026-08-15,
`daily_progress` contained only today's row (`2026-08-15`, 0 minutes, 0 goal minutes,
`questMet=false`) and `player_profile` reported `currentStreak=0` and
`longestStreak=0`. The installed branch's Home screen displayed `0-day streak`,
`Longest: 0`, and `0m of 30m played today`.

No historical gap-bearing rows were present, so a positive gap-correction case could
not be exercised without seeding synthetic data. This verifies the no-gap negative case:
the implementation does not invent a streak or alter a dataset with no calendar gap.

On the physical Xiaomi device `9XDQCM6HLB5DO7YH`, the existing data included met days on
2026-08-07 and 2026-08-09, but 2026-08-08 was an explicit unmet row (26 minutes). Every
date from 2026-07-23 through 2026-08-15 had a stored row, so this was not a missing-date
case. The installed branch displayed a `6-day streak` and `Longest: 10`, which is the
expected unchanged control result: an explicit unmet day already breaks the order-only
fold, and calendar densification has no missing date to add.

## What this change deliberately does not do

- Does not change `Gamification.streak()` or any of its expectations.
- Does not split sessions at midnight. Rejected in Decision 1.
- Does not persist synthesized gap days.
- Does not correct `longestStreak` values inflated by the old behaviour, pending the
  owner's call on Decision 4. Note that Decision 7's backfill re-derives streaks from
  corrected totals, which can *raise* the high-water mark but still never lowers it.
- Does not reconstruct historical `isGoal` flags. Decision 7 recomputes Focus minutes
  against today's flags and says so rather than implying a faithful replay.
- Does not handle time zone or DST changes mid-session. Known limitation, written down.
- Does not narrow the poll-gap start estimate. A session opened after a deferred poll is
  dated to the poll before the gap, which may be the wrong calendar day. Bound stated in
  the `steam-sync` delta spec; Decision 5 records why the obvious clamp is a regression and
  what a real fix would require.
