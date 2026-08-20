## 1. Display-density control becomes symbolic

- [x] 1.1 Add an `icon` property to `GameListDensity`, mapping `LIST` to a stacked-rows glyph, `GRID` to a 2×2 grid glyph, and `COMPACT_GRID` to a denser grid glyph from the Tabler set already in use
- [x] 1.2 Replace `Text(density.label)` in `GameListDensityControl` with an `Icon`, keeping the button's tap target at the Material minimum
- [x] 1.3 Set the button's `contentDescription` to the active density's `label`, so the name is still announced
- [x] 1.4 Add each density's icon as the `leadingIcon` of its `DropdownMenuItem`, keeping the existing label text and trailing check mark
- [x] 1.5 Confirm the collection overview (`CollectionScreen.kt:382`), which shares this control, renders correctly with the icon form

## 2. Library sort direction

- [x] 2.1 Add a `LibrarySortDirection` enum (`ASCENDING`, `DESCENDING`) in `domain/`, persisted by constant name, with the same rename warning the existing sort enums carry
- [x] 2.2 Add `LibrarySortKey.defaultDirection`: `ASCENDING` for `NAME`, `DESCENDING` for `PLAYTIME`, `RECENT_ACTIVITY`, and `XP_CONTRIBUTED` — matching today's fixed behaviour exactly
- [x] 2.3 Extend `LibrarySortPrefs` with `focusDirection` and `libraryDirection`, each defaulting to its key's default direction
- [x] 2.4 Add two Preferences DataStore keys in `SettingsDataStore` and a setter per list; absence reads as the key's default direction
- [x] 2.5 Rewrite `comparatorFor` to build the ascending comparator per key, and apply the direction at the one place that composes it — never by duplicating the four comparators
- [x] 2.6 Keep the `thenBy { appId }` tie-break stable under reversal so equal rows do not shuffle when direction flips
- [x] 2.7 In `sortedFor`, apply direction to the sort comparator only, leaving the relevance tier comparator ascending, so reversal never inverts search ranking
- [x] 2.8 Add a direction chevron to `SortControl` beside the existing `ArrowsSort` icon; tapping it flips direction without opening the menu
- [x] 2.9 Give the chevron a `contentDescription` naming both the current direction and what tapping it will do
- [x] 2.10 Wire `setFocusSortDirection` / `setLibrarySortDirection` through `LibraryViewModel` to both `SectionHeader` call sites
- [x] 2.11 Add optional `focusDirection` / `libraryDirection` fields to the backup file's `librarySort` block, tolerating their absence on import by applying the key's default

## 3. Achievement counts in the least dense grid

- [x] 3.1 Split `GameListField.BADGES` into `ACHIEVEMENT_COUNT` and `XP_CONTRIBUTION`
- [x] 3.2 Give `LIST` both fields; give `GRID` `ACHIEVEMENT_COUNT` only; give `COMPACT_GRID` neither
- [x] 3.3 Replace `showsBadges` with `showsAchievementCount` and `showsXpContribution`, updating both `LibraryGameRow` call sites
- [x] 3.4 Render `AchievementCountLabel` in `LibraryGameCell` under the game name, gated on `density.showsAchievementCount`, so the existing "100% Completed" pill carries into the grid unchanged
- [x] 3.5 Confirm the cell's fixed `aspectRatio` still accommodates the added line at `GRID`, adjusting the ratio rather than truncating the name if it does not
- [x] 3.6 Confirm `isStrictSubsetOf` still holds across the ladder after the field split

## 4. Achievement-rarity header

- [x] 4.1 In `RarityBreakdownCard`'s header `Row`, give the count `Text` `maxLines = 1` and `softWrap = false`
- [x] 4.2 Give the optional "Show rarest" `TextButton` `Modifier.weight(1f, fill = false)` so it yields first, matching the technique `GameBadges` already uses
- [x] 4.3 Leave the count unabbreviated — no thousands rounding

## 5. Settings Sync card layout

> Apply only after `add-offline-steam-assets` and `add-guided-first-run-setup` have landed; both add
> controls to this section, and arranging it earlier arranges a set that is about to change.

- [ ] 5.1 Restructure `SyncCard` from one `SpaceBetween` row into a `Column` of per-operation rows
- [ ] 5.2 Give the Steam library row its name, the last-sync time, and the `Sync now` button
- [ ] 5.3 Give the achievements row its name, a one-line description of what a full refresh does, and the refresh action
- [ ] 5.4 Present genre enrichment as a status row with no control
- [ ] 5.5 Verify both actions keep their existing enabled/disabled conditions — `Sync now` disabled while syncing, refresh disabled while reconciling — since both paths enqueue under one unique work name with `KEEP`
- [ ] 5.6 Fold in whatever controls the two prerequisite changes added to this section, so the result arranges the final set

## 6. Tests

- [x] 6.1 Unit-test that each sort key reversed produces the exact reverse of its ascending order, tie-break included
- [x] 6.2 Unit-test that reversal under an active search leaves relevance tiers in ascending order and reverses only within a tier
- [x] 6.3 Unit-test that games with no value for a key are ordered last ascending and first reversed
- [x] 6.4 Unit-test that an absent stored direction resolves to the key's default, reproducing pre-change ordering for all four keys
- [x] 6.5 Unit-test that a backup written without direction fields imports to the per-key defaults
- [x] 6.6 Extend the existing density-ladder test to cover the split `ACHIEVEMENT_COUNT` / `XP_CONTRIBUTION` fields and assert the ladder is still a strict subset chain

## 7. Verification

- [x] 7.1 `./gradlew :app:testDebugUnitTest :gamification:test`
- [x] 7.2 On device: confirm the search field is the same width at all three densities, and that changing density does not reflow the header row
- [ ] 7.3 On device: reverse each Library list independently, leave and return, and confirm both directions persisted and did not affect each other
- [x] 7.4 On device: switch to the least dense grid and confirm achievement counts appear, including the "100% Completed" pill on a fully-completed game
- [x] 7.5 On device: confirm the densest grid gained nothing
- [x] 7.6 Verify the rarity header renders on one line with a simulated four-digit unlocked total
- [ ] 7.7 On device: confirm the Sync section pairs each status with its own action and that both controls still disable while their work runs
