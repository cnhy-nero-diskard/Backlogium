# Tasks — Game detail: summary and achievement legibility

> No new network calls: `GetSchemaForGame` already returns `description` and `hidden`; the mapper
> drops them today.

## 1. Persistence
- [x] 1.1 `AchievementSchemaDto`: add `description: String = ""` and `hidden: Int = 0`
- [x] 1.2 `Achievement`: add `description: String? = null`, `hidden: Boolean = false`
- [x] 1.3 `BacklogiumDatabase`: version 6 → 7, `MIGRATION_6_7` adding both columns (`description
  TEXT`, `hidden INTEGER NOT NULL DEFAULT 0`) — no other open proposal has a pending schema change,
  so this bump doesn't need to coordinate with anything
- [x] 1.4 Register the migration in `DatabaseModule`
- [x] 1.5 `AchievementMerge`: carry description/hidden through; **preserve `snapshotPercent`
  semantics exactly** (it must still never be overwritten after first unlock)
- [x] 1.6 `AchievementMergeTest`: description/hidden merged; `snapshotPercent` immutability intact
- [x] 1.7 No forced re-fetch: confirm the freshness gate is untouched

## 2. Summary section
- [x] 2.1a `LibraryGame` (`GameRepository.kt`): add `mainExtraMinutes` and `allStylesMinutes`,
  carried through `Game.toDomain()` alongside the existing `completionistMinutes`/`mainStoryMinutes`
  — `HltbData` already has all four, `LibraryGame` currently drops two
- [x] 2.1 `GameDetailViewModel`: join the game row (now with all four HLTB lengths via 2.1a),
  per-game tracked minutes (`SessionDao.observeTrackedMinutesByGame`), and `backfillMinutes` into
  the ui state
- [x] 2.2 Derive the game's XP contribution by calling `LibraryXp.contribution(input, cfg)`
  (`domain/LibraryXp.kt`) directly — it already exists from `enhance-library`; build its
  `GameXpInput` from tracked+backfill minutes, completionist minutes, and unlocked rarity snapshots
- [x] 2.3 `GameDetailScreen`: summary `item {}` blocks above the achievement list — art, playtime
  (tracked vs imported when applicable), HLTB lengths when known, achievement completion, XP
- [x] 2.4 Omit HLTB lengths entirely when there is no resolved data (no zeros, no placeholders)
- [x] 2.5 Keep the summary compact enough that the first achievement row is at or near the fold
- [x] 2.6 Game with no achievements: summary still renders; the achievement area explains the absence
  (replaces today's whole-screen `EmptyState` early return)

## 3. Sorting
- [x] 3.0 `GameAchievement` (`AchievementRepository.kt`) currently exposes only `rarityPercent`
  (the frozen `snapshotPercent`) — widen it (and its mapper off `Achievement`) to also carry
  `unlockedAt: Long?` and `globalPercent: Double?`; neither sort key exists on the DTO today
- [x] 3.1 Sort modes: date achieved (default, descending) and rarity (rarest first)
- [x] 3.2 Rarity key: `snapshotPercent ?: globalPercent`
- [x] 3.3 Locked achievements grouped after unlocked ones in both modes
- [x] 3.4 Sort control as a compact row above the list; transient state, not persisted
- [x] 3.5 Unit-test the comparator: nulls, all-locked, all-unlocked, mixed
  (`AchievementSortTest`, 12 cases)

## 4. Unlock rate on each row
- [x] 4.1 `AchievementUi`: add the display percent, resolved as `snapshotPercent ?: globalPercent`
  (the same key the rarity sort uses — reuse one function so they cannot diverge)
- [x] 4.2 Render as "0.8% of players have this" beside or beneath the tier/XP line
- [x] 4.3 Render nothing when both percentages are null (no zero, no placeholder)
- [x] 4.4 Verify a Legendary row never displays a percentage that contradicts its tier
  (`AchievementRowMappingTest` pins display-percent == tier-producing percent)

## 5. Descriptions
- [x] 5.1 `AchievementUi`: add `description` and `hidden`
- [x] 5.2 Render the description beneath the name; render nothing when absent
- [x] 5.3 Hidden + locked → "Hidden achievement" label
- [x] 5.4 Hidden + unlocked with a description → render normally
- [x] 5.5 Confirm the existing locked-row alpha treatment still reads well once the row carries a
  description and an unlock rate as well — kept as a whole-row alpha rather than per-element
  colouring, so "locked" stays one signal instead of three competing greys (not visually verified
  on device)

## 5a. Rarity halo (addendum — requested after the above shipped)
- [x] 5a.1 Icon glow keyed to `RarityTier`, not fixed: `ColorScheme.rarityHalo(tier)`
  (`ui/theme/Theme.kt`), a dull→vivid ramp (grey → green → steel-blue → violet → gold), reusing
  `SteelBlue`/`Gold` for RARE/LEGENDARY rather than adding two more tokens
- [x] 5a.2 `AchievementIcon` renders the halo (`Brush.radialGradient` behind the icon, in a 56dp
  box so the glow has room past the 40dp icon) only when `tier != null` — locked achievements have
  no earned tier, so no halo
- [x] 5a.3 New rarity color tokens added to `Color.kt` with light-scheme counterparts, following
  the existing `overrunExcess`-style pattern (a `ColorScheme` extension keyed off surface
  luminance, not `isSystemInDarkTheme()`)

## 6. Docs & specs
- [x] 6.1 Update `docs/ui-screens-descriptor.md`
- [x] 6.2 Verify the `app-ui` and `steam-achievements` spec deltas match the built behavior —
  every scenario traced to code; `openspec validate` passes. "Sort not persisted" holds because the
  game-detail route is its own back-stack entry, so its ViewModel (and the transient sort) is
  destroyed on pop
