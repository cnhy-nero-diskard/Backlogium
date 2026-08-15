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
playtime *delta* observed between two polls up to 15 minutes apart, so the minute-level
distribution inside that window is already an estimate. Splitting an estimate at midnight
produces two precise-looking numbers from one imprecise one, and it makes a session's
contribution non-atomic, which every consumer then has to handle.

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
  owner's call on Decision 4.
- Does not handle time zone or DST changes mid-session. Known limitation, written down.
