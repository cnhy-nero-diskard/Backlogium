## Why

Issue #167 includes Library discovery gaps beyond visit-state retention: wishlist games disappear under a query, recent activity is not a date-added sort, and the Hide game confirmation is unnecessarily long. These make common Library tasks harder to find or interpret.

## What Changes

- Include cached wishlist title matches in Library search, clearly labeled and routed to appropriate store/detail actions without owned-game controls.
- Keep wishlist read failures distinct from an empty search result.
- Add an independent Added recently sort to Library sections using recorded first-observed arrival times, with stable ordering for ties and unknown dates.
- Explain that added time is Backlogium's observation, not a Steam purchase date.
- Shorten the Hide game confirmation to its consequence and restore path while retaining confirm and cancel actions.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `app-ui`: Extend Library wishlist search, date-added ordering, and Hide game confirmation behavior.

## Impact

- Covers the remaining issue #167 work alongside `02-retain-library-visit-context`, which defines search and filter lifetime. It does not duplicate the Library legibility scope preserved in draft PR #178.
- Expected implementation areas: `LibraryScreen.kt`, `LibraryViewModel.kt`, `WishlistViewModel.kt`, `LibrarySortKey.kt`, and `VisibilityChangeDialog.kt`.
- Uses existing cached wishlist and game arrival data; no new external data source is planned.
