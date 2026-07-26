## 1. GamificationUpdater

- [ ] 1.1 Partition `questResults` into `pastDays` (`date < today`) and `todayResult` (`date ==
  today`, if present)
- [ ] 1.2 Compute `pastStreak = Gamification.streak(pastDays, config)`
- [ ] 1.3 Persisted `currentStreak` = `pastStreak.current + 1` when `todayResult?.met == true`,
  else `pastStreak.current`
- [ ] 1.4 Persisted `longestStreak` = `max(pastStreak.longest, currentStreak)`
- [ ] 1.5 Use the same `LocalDate` source already injected for "today" elsewhere in
  `GamificationUpdater`/`SteamSyncWorker` — no second clock
- [ ] 1.6 Comment at the call site noting the one-row-per-date assumption the partition relies on

## 2. Unit tests

- [ ] 2.1 Streak intact, today unmet and in progress → persisted `currentStreak` equals yesterday's
  value, not zero
- [ ] 2.2 Streak intact, today's quest met → persisted `currentStreak` equals yesterday's value + 1
- [ ] 2.3 Streak already broken before today (yesterday unmet, beyond grace) → `currentStreak` is 0
  regardless of today's state
- [ ] 2.4 `longestStreak` still reflects the historical maximum after today extends past it
- [ ] 2.5 Grace allowance still applies correctly across `pastDays` (unchanged engine behavior,
  verify the partition doesn't disturb it)
- [ ] 2.6 No `DailyProgress` row yet for today (first sync of the day, before any row is created) →
  treated the same as today unmet/in-progress

## 3. Home screen presentation

- [ ] 3.1 `HomeViewModel`/`HomeUiState`: no new zero-streak state to represent — the persisted value
  is already correct; confirm no local recompute in the UI layer re-introduces the bug
- [ ] 3.2 `HomeScreen.kt` Streak card copy: while today is unmet, phrase the count so it doesn't
  imply today has already extended it (without adding a second badge/number)
- [ ] 3.3 Confirm the weekly-milestone animation trigger (`isStreakMilestone`) still fires correctly
  off the corrected `currentStreak` value, including the case where today's quest completion is what
  crosses the milestone

## 4. Verification

- [ ] 4.1 Manual check: open the app the morning after an unbroken streak, before playing anything —
  Streak card shows the intact count, not 0
- [ ] 4.2 Manual check: meet today's quest mid-session — Streak card increments live
- [ ] 4.3 Manual check: let a day lapse unmet — Streak card reads 0 starting the following day, not
  mid-way through the missed day

## 5. Docs & specs

- [ ] 5.1 Verify the `app-ui` spec delta matches the built behavior
