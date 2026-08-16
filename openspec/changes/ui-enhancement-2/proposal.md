## Why

Five unrelated UI defects and gaps, each too small to justify its own change and each cheap enough
that reviewing them together costs less than reviewing them apart. They are grouped by size, not by
subject — the only thing they share is that none of them changes what the app knows, only how it
presents what it already knows.

Four are visible defects a user hits today:

- **The Library's display-density control renders its label as text.** `GameListDensityControl`
  puts `Text(density.label)` in an unconstrained `TextButton`, and the Library header row gives the
  search field `weight(1f)` — so the field yields and "Compact grid" takes the space. Choosing the
  densest layout makes the search box the smallest it ever gets, which is exactly backwards.
- **The Analytics achievement-rarity card wraps its own count.** `"${breakdown.total} unlocked"`
  sits in a `Row` after a `Spacer(weight(1f))` and a "Show rarest" `TextButton`, with no width of
  its own. At four digits the text no longer fits and the word "unlocked" breaks mid-word.
- **Grid densities drop achievement counts entirely.** A user who prefers the grid loses the
  unlocked-of-total figure that the list shows, even though the grid cell has room for it below the
  name.
- **The Settings Sync card scatters its content.** Two status lines on the left, two differently-
  weighted actions stacked on the right, and no visual relationship between the status and the
  action it describes.

One is a missing affordance: **Library sorts have no direction.** Every key is fixed — descending
for playtime, recent activity, and XP, ascending for name — so "least played first" and "Z→A" are
unreachable.

## What Changes

- Replace the display-density control's text label with a density-appropriate icon, restoring the
  search field's width at every density and giving the control a stable footprint.
- Add a direction toggle to each Library list's sort control, persisted per list alongside the
  existing sort-key preference.
- Show the unlocked-of-total achievement count in the least dense grid, keeping the XP badge
  list-only so the density ladder stays a strict subset chain.
- Constrain the rarity card's header so its count cannot wrap regardless of magnitude.
- Rearrange the Settings Sync card so each action sits with the status it acts on.

## Capabilities

### Modified Capabilities

- `app-ui`: The density control is identified by symbol rather than by word and no longer competes
  with the search field for width; each Library list's sort carries a direction; achievement counts
  extend to the least dense grid; the achievement-rarity header holds its count on one line.
- `app-settings`: The Sync section's status and actions are presented as paired rows rather than as
  two opposing columns.

## Impact

- **Affected code:** `ui/components/GameListDensityControl.kt`, `ui/library/LibraryScreen.kt`,
  `ui/library/LibrarySorting.kt`, `domain/LibrarySortKey.kt`, `data/local/SettingsDataStore.kt`,
  `ui/analytics/AnalyticsScreen.kt`, `ui/settings/SettingsScreen.kt`.
- **Storage:** Two new Preferences DataStore keys for the per-list sort direction. No Room schema
  change, no migration.
- **Backup:** `BackupFile`'s `librarySort` block gains two optional direction fields. Absent fields
  restore as the existing fixed directions, so an older backup imports unchanged.
- **Dependencies:** None. The density icons come from the Tabler set already in use.

## Non-goals

- Sort direction for collection member lists. Collections store their sort on the `Collection`
  entity, so a direction there means a Room migration, a backup-format field, merge-engine handling,
  and a decision about what reversing a hand-dragged `MANUAL_SEQUENCE` order means. That is a
  separate change; this one is deliberately migration-free.
- Sort direction for History or Analytics lists.
- Any change to which games a density shows, or to the order a density presents them in.
- New sort keys.

## Sequencing

The Settings Sync card rearrangement (section 5) MUST be applied after `add-offline-steam-assets`
and `add-guided-first-run-setup`, both of which add controls to the same Settings section. Applying
it earlier arranges a set of controls that is about to change. The other four sections have no such
constraint and can be applied in any order.
