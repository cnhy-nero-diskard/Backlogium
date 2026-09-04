## Why

A family-shared game's tracked playtime is structurally incomplete: Backlogium only knows what it
personally observed (presence-derived sessions), never Steam's own total, since that total isn't
reported for a game the player doesn't own. `game-sources` already discloses this limitation and
points at enabling background monitoring as the remedy, but offers no way to correct an estimate
that is already known to be wrong — the player may know, from memory or Steam's own in-client
playtime display, roughly how many hours they've actually put into a borrowed title. Separately,
once a library has several borrowed games mixed into "Your games," there is no way to isolate them
— every other meaningful slice of the library (genre, HLTB coverage) already has a filter chip
except this one.

## What Changes

- The game detail screen gains a "Set hours played" action, visible only for a family-shared game,
  letting the player enter an hours estimate that is stored as a per-game, freely re-editable
  minutes offset — additive on top of whatever presence-derived sessions have tracked, the same
  spirit as the existing owned-game Steam-history backfill but independent of it (a new column, not
  a reuse of `Game.backfillMinutes`, so the unrelated whole-library backfill reset can never zero a
  player's manual estimate).
- That manual estimate is included everywhere a family-shared game's playtime already feeds
  something: XP (`GamificationUpdater`), the Completed/Dropped/Almost-done derived-collection rules
  (`SmartCollections.smartCollectionPlaytimeMinutes`), and the completion-progress bar and general
  playtime display driven by `GameSource.displayedPlaytimeMinutes` (Library rows, Collections
  member cards, Home).
- The Library screen gains a "Family Shared" filter chip, alongside the existing genre and "Not
  covered" filters, that narrows both the Focus and "Your games" sections to family-shared games
  only.

## Capabilities

### New Capabilities
(none)

### Modified Capabilities
- `game-sources`: adds a manual playtime estimate for a family-shared game, additive with tracked
  time and counted on the same terms as tracked time everywhere playtime is consumed; adds a
  Library filter scoped to family-shared games.

## Impact

- `app/src/main/java/com/example/backlogium/data/local/entity/Game.kt` — new column
  (`manualSharedMinutes` or similar), defaulted to 0.
- `app/src/main/java/com/example/backlogium/data/local/BacklogiumDatabase.kt` +
  `DatabaseModule.kt` — new Room migration (v26 → v27), additive `ALTER TABLE`.
- `app/src/main/java/com/example/backlogium/data/local/dao/GameDao.kt` — new single-game update
  query, SQL-guarded to `source = 'FAMILY_SHARED'` (matching `deleteSharedGame`'s existing pattern),
  so the write path itself cannot touch an owned game's row regardless of what the UI passes.
- `app/src/main/java/com/example/backlogium/domain/SetSharedGamePlaytimeUseCase.kt` (new) — the
  write's owner, mirroring `PlaytimeBackfillUseCase`'s shape so the edit can trigger an immediate
  gamification recompute the same way that class's import/reset already does; neither
  `GameRepository` nor `GameDetailViewModel` currently has a `GamificationUpdater` dependency to do
  this inline.
- `app/src/main/java/com/example/backlogium/domain/GamificationUpdater.kt` — third term in the XP
  playtime sum (`backfillMinutes + trackedMinutes + manualSharedMinutes`).
- `app/src/main/java/com/example/backlogium/data/backup/BackupExportMapper.kt` — same sum
  duplicated for the backup XP snapshot.
- `app/src/main/java/com/example/backlogium/domain/SmartCollections.kt` —
  `smartCollectionPlaytimeMinutes`'s `FAMILY_SHARED` branch gains the manual term.
- `app/src/main/java/com/example/backlogium/domain/GameSource.kt` — `displayedPlaytimeMinutes`
  gains a manual-minutes parameter for its `FAMILY_SHARED` branch, threaded through its call sites
  (`CollectionViewModel.kt`, `HomeViewModel.kt`).
- `app/src/main/java/com/example/backlogium/ui/library/LibraryViewModel.kt` — its own private,
  identically-named but separately-implemented `LibraryGame.displayedPlaytimeMinutes(xp)` (not a
  call site of the function above, despite the shared name — see design.md) gets the same
  `FAMILY_SHARED`-branch fix independently, since it is what actually drives the Library screen's
  playtime display, sort order, and completion-progress bar.
- `app/src/main/java/com/example/backlogium/ui/gamedetail/GameDetailViewModel.kt` +
  `GameDetailScreen.kt` — new "Set hours played" action and dialog, gated on `isFamilyShared`,
  alongside the existing `RemoveSharedGameAction`; `GameSummaryUi.headlineMinutes` and
  `Content.toSummary()`'s inline `xpContributed` computation (a second, independent duplicate of
  the backfill-plus-tracked sum, specific to this screen) both gain the manual term too, so the
  screen that hosts the new control stays consistent with what it just let the player set.
- `app/src/main/java/com/example/backlogium/ui/library/LibraryScreen.kt` +
  `LibraryViewModel.kt` — new "Family Shared" `FilterChip`, mirroring the existing "Not covered"
  toggle.
- `app/src/main/java/com/example/backlogium/data/backup/BackupFile.kt` +
  `BackupExportMapper.kt` + `BackupMergeEngine.kt` — new nullable field on `BackupGame`, mapped and
  merged the same way `backfillMinutes` already is.
- No change to `openspec/specs/playtime-backfill` (the existing whole-library, owned-game, one-time
  import stays exactly as specified — this is a separate, per-game, always-editable mechanism scoped
  to family-shared games) or to `openspec/specs/gamification`/`smart-collections` (both already
  describe their inputs generically enough — "tracked minutes", "playtime" — that this change is an
  upstream composition detail, the same precedent `playtime-backfill` itself set).
