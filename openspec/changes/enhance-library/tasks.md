# Tasks — Library navigation, universal completion progress, and targeted HLTB refresh

> **No Room migration.** Nothing new is persisted — every addition is a read-side derivation or
> transient view state.
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

## 2. Relabel the tracked section
- [ ] 2.1 `LibraryScreen`: section heading "Goal games" → "Focus"
- [ ] 2.2 `GoalDialog`: title and body copy → focus wording ("Add to Focus" / "Remove from Focus"),
  keeping the no-typed-target behavior
- [ ] 2.3 `LibraryScreen`: the 3-dot content description ("Manage goal") → focus wording
- [ ] 2.4 `HistoryScreen.kt:131`: "· 40m on goals" → focus wording, so the two screens agree
- [ ] 2.5 Leave `isGoal`, `goalMinutesPlayed`, `observeGoalGames()`, `QuestMode.GOAL_ONLY`, and
  `Game.targetMinutes` untouched — user-facing copy only, no migration, no engine change
- [ ] 2.6 Grep for any remaining user-visible "goal" string and reconcile

## 3. Search
- [ ] 3.1 `LibraryUiState`: a `query` field; ViewModel-side case-insensitive `contains` filter over
  both lists
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
- [ ] 4.6 Re-check row density now that every row can also carry a progress bar; demote the XP badge
  if the row no longer reads cleanly

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
- [ ] 7.1 Update `docs/ui-screens-descriptor.md` — including the Focus relabel and universal progress
- [ ] 7.2 Verify the `app-ui` and `hltb-data` spec deltas match the built behavior
- [ ] 7.3 Note the deliberate label/identifier mismatch (UI "Focus" vs `isGoal`) where a future reader
  will hit it
