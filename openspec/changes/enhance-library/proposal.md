# Library navigation, pinning, and targeted HLTB refresh

## Why

The Library is a flat, unfiltered, unsearchable list of every owned game. For a library of any
real size that makes it a scrolling exercise: there is no way to find a specific game, no way to
keep the handful you actually care about within reach, and no way to see what a game has
*contributed* despite XP being the app's central currency.

The HLTB batch refresh has a related problem. It is all-or-nothing over the whole library, throttled
to one request every 1.5 seconds — so a 300-game library is a ~7-minute opaque wait behind a
spinner labelled "Refreshing…", with no indication of progress, of which game is being parsed, or
of what the results were. `HltbRefreshWorker` already reports `(done, total)` via `setProgress`;
`SyncScheduler` throws that data away and exposes only a boolean. And when you only want data for
three specific games, the only option is to sweep all 300.

## What Changes

- **Pinning**: games can be pinned, surfacing in their own section above Goal games.
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
- `app-ui`: the Library screen gains pinning, search, an XP badge per game, batch progress with a
  per-game log, and a multi-select mode for targeted refresh.
- `hltb-data`: the batch refresh accepts an explicit subset of games, forces refresh for an
  explicit selection regardless of freshness, and reports per-game outcomes as it proceeds.

## Impact

- **Affected code (new):** a pinned section, a search field, an XP-per-game derivation, a
  selection mode, and a progress/log surface.
- **Affected code (modified):** `Game` gains `pinned` (additive migration); `GameDao` gains a
  pinned-games query and a pin/unpin update; `HltbRepository.refreshBatch` widens its progress
  callback to carry the per-game outcome; `HltbRefreshWorker` accepts an appId subset and reports
  the current game + outcome via `setProgress`; `SyncScheduler` exposes the progress data instead
  of discarding it; `LibraryViewModel`/`LibraryScreen`.
- **No engine change.** `Gamification.gameXp` and `achievementXp` are called as-is; the badge is a
  read-side derivation using exactly the inputs `GamificationUpdater` uses.

## Non-goals

- **Sorting controls** (by playtime, name, completion). Search plus pinning addresses the
  find-a-game problem; a sort menu is a separate concern.
- **Persisting the batch log across process death.** The log is a progress aid, not a record; it
  is in-memory and clears when the screen is left. A persisted run history would need its own
  entity, migration, and retention policy for little gain.
- **Filtering by anything but name** (genre, HLTB status, completion). Name search only.
- **Changing how XP is computed.** The badge reports the existing computation; it does not
  introduce a second definition of XP.
- **Bulk goal tagging** via the new multi-select mode. Selection drives HLTB refresh only, so the
  mode has exactly one meaning.
