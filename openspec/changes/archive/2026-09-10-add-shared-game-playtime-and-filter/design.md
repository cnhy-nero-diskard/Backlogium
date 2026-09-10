## Context

See proposal.md - Why. Two facts from research shape this design:

- **Three independent functions already combine a family-shared game's playtime with something
  else, and all three must learn about the new manual term — this is not one call chain.**
  `SmartCollections.smartCollectionPlaytimeMinutes(source, steamPlaytimeMinutes,
  importedPlaytimeMinutes, sessionMinutes)` feeds derived-collection membership; its
  `FAMILY_SHARED` branch today returns bare `sessionMinutes`, ignoring `importedPlaytimeMinutes`
  entirely (harmless today, since a shared game's `backfillMinutes` is always 0 — nothing has ever
  been able to set it). `GameSource.displayedPlaytimeMinutes(steamPlaytimeMinutes,
  trackedMinutes)` (`domain/GameSource.kt`) feeds Collections and Home
  (`CollectionViewModel.kt:64,272,316`, `HomeViewModel.kt:343`, each calling it via
  `game.source.displayedPlaytimeMinutes(...)`). **Separately again**,
  `LibraryViewModel.kt:652` declares its own **private, identically-named but differently-signed**
  `LibraryGame.displayedPlaytimeMinutes(xp: XpInputs)` — an unrelated function that happens to
  share a name with the domain one, called at `LibraryViewModel.kt:598,626` with no receiver
  prefix (so name-resolves to the private one, not the domain extension). This is the function
  that actually drives the Library screen's playtime display, sort order, and completion-progress
  bar, per its own doc comment — and it is *not* a call site of `GameSource.displayedPlaytimeMinutes`
  at all, despite the shared name. All three — the two real call sites of the domain function, the
  separate Library-local function, and `smartCollectionPlaytimeMinutes` — need the manual term
  added independently; fixing only the domain-named one would leave the Library screen itself,
  the most visible surface, unfixed.
- **The game detail screen itself duplicates the sum twice more.**
  `GameDetailViewModel.kt`'s `Content.toSummary()` inlines `game.backfillMinutes + trackedMinutes`
  a second time (independent of `GamificationUpdater`) to compute the screen's own displayed
  `xpContributed` figure, and `GameSummaryUi.headlineMinutes` reads `trackedMinutes` directly (no
  backfill/manual term at all) for the screen's headline playtime figure when `isFamilyShared`.
  Both need the manual term for the detail screen — the very screen this feature's own entry point
  lives on — to stay internally consistent with what it just let the player set.
- **`Game.backfillMinutes` cannot be reused.** `PlaytimeBackfillUseCase.reset()` zeros
  `backfillMinutes` for every game via `gameDao.applyBackfill(gameDao.getAll().associate { it.appId
  to 0 })` — an unrelated, whole-library, owned-game-scoped action that would silently wipe a
  player's manual shared-game estimate if it shared the column. This is why the proposal calls for
  a new, independent field.

## Goals / Non-Goals

**Goals:**
- A family-shared game's manual playtime estimate is additive with tracked time and counts
  wherever tracked time already counts (XP, derived collections, completion progress, display).
- The estimate is freely re-editable, independent of the unrelated owned-game history backfill.
- A Library filter isolates family-shared games, composing with existing filters (AND).

**Non-Goals:**
- No change to the owned-game Steam-history backfill (`playtime-backfill` capability) — it keeps
  its own one-time, whole-library, opt-in semantics untouched.
- No change to `Game.targetMinutes`/numeric goal targets — confirmed dormant (`GameRepository.kt`
  comment: "minutes target is retired"); the "Focus" flag (`isGoal`) is unrelated to this feature
  and untouched.
- No attempt to verify the manual estimate against any external source — it is explicitly an
  unverified player estimate, same epistemic status the existing disclosure already assigns to
  tracked time itself.

## Decisions

### 1. A new `Game.manualSharedMinutes: Int = 0` column, not a reuse of `backfillMinutes`

Per Context: reusing `backfillMinutes` would let `PlaytimeBackfillUseCase.reset()` silently discard
a player's manual estimate. A dedicated column keeps the two mechanisms — one whole-library and
one-time, the other per-game and always-editable — fully independent, matching how `source` and
`backfillMinutes` already coexist as separate concerns on the same row.

Room migration `MIGRATION_26_27`: `ALTER TABLE games ADD COLUMN manualSharedMinutes INTEGER NOT
NULL DEFAULT 0`, following the exact shape of the original `backfillMinutes` migration
(`MIGRATION_3_4`). Registered in `DatabaseModule.kt` alongside the existing migration list.

### 2. Write path is guarded in SQL, not only in the UI

`GameDao` gains `setManualSharedMinutes(appId, minutes)` with `WHERE source = 'FAMILY_SHARED'` in
its `UPDATE` — the same defense-in-depth pattern `deleteSharedGame` already uses. A UI bug that
somehow surfaced the action for an owned game would still no-op at the database layer rather than
silently mutating an owned game's row.

### 3. All three playtime-combination functions gain the same third term, independently

`smartCollectionPlaytimeMinutes`'s `FAMILY_SHARED` branch becomes `sessionMinutes +
manualSharedMinutes`. `GameSource.displayedPlaytimeMinutes`'s `FAMILY_SHARED` branch becomes
`trackedMinutes + manualSharedMinutes`. `LibraryViewModel`'s separate, private
`LibraryGame.displayedPlaytimeMinutes(xp: XpInputs)` gets the same treatment: its `FAMILY_SHARED`
branch becomes `(xp.trackedByGame[appId] ?: 0) + manualSharedMinutes` (`manualSharedMinutes` read
directly off the `LibraryGame`/`Game` row, already in scope). These stay three separate functions
with their own call sites rather than being unified into one — the Library-local/domain naming
collision noted in Context is pre-existing and out of scope to resolve here; unifying it is a
larger, unrelated refactor this change has no reason to force. `GamificationUpdater.compute()`'s
playtime sum gains the same third term for the same reason (`backfillByGame[appId] +
trackedByGame[appId] + manualByGame[appId]`), matching the pattern its own docstring already
describes for the backfill/tracked pair. `BackupExportMapper`'s duplicate XP-snapshot sum gets the
identical third term so a restored backup's snapshotted XP stays consistent with a live recompute.

### 4. Manual entry replaces on edit, not accumulates

Per the earlier decision (recorded in the conversation, not re-litigated here): re-entering a value
replaces the stored offset rather than adding another delta on top, because the player is stating
"my best current estimate of total hours," not logging an incremental session. This matches how the
owned-game backfill's own value is a single frozen figure, not a running log.

### 5. UI: a new dialog next to the existing `RemoveSharedGameAction`, hours in, minutes stored

`GameSummaryUi` already carries `isFamilyShared`; the new "Set hours played" `TextButton` +
`AlertDialog` (with a numeric `OutlinedTextField`) sits in the same `if (summary.isFamilyShared)`
block as `RemoveSharedGameAction`, following its existing local-`remember` confirmation-state
pattern. The player enters whole or fractional hours (matching how playtime is normally discussed);
the dialog converts to minutes (`(hours * 60).roundToInt()`) before calling into the ViewModel,
consistent with every other playtime field being stored in minutes.

### 6. Library filter mirrors the existing "Not covered" `FilterChip` exactly

`showFamilySharedOnly: Boolean` via `rememberSaveable`, a new `FilterChip` beside the existing one,
a `filterBySource` list extension analogous to `filterByHltbCoverage`, folded into the same
`remember(...)` blocks that already recompute `visibleGoalGames`/`visibleBacklog`, and reset in the
existing `DisposableEffect`. `LibraryRow`/its concrete types already expose `isFamilyShared`, so no
new field is needed there.

### 7. The write goes through a small dedicated use case, mirroring `PlaytimeBackfillUseCase`, not `GameRepository` directly

Setting or clearing the estimate changes XP, so it needs an immediate recompute the same way
`PlaytimeBackfillUseCase`'s import/reset and `FamilySharedGameRepository.remove()`'s removal both
already trigger one. Neither `GameRepository` (where the natural `tagGoal`/`untagGoal`-style setter
would otherwise live) nor `GameDetailViewModel` currently depends on `GamificationUpdater`. Rather
than adding that dependency to a repository whose existing responsibilities are read-side joins and
Steam/HLTB IO, a new `SetSharedGamePlaytimeUseCase` (`domain/`) takes the same constructor shape as
`PlaytimeBackfillUseCase` (`gameDao`, `settings`, `gamificationUpdater`, `time`,
`derivedStateWrites`) and owns: writing the minutes (guarded to `source == FAMILY_SHARED`, checked
in Kotlin as well as the DAO's own SQL guard — belt and suspenders, not a substitute for it), then
recomputing under the same `derivedStateWrites.withLock` protocol every other recompute site uses.

## Risks / Trade-offs

- **A player could enter an estimate lower than what tracked sessions already show**, making the
  displayed total *decrease* when tracked-only would have been higher. → Accepted: the estimate
  replaces the manual term, not the tracked term — tracked sessions are never discarded (Requirement
  "Clearing the estimate does not affect tracked sessions"), so the combined total can only ever be
  `tracked + max(0, manual)`; a manual entry lower than tracked minutes simply contributes 0 on top
  rather than reducing the shown total below tracked-only. This is enforced by `.coerceAtLeast(0)`
  already present in both combination functions, applied to the *sum*, not by clamping the manual
  input itself against tracked minutes at entry time (which would require the dialog to know live
  tracked minutes and would make a legitimate "my estimate is actually lower than what got tracked,
  e.g. after correcting a double-counted session" impossible to express). The stored manual value
  itself may still be any non-negative number the player enters.
- **Two independent combination functions must both be kept in sync by hand.** → Accepted per
  Decision 3; `smartCollectionPlaytimeMinutes` already has coverage in `SmartCollectionsTest`, which
  the tasks below extend directly. `displayedPlaytimeMinutes` has no dedicated test file today —
  the tasks below add one (`GameSourceTest`) rather than leaving it exercised only indirectly
  through the ViewModels that call it, so a future change to either function is caught by its own
  test rather than a shared one masking a regression in the other.
