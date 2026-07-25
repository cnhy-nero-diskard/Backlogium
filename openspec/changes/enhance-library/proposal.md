# Library navigation, universal completion progress, and targeted HLTB refresh

## Why

The Library is a flat, unfiltered, unsearchable list of every owned game. For a library of any
real size that makes it a scrolling exercise: there is no way to find a specific game, and no way to
see what a game has *contributed* despite XP being the app's central currency.

It also carries a leftover from before the HowLongToBeat integration. The goal-games section was
built around a **manual hours target** the user typed per game — `GameRepository.tagGoal` still
documents that target as *"retired"*, and `Game.targetMinutes` is a dormant column nothing reads.
HowLongToBeat replaced that target, but it did more than substitute for it: the batch refresh
fetches a completion length for **every** owned game, not just tagged ones. So the section's
remaining visual privilege — being the only rows that show progress toward completion — is a
leftover from when it was the only place a target existed, not a consequence of anything.

The HLTB batch refresh has a related problem. It is all-or-nothing over the whole library, throttled
to one request every 1.5 seconds — so a 300-game library is a ~7-minute opaque wait behind a
spinner labelled "Refreshing…", with no indication of progress, of which game is being parsed, or
of what the results were. `HltbRefreshWorker` already reports `(done, total)` via `setProgress`;
`SyncScheduler` throws that data away and exposes only a boolean. And when you only want data for
three specific games, the only option is to sweep all 300.

## What Changes

- **Completion progress for every game** that has a HowLongToBeat length, not only tagged ones —
  claiming the dividend the HLTB integration already paid for.
- **The tagged section is relabelled** from "Goal games" to "Focus", since it no longer implies a
  target the user set. Its meaning becomes what it actually is now: a curated shortlist of games
  being actively tracked, whose minutes are accounted separately.
- **Search**: a name filter over the whole Library, preserving section structure.
- **XP badge**: each game shows its total contribution to the player's XP — tapered playtime XP
  plus its unlocked achievements' rarity XP — so the badges sum to the player's real total.
- **Batch progress**: the existing worker progress is surfaced as a real progress bar
  ("12 / 240") plus a rolling log of each game and its outcome (matched / needs review / no
  match / lookup failed).
- **Targeted batch**: multi-select games and run the HLTB lookup over just that selection,
  bypassing the freshness window.

## Capabilities

### Modified Capabilities
- `app-ui`: completion progress is shown for any game with a known completion length; the tagged
  section is relabelled; the Library gains search, an XP badge per game, batch progress with a
  per-game log, and a multi-select mode for targeted refresh.
- `hltb-data`: the batch refresh accepts an explicit subset of games, forces refresh for an
  explicit selection regardless of freshness, and reports per-game outcomes as it proceeds.

## Impact

- **Affected code (new):** a search field, an XP-per-game derivation, a selection mode, and a
  progress/log surface.
- **Affected code (modified):** `HltbRepository.refreshBatch` widens its progress callback to carry
  the per-game outcome; `HltbRefreshWorker` accepts an appId subset and reports the current game +
  outcome via `setProgress`; `SyncScheduler` exposes the progress data instead of discarding it;
  `LibraryViewModel`/`LibraryScreen`; user-facing "goal" copy in `LibraryScreen` and `HistoryScreen`.
- **No Room migration.** Nothing new is persisted — every addition is a read-side derivation or
  transient view state.
- **No engine change.** `Gamification.gameXp` and `achievementXp` are called as-is; the badge uses
  exactly the inputs `GamificationUpdater` uses.

## Non-goals

- **A separate pinning feature.** Considered and rejected: once completion progress is universal and
  the tagged section is honestly labelled, a pin and a tag are the same gesture with two names —
  and the tag is the one already wired into per-day minute accounting and the engine's
  `QuestMode.GOAL_ONLY`. Adding pinning would have meant a second promotion tier directly above the
  first, a new column, and a new way for `SteamSyncWorker` to silently drop a flag.
- **Retiring the tag itself.** Its accounting (`DailyProgress.goalMinutesPlayed`), its History
  presentation, and `QuestMode.GOAL_ONLY` all depend on it. Removing it would be deletion work in
  service of a rename.
- **Renaming `isGoal` / `goalMinutesPlayed` in code or schema.** A user-facing relabel needs no
  migration; renaming columns would need one for zero functional gain. Internal names stay.
- **Dropping the dormant `Game.targetMinutes` column.** Harmless where it is; removing it costs a
  migration.
- **Sorting controls** (by playtime, name, completion). Search plus the Focus shortlist addresses
  the find-a-game problem.
- **Filtering by anything but name** (genre, HLTB status, completion).
- **Persisting the batch log across process death.** The log is a progress aid, not a record.
- **Changing how XP is computed.** The badge reports the existing computation.
- **Bulk tagging** via the new multi-select mode. Selection drives HLTB refresh only, so the mode
  has exactly one meaning.
