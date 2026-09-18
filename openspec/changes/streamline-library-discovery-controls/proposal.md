## Why

Library exposes powerful search, density, HLTB, genre, filter, sort, selection, and row-management controls at the same visual level. This overloads routine game discovery, hides long-press behavior, and includes a Family Shared zero-result path with incorrect copy and no recovery action.

## What Changes

- Make search and active filters the primary Library controls, with density, sorting, and HowLongToBeat enrichment available through clearly labeled secondary controls.
- Represent every active filter in one coherent filter model and provide specific removal plus a single clear-all action.
- Correct zero-result messages and recovery for Family Shared, genre, coverage, text-query, and combined filters.
- Make the genre picker searchable and suitable for large catalogs while preserving multi-select behavior.
- Add discoverable and accessible entry into multi-select, including long-click labels and a non-gesture alternative.
- Preserve the current list/grid densities, independent Focus/Your games sorting, wishlist behavior, and HLTB batch capabilities.
- Move Library-facing copy into Android string resources.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `app-ui`: Refine Library discovery hierarchy, filtering, zero-result recovery, genre selection, selection accessibility, and localized copy.

## Impact

- Affects `ui/library/LibraryScreen.kt`, Library filter/presentation models and ViewModel state, string resources, and Library unit/instrumentation tests.
- Does not change library ownership, Focus membership, sort persistence, HLTB matching, or wishlist data contracts.
- The visual-regression proposal owns screenshot infrastructure and goldens; this change owns Library production behavior and semantic tests only.
