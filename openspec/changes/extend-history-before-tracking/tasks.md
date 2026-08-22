## 1. Unlock dates reach the grouping

- [ ] 1.1 Add the unlock dates to `groupHistory`'s day union — today `achievementsByDate` is computed and then never consulted when building `allDates`, which drops unlocks on session-less days inside the current window
- [ ] 1.2 Add a regression test for that drop specifically: an unlock inside the window on a date with no session and no progress row must produce a day
- [ ] 1.3 Add a repository query returning the earliest dated unlock, so paging knows its floor without scanning every page
- [ ] 1.4 Confirm an unlocked achievement whose `unlockedAt` is null produces no day and is routed to the undated accounting rather than silently discarded

## 2. Two kinds of day

- [ ] 2.1 Give the day model an explicit kind — tracked or before-tracking — rather than letting consumers infer it from an empty session list
- [ ] 2.2 Make a day's played time an explicit unknown state, distinct from zero, and thread it through every consumer
- [ ] 2.3 A date with both sessions and unlocks is a tracked day carrying its unlocks, never a second row
- [ ] 2.4 Produce no day for a date with no sessions, no progress row, and no dated unlocks, so an empty stretch adds nothing to the list
- [ ] 2.5 Extend the day's game thumbnails to cover games named only by that day's unlocks
- [ ] 2.6 Unit-test the grouping as a table of ledger fixtures: unlock-only day, session-only day, both, undated unlock, empty stretch, and a day at the paging boundary

## 3. Isolation from the engine

- [ ] 3.1 Verify pre-tracking days write no `daily_progress` row by any path
- [ ] 3.2 Verify they never enter the day sequence handed to `Gamification` — the engine requires a contiguous sequence, and a 2019 date either breaks the current streak or fabricates one
- [ ] 3.3 Assert current streak, longest streak, total XP, and level are byte-identical before and after pre-tracking days become visible, on a fixture with years of pre-install unlocks
- [ ] 3.4 Present no quest state on a pre-tracking day — not an unmet one, which would assert something the app cannot know

## 4. The daily XP derivation

- [ ] 4.1 Add a pure derivation in `domain/`: given sessions, unlocked achievements with snapshots, per-game backfill minutes, per-game completionist lengths, a zone, and a `RuleConfig`, return XP per local date plus the undated remainder. No Room, no Android, no injection — the shape `LibraryXp` already uses
- [ ] 4.2 Require `RuleConfig` rather than defaulting it, for the reason `LibraryXp` documents: an omitted config renders plausible numbers that disagree with an edited player's total
- [ ] 4.3 Compute each day's playtime XP as `gameXp(backfill + cumulative_through) − gameXp(backfill + cumulative_before)` per game, so the backfill offset participates in the taper exactly as it does in the total
- [ ] 4.4 Attribute achievement XP by the local date of `unlockedAt`, using `snapshotPercent`, never the live global percent
- [ ] 4.5 Compute the undated remainder as `Σ gameXp(backfillMinutes)` plus the XP of unlocked achievements with no unlock time, and return the two sources separately so the UI can name them
- [ ] 4.6 Call `Gamification.gameXp` and `Gamification.achievementXp` directly — no reimplementation of the taper or the tier table
- [ ] 4.7 Property-test the identity `totalXp == Σ dailyXp + undatedXp` over generated ledgers, not one hand-built case, covering: no backfill, heavy backfill, undated unlocks, a game past twice its completionist length, a game with no HLTB length, and an achievement with no snapshot
- [ ] 4.8 Test that a day's attributed XP is invariant to the order its sessions and unlocks are supplied in

## 5. History presentation

- [ ] 5.1 Show each day's attributed XP, presenting zero rather than omitting it
- [ ] 5.2 Present a pre-tracking day's played time as unknown, with no quest state and no session expansion, visually distinguishable from a tracked day with no play
- [ ] 5.3 Keep the achievement row's capped-with-overflow treatment identical on pre-tracking days
- [ ] 5.4 Extend paging to the earliest dated unlock and withdraw the load-earlier action once it is reached
- [ ] 5.5 Present the undated remainder where the daily accounting is shown, naming the import and undated unlocks as its sources, and omit it entirely when it is zero
- [ ] 5.6 Confirm expansion state survives loading earlier days, as it does today

## 6. Home

- [ ] 6.1 Show today's attributed XP alongside the level and XP progress, as zero rather than absent before anything is earned
- [ ] 6.2 Re-derive at day rollover from the injected date, without waiting for a sync — the same reason `HistoryViewModel` already takes `currentDate` as an input
- [ ] 6.3 Verify Home's figure and History's figure for the same date agree
- [ ] 6.4 Confirm no account, sync, or data-management control reaches Home

## 7. Provenance alignment

- [ ] 7.1 If `disclose-data-provenance` is active, adopt its terms rather than inventing wording: unlock times are observed, a day's minutes are tracked, a pre-tracking day's minutes are absent rather than any of the three
- [ ] 7.2 If it is not active, state unknown-versus-zero in plain language here, and leave the wording easy to route through the vocabulary later

## 8. Non-regression

- [ ] 8.1 Confirm no entity, DAO write, migration, or DataStore key was added
- [ ] 8.2 Confirm no Steam request was added, and no existing request changed
- [ ] 8.3 Confirm no per-day XP value is persisted anywhere
- [ ] 8.4 Confirm the Analytics screen, Personal Pace, and every windowed figure are unchanged — a pre-tracking day contributes nothing to any total, average, or chart
- [ ] 8.5 Confirm a rules edit re-derives every past day's XP, consistently with the disclosed retroactive effect
