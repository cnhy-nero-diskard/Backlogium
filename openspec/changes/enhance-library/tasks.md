# Tasks — Library navigation, sorting, universal completion progress, and targeted HLTB refresh

> **No Room migration.** The only persisted additions are two Preferences DataStore keys for the
> per-list sort selections; everything else is a read-side derivation or transient view state.
>
> **Invariant to preserve:** each `appId` appears in exactly one Library section. A duplicate key
> across `LazyColumn` items crashes Compose — see the existing defensive filter in `LibraryViewModel`
> that removes tracked ids from the other list.

## 1. Universal completion progress
- [ ] 1.1 `BacklogGameUi`: add `completionistMinutes: Int?`, populated from the cached HLTB row the
  same way `GoalGameUi` already is
- [ ] 1.2 `BacklogGameRow`: render the same progress presentation `GoalGameRow` has, conditional on a
  non-null completion length
- [ ] 1.3 Extract the shared progress block so both rows use one composable rather than two copies
- [ ] 1.4 Verify a game with no HLTB data renders exactly as today (no bar, no placeholder)

## 2. Relabel both sections
- [ ] 2.1 `LibraryScreen`: section headings "Goal games" → "Focus", "Backlog" → "Your games"
- [ ] 2.2 `GoalDialog`: title and body copy → focus wording ("Add to Focus" / "Remove from Focus"),
  keeping the no-typed-target behavior
- [ ] 2.3 `LibraryScreen`: the 3-dot content description ("Manage goal") → focus wording
- [ ] 2.4 `HistoryScreen.kt:131`: "· 40m on goals" → focus wording, so the two screens agree
- [ ] 2.5 Leave `isGoal`, `goalMinutesPlayed`, `observeGoalGames()`, `observeBacklog()`,
  `QuestMode.GOAL_ONLY`, and `Game.targetMinutes` untouched — user-facing copy only, no migration, no
  engine change
- [ ] 2.6 Grep for any remaining user-visible "goal" or "backlog" string and reconcile
- [ ] 2.7 Empty-state copy: check the "No games yet" message still reads correctly under the new labels

## 3. Per-list sorting
- [ ] 3.1 A sort-key enum: playtime, name, recently played, XP contributed
- [ ] 3.2 `SettingsDataStore`: two persisted keys (one per list), defaulting to the current DAO
  ordering — name for Focus, playtime for Your games — so an upgrade renders exactly as today
- [ ] 3.3 Sort in the ViewModel, not the DAO: the XP key is a read-side derivation SQL cannot express,
  and the lists are already in memory — keep all four keys in one place
- [ ] 3.4 Directions: descending for playtime, recently played, and XP; ascending for name
- [ ] 3.5 Tie-break every key by name ascending so ordering never depends on Room's return order
- [ ] 3.6 Games with no value for the active key sort last (zero recent playtime, zero XP)
- [ ] 3.7 `LibraryScreen`: a compact sort control per section header, showing the active key
- [ ] 3.8 Sorting applies to filtered results when a search is active
- [ ] 3.9 Unit-test the comparators: each key, its tie-break, and missing-value placement

## 4. Search
- [ ] 4.1 `LibraryUiState`: a `query` field; ViewModel-side case-insensitive `contains` filter over
  both lists
- [ ] 4.2 `LibraryScreen`: search field as the first item, above the HLTB controls
- [ ] 4.3 Section headings retained only for sections with matches
- [ ] 4.4 Empty state when the filter matches nothing
- [ ] 4.5 Clear action restores the full list

## 5. XP contribution badge
- [ ] 5.1 Expose per-game tracked minutes (`SessionDao.trackedMinutesByGame()` already exists) and
  per-game unlocked achievements to the Library
- [ ] 5.2 Derive per game: `Gamification.gameXp(backfill + tracked, completionistMinutes)` +
  `Gamification.achievementXp(that game's unlocked achievements by snapshotPercent)`
- [ ] 5.3 Render as a compact badge beside the achievement badge, labelled as contributed XP
- [ ] 5.4 Sanity check: the sum of all badges equals the profile's `totalXp` (the correctness test
  for this feature)
- [ ] 5.5 No changes to `:gamification` or `GamificationUpdater`
- [ ] 5.6 Re-check row density now that every row can also carry a progress bar; demote the XP badge
  if the row no longer reads cleanly

## 6. Batch progress + log
- [ ] 6.1 `HltbRepository.refreshBatch`: widen `onProgress` to `(done, total, name, status?)` with a
  default so existing callers compile; `null` status = lookup failed
- [ ] 6.2 `HltbRefreshWorker`: publish `done`, `total`, current game name, and outcome via
  `setProgress`
- [ ] 6.3 `SyncScheduler`: expose a typed progress flow from `WorkInfo.progress` instead of only the
  `hltbRefreshInProgress` boolean (keep the boolean for existing callers)
- [ ] 6.4 `LibraryViewModel`: accumulate progress emissions into a rolling in-memory log
- [ ] 6.5 `LibraryScreen`: determinate progress bar ("12 / 240") plus a compact scrolling log
- [ ] 6.6 Returning to the screen mid-run shows correct progress with a log that resumes from that
  point (documented behavior, not a bug)
- [ ] 6.7 `HltbRepositoryTest`: per-game outcomes reported; failed lookup distinguished from no match

## 7. Targeted batch
- [ ] 7.1 `HltbRefreshWorker`: accept an optional appId array input; when present, refresh exactly
  those games with `force = true`
- [ ] 7.2 `SyncScheduler.refreshHltbNow(appIds)` overload
- [ ] 7.3 `LibraryViewModel`: transient selection set keyed by `appId`, independent of the active filter
- [ ] 7.4 `LibraryScreen`: long-press enters selection mode; action bar with count +
  "HowLongToBeat lookup (N)"; clear exits
- [ ] 7.5 Selection survives filtering (hidden games stay selected and counted)
- [ ] 7.6 Selection clears on navigation away; never persisted
- [ ] 7.7 Tap outside selection mode still opens game detail

## 8. Docs & specs
- [ ] 8.1 Update `docs/ui-screens-descriptor.md` — the Focus / Your games relabels, universal progress,
  and the per-list sort controls
- [ ] 8.2 Verify the `app-ui` and `hltb-data` spec deltas match the built behavior
- [ ] 8.3 Note the deliberate label/identifier mismatch (UI "Focus" / "Your games" vs `isGoal` /
  `observeBacklog()`) where a future reader will hit it
