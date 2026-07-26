# Tasks — Game detail: summary and achievement legibility

> No new network calls: `GetSchemaForGame` already returns `description` and `hidden`; the mapper
> drops them today.

## 1. Persistence
- [ ] 1.1 `AchievementSchemaDto`: add `description: String = ""` and `hidden: Int = 0`
- [ ] 1.2 `Achievement`: add `description: String? = null`, `hidden: Boolean = false`
- [ ] 1.3 Bump `BacklogiumDatabase`; additive migration adding both columns (`description TEXT`,
  `hidden INTEGER NOT NULL DEFAULT 0`)
- [ ] 1.4 Register the migration in `DatabaseModule`
- [ ] 1.5 `AchievementMerge`: carry description/hidden through; **preserve `snapshotPercent`
  semantics exactly** (it must still never be overwritten after first unlock)
- [ ] 1.6 `AchievementMergeTest`: description/hidden merged; `snapshotPercent` immutability intact
- [ ] 1.7 No forced re-fetch: confirm the freshness gate is untouched

## 2. Summary section
- [ ] 2.1 `GameDetailViewModel`: join the game row, its HLTB row, per-game tracked minutes, and
  `backfillMinutes` into the ui state
- [ ] 2.2 Derive the game's XP contribution — **the same formula as `enhance-library`'s badge**; if
  both changes land, extract one shared function rather than duplicating
- [ ] 2.3 `GameDetailScreen`: summary `item {}` blocks above the achievement list — art, playtime
  (tracked vs imported when applicable), HLTB lengths when known, achievement completion, XP
- [ ] 2.4 Omit HLTB lengths entirely when there is no resolved data (no zeros, no placeholders)
- [ ] 2.5 Keep the summary compact enough that the first achievement row is at or near the fold
- [ ] 2.6 Game with no achievements: summary still renders; the achievement area explains the absence
  (replaces today's whole-screen `EmptyState` early return)

## 3. Sorting
- [ ] 3.1 Sort modes: date achieved (default, descending) and rarity (rarest first)
- [ ] 3.2 Rarity key: `snapshotPercent ?: globalPercent`
- [ ] 3.3 Locked achievements grouped after unlocked ones in both modes
- [ ] 3.4 Sort control as a compact row above the list; transient state, not persisted
- [ ] 3.5 Unit-test the comparator: nulls, all-locked, all-unlocked, mixed

## 4. Unlock rate on each row
- [ ] 4.1 `AchievementUi`: add the display percent, resolved as `snapshotPercent ?: globalPercent`
  (the same key the rarity sort uses — reuse one function so they cannot diverge)
- [ ] 4.2 Render as "0.8% of players have this" beside or beneath the tier/XP line
- [ ] 4.3 Render nothing when both percentages are null (no zero, no placeholder)
- [ ] 4.4 Verify a Legendary row never displays a percentage that contradicts its tier

## 5. Descriptions
- [ ] 5.1 `AchievementUi`: add `description` and `hidden`
- [ ] 5.2 Render the description beneath the name; render nothing when absent
- [ ] 5.3 Hidden + locked → "Hidden achievement" label
- [ ] 5.4 Hidden + unlocked with a description → render normally
- [ ] 5.5 Confirm the existing locked-row alpha treatment still reads well once the row carries a
  description and an unlock rate as well

## 6. Docs & specs
- [ ] 6.1 Update `docs/ui-screens-descriptor.md`
- [ ] 6.2 Verify the `app-ui` and `steam-achievements` spec deltas match the built behavior
