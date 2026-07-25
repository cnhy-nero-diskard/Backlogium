# Tasks — Library navigation, pinning, and targeted HLTB refresh

> **Invariant to preserve:** each `appId` must appear in exactly one Library section. A duplicate
> key across `LazyColumn` items crashes Compose — see the existing defensive filter in
> `LibraryViewModel` that removes goal ids from the backlog list. The new Pinned section must be
> excluded from both other lists the same way.

## 1. Pinning — persistence
- [ ] 1.1 `Game`: add `pinned: Boolean = false`
- [ ] 1.2 Bump `BacklogiumDatabase`; additive migration
  (`ALTER TABLE games ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0`)
- [ ] 1.3 Register the migration in `DatabaseModule`
- [ ] 1.4 `GameDao`: `observePinned()` query and a pin/unpin update
- [ ] 1.5 `GameRepository`: `pin(appId)` / `unpin(appId)`
- [ ] 1.6 **`SteamSyncWorker.persistPoll`: preserve `pinned` when rebuilding `Game` rows** (same
  treatment as `isGoal` and `backfillMinutes`) — add a regression test; this is the likeliest bug

## 2. Pinning — UI
- [ ] 2.1 `LibraryViewModel`: a `pinned` list; exclude pinned ids from `goalGames` and `backlog`
- [ ] 2.2 `LibraryScreen`: Pinned section rendered first, hidden when empty
- [ ] 2.3 A pinned goal game keeps its goal presentation (completion progress bar) and goal actions
- [ ] 2.4 Pin/unpin action in the 3-dot dialog
- [ ] 2.5 Verify no `appId` can appear in two sections (test the just-pinned-just-tagged race)

## 3. Search
- [ ] 3.1 `LibraryUiState`: a `query` field; ViewModel-side case-insensitive `contains` filter over
  all three lists
- [ ] 3.2 `LibraryScreen`: search field as the first item, above the HLTB controls
- [ ] 3.3 Section headings retained only for sections with matches
- [ ] 3.4 Empty state when the filter matches nothing
- [ ] 3.5 Clear action restores the full list

## 4. XP contribution badge
- [ ] 4.1 Expose per-game tracked minutes (`SessionDao.trackedMinutesByGame()` already exists) and
  per-game unlocked achievements to the Library
- [ ] 4.2 Derive per game: `Gamification.gameXp(backfill + tracked, completionistMinutes)` +
  `Gamification.achievementXp(that game's unlocked achievements by snapshotPercent)`
- [ ] 4.3 Render as a compact badge beside the achievement badge, labelled as contributed XP
- [ ] 4.4 Sanity check: the sum of all badges equals the profile's `totalXp` (the correctness test
  for this feature)
- [ ] 4.5 No changes to `:gamification` or `GamificationUpdater`

## 5. Batch progress + log
- [ ] 5.1 `HltbRepository.refreshBatch`: widen `onProgress` to `(done, total, name, status?)` with a
  default so existing callers compile; `null` status = lookup failed
- [ ] 5.2 `HltbRefreshWorker`: publish `done`, `total`, current game name, and outcome via
  `setProgress`
- [ ] 5.3 `SyncScheduler`: expose a typed progress flow from `WorkInfo.progress` instead of only the
  `hltbRefreshInProgress` boolean (keep the boolean for existing callers)
- [ ] 5.4 `LibraryViewModel`: accumulate progress emissions into a rolling in-memory log
- [ ] 5.5 `LibraryScreen`: determinate progress bar ("12 / 240") plus a compact scrolling log
- [ ] 5.6 Returning to the screen mid-run shows correct progress with a log that resumes from that
  point (documented behavior, not a bug)
- [ ] 5.7 `HltbRepositoryTest`: per-game outcomes reported; failed lookup distinguished from no match

## 6. Targeted batch
- [ ] 6.1 `HltbRefreshWorker`: accept an optional appId array input; when present, refresh exactly
  those games with `force = true`
- [ ] 6.2 `SyncScheduler.refreshHltbNow(appIds)` overload
- [ ] 6.3 `LibraryViewModel`: transient selection set keyed by `appId`, independent of the active filter
- [ ] 6.4 `LibraryScreen`: long-press enters selection mode; action bar with count +
  "HowLongToBeat lookup (N)"; clear exits
- [ ] 6.5 Selection survives filtering (hidden games stay selected and counted)
- [ ] 6.6 Selection clears on navigation away; never persisted
- [ ] 6.7 Tap outside selection mode still opens game detail

## 7. Docs & specs
- [ ] 7.1 Update `docs/ui-screens-descriptor.md`
- [ ] 7.2 Verify the `app-ui` and `hltb-data` spec deltas match the built behavior
