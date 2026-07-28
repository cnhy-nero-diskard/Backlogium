# Tasks — Library navigation, sorting, universal completion progress, and targeted HLTB refresh

> **No Room migration.** The only persisted additions are two Preferences DataStore keys for the
> per-list sort selections; everything else is a read-side derivation or transient view state.
>
> **Invariant to preserve:** each `appId` appears in exactly one Library section. A duplicate key
> across `LazyColumn` items crashes Compose — see the existing defensive filter in `LibraryViewModel`
> that removes tracked ids from the other list.

## 1. Universal completion progress
- [x] 1.1 `BacklogGameUi`: add `completionistMinutes: Int?`, populated from the cached HLTB row the
  same way `GoalGameUi` already is
- [x] 1.2 `BacklogGameRow`: render the same progress presentation `GoalGameRow` has, conditional on a
  non-null completion length
- [x] 1.3 Extract the shared progress block so both rows use one composable rather than two copies
- [x] 1.4 Verify a game with no HLTB data renders exactly as today (no bar, no placeholder)

## 2. Relabel both sections
- [x] 2.1 `LibraryScreen`: section headings "Goal games" → "Focus", "Backlog" → "Your games"
- [x] 2.2 `GoalDialog`: title and body copy → focus wording ("Add to Focus" / "Remove from Focus"),
  keeping the no-typed-target behavior
- [x] 2.3 `LibraryScreen`: the 3-dot content description ("Manage goal") → focus wording
- [x] 2.4 `HistoryScreen.kt:132`: "· 40m on goals" → focus wording, so the two screens agree
- [x] 2.5 `SettingsScreen.kt:573`: the quest-mode chip label `QuestMode.GOAL_ONLY -> "Goal games only"`
  → focus wording. **Not optional copy** — `QuestMode` is a live, user-reachable setting (chips at
  `SettingsScreen.kt:265`, persisted as `quest_mode` in `SettingsDataStore`), and it is the one place
  where the section's name has a functional consequence
- [x] 2.6 Leave `isGoal`, `goalMinutesPlayed`, `observeGoalGames()`, `observeBacklog()`,
  `QuestMode.GOAL_ONLY`, and `Game.targetMinutes` untouched — user-facing copy only, no migration, no
  engine change
- [x] 2.7 Grep for any remaining user-visible "goal" or "backlog" string and reconcile
- [x] 2.8 Empty-state copy: check the "No games yet" message still reads correctly under the new labels

## 3. Per-list sorting
- [x] 3.0 Widen `LibraryGame` + `Game.toDomain()` with `playtime2Weeks` — it exists on the `Game`
  entity and is currently dropped, so "recently played" has no data path until this lands (the
  XP key's inputs are section 5)
- [x] 3.1 A sort-key enum: playtime, name, recently played, XP contributed
- [x] 3.2 `SettingsDataStore`: two persisted keys (one per list), defaulting to the current DAO
  ordering — `observeGoalGames()` is `ORDER BY name ASC`, `observeBacklog()` is
  `ORDER BY playtimeForever DESC, name ASC` — so an upgrade renders exactly as today, tie-break
  included. Update the class doc comment, which currently says it holds "just the tunable
  gamification `RuleConfig`"
- [x] 3.3 Sort in the ViewModel, not the DAO: the XP key is a read-side derivation SQL cannot express,
  and the lists are already in memory — keep all four keys in one place
- [x] 3.4 Directions: descending for playtime, recently played, and XP; ascending for name
- [x] 3.5 Tie-break every key by name ascending so ordering never depends on Room's return order
- [x] 3.6 Games with no value for the active key sort last (zero recent playtime, zero XP)
- [x] 3.7 `LibraryScreen`: a compact sort control per section header, showing the active key
- [x] 3.8 Sorting applies to filtered results when a search is active
- [x] 3.9 Unit-test the comparators: each key, its tie-break, and missing-value placement

## 4. Search
- [x] 4.1 `LibraryUiState`: a `query` field; ViewModel-side case-insensitive `contains` filter over
  both lists
- [x] 4.2 `LibraryScreen`: search field as the first item, above the HLTB controls
- [x] 4.3 Section headings retained only for sections with matches (note `LibraryScreen.kt:121`
  currently renders the second heading unconditionally)
- [x] 4.4 Empty state when the filter matches nothing, rendered **inside** the `LazyColumn` beneath
  the search field
- [x] 4.5 Keep the pre-column early-return at `LibraryScreen.kt:78` keyed to the **unfiltered**
  library. If filtered lists feed it, a no-match query unmounts the search field with the rest of the
  screen and the user cannot clear the query that caused it
- [x] 4.6 Clear action restores the full list

## 5. XP contribution badge
- [x] 5.1 Expose per-game tracked minutes (`SessionDao.trackedMinutesByGame()` already exists) and
  `Game.backfillMinutes` to the Library
- [x] 5.2 Expose per-game unlocked achievements **with their `snapshotPercent`** — note
  `AchievementRepository.counts` gives unlocked/total counts only, which `achievementXp` cannot tier
  from. This is a new query, not a reuse of the existing counts flow
- [x] 5.3 `LibraryViewModel`: inject `SettingsRepository` and combine `ruleConfig` into the
  derivation. **Do not let `cfg` default.** `gameXp`/`achievementXp` both declare
  `cfg: RuleConfig = RuleConfig()`; `RuleConfig` is user-tunable and persisted, and every existing
  caller threads the stored value (`SteamSyncWorker.kt:180`, `PlaytimeBackfillUseCase.kt:74`,
  `UpdateRuleConfigUseCase.kt:36,44`). Omitting it compiles and renders plausible numbers that are
  wrong for anyone who edited `xpPerMinute`, `levelBase`, or the five achievement-XP values
- [x] 5.4 Derive per game: `Gamification.gameXp(backfill + tracked, completionistMinutes, cfg)` +
  `Gamification.achievementXp(that game's unlocked achievements by snapshotPercent, cfg)`
- [x] 5.5 Render as a compact badge beside the achievement badge, labelled as contributed XP
- [x] 5.6 Correctness test: the badges sum to the profile's `totalXp`. Exactness is real, not
  approximate — `Gamification.xp` sums per-game `gameXp` values that are each already
  `roundToInt()`-ed, so there is no rounding drift to absorb
- [x] 5.7 Scope that assertion to the games the Library shows. The engine sums achievements from
  `achievementDao.getAllUnlocked()` and playtime over `trackedByGame.keys + backfillByGame.keys` —
  neither is the Library list, so an orphan achievement row or an unowned backfilled game counts
  toward `totalXp` with no row to badge it
- [x] 5.8 No changes to `:gamification` or `GamificationUpdater`
- [x] 5.9 Re-check row density now that every row can also carry a progress bar; demote the XP badge
  if the row no longer reads cleanly

## 6. Batch progress + log
- [x] 6.1 `HltbRepository.refreshBatch`: widen `onProgress` to
  `(done, total, name, HltbMatchState?)` with a default so existing callers compile; `null` status =
  lookup failed. Use the domain `HltbMatchState`, not the storage `HltbMatchStatus` — the mirror
  exists so "no consumer depends on the storage enum" (its own doc comment) and the Library already
  consumes `HltbMatchState` for `hltbStatus`
- [x] 6.2 `HltbRefreshWorker`: publish `done`, `total`, current game name, and outcome via
  `setProgress`. `Data` holds no enums — send the outcome as a name string, absent = lookup failed
- [x] 6.3 `SyncScheduler`: expose a typed progress flow from `WorkInfo.progress` instead of only the
  `hltbRefreshInProgress` boolean (keep the boolean for existing callers)
- [x] 6.4 `LibraryViewModel`: accumulate progress emissions into a rolling in-memory log
- [x] 6.4a Handle two boundary edges: WorkManager clears `WorkInfo.progress` on completion, so empty
  progress `Data` means "finished", never `0 / 0`; and `refreshBatch` only calls `onProgress` inside
  its loop, so an empty target set publishes nothing at all and the UI must not read as stalled
- [x] 6.5 `LibraryScreen`: determinate progress bar ("12 / 240") plus a compact scrolling log
- [x] 6.6 Returning to the screen mid-run shows correct progress with a log that resumes from that
  point (documented behavior, not a bug)
- [x] 6.7 `HltbRepositoryTest`: per-game outcomes reported; failed lookup distinguished from no match

## 7. Targeted batch
- [x] 7.1 `HltbRefreshWorker`: accept an optional appId array input; when present, refresh exactly
  those games with `force = true`
- [x] 7.2 `SyncScheduler.refreshHltbNow(appIds)` overload
- [x] 7.2a Gate the selection's refresh action on `refreshing`, the way `HltbControls` already
  disables its two buttons. Both paths enqueue under the single unique name `hltb_refresh_now` with
  `ExistingWorkPolicy.KEEP`, so an ungated action tapped during a sweep is **dropped with no error**
  while the sweep's progress bar keeps advancing — indistinguishable from success. Not `REPLACE`
  (kills a ~6-minute sweep unannounced) and not a second work name (two progress streams for
  `SyncScheduler` to merge)
- [x] 7.3 `LibraryViewModel`: transient selection set keyed by `appId`, independent of the active filter
- [x] 7.4 `LibraryScreen`: long-press enters selection mode; action bar with count +
  "HowLongToBeat lookup (N)"; clear exits. Both rows are `Card(onClick = …)` and Material 3's `Card`
  has no long-press — each becomes a non-clickable `Card` with `Modifier.combinedClickable`
  (`@OptIn(ExperimentalFoundationApi::class)`)
- [x] 7.5 Selection survives filtering (hidden games stay selected and counted)
- [x] 7.6 Selection clears on navigation away; never persisted
- [x] 7.7 Tap outside selection mode still opens game detail

## 8. Docs & specs
- [x] 8.1 Update `docs/ui-screens-descriptor.md` — the Focus / Your games relabels, universal progress,
  the per-list sort controls, search, the XP badge, batch progress, and selection mode.
  **Budget more than a relabel:** the Library section is already stale on unrelated grounds — `:143`
  says "Entire row is tappable → opens the Goal dialog" (tap now opens game detail; a 3-dot button
  opens the dialog) and `:151` gives the dialog title as "Set as goal" / "Edit goal" (actually
  "Set as goal" / "Remove goal"). Fix those while in there rather than relabelling around them
- [x] 8.2 Verify the `app-ui` and `hltb-data` spec deltas match the built behavior
- [x] 8.3 Note the deliberate label/identifier mismatch (UI "Focus" / "Your games" vs `isGoal` /
  `observeBacklog()`) where a future reader will hit it
