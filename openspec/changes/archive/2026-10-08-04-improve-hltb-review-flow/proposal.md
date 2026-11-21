## Why

Issue #163 reports that HLTB matching can return to a title the player meant to skip, while reviewer entry points are missing from both Library grids and Gameplay settings. The current reviewer follows app IDs through queue changes but has no session deferral concept.

## What Changes

- Add an explicit Skip for this review session action and keep deferred games unresolved in storage.
- Move automatic forward selection to the next unprocessed game after matching or queue changes, then show completion instead of wrapping to deferred titles.
- Provide Review skipped or reset access within the session, and retain session selection through configuration recreation and temporary detail return.
- Offer accessible per-game HLTB lookup/review actions in both Library grid densities and a Match Center shortcut in Settings → Gameplay.
- Preserve scoped single-game reviewer behavior and check the reported jump against current identity tracking before attributing its cause.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `app-ui`: Add session deferral, forward reviewer progression, and the missing reviewer entry points.

## Impact

- Covers issue #163 from feedback index #174. Its Library return path coordinates with `02-retain-library-visit-context`; shell bar visibility belongs to issue #157.
- Expected implementation areas: `HltbReviewViewModel.kt`, `HltbReviewScreen.kt`, `LibraryScreen.kt`, Settings Gameplay presentation, and app navigation wiring.
- HLTB match storage and data-source contracts remain unchanged.
