## Why

Favorites and everyday collection membership are missing convenient entry points, while derived collections need clearer presentation. Issue #168 should provide one consistent local collection experience across Library, game detail, Collections, and Home.

## What Changes

- Persist an account-bound, app-owned favorite preference independently of Focus; expose its heart in game detail and derive a read-only Favorites collection.
- Add a read-only Played recently collection using supported Steam/session evidence within fourteen local calendar days, including shared games where evidence exists.
- Provide a quick Add to collection picker from Library list and both grids, showing existing membership and immediate success/error feedback.
- Provide direct member removal in custom collection overviews and improve derived cards' artwork, counts, labels, and membership explanations.
- Preserve preferences through sync, hidden-game filtering, backup/restore, and account transitions without storing derived membership.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `custom-collections`: Persistent game favorites and direct custom-membership operations.
- `smart-collections`: Favorites, Played recently, and consistent derived-card presentation.
- `app-ui`: Detail favorite heart and Library/custom-collection shortcuts across densities.
- `backup-restore`: Explicit favorite preferences with compatible legacy-import behavior.

## Impact

- First change in the `streamline-collections` umbrella: issue #168 feedback F23, F27, F28, F34, and F37; the heart integration requested by #160 and Library integration alongside #167.
- Baseline is branch `feat/streamline-collections` at `004a508f`. Issue #167 is already represented by completed `02-retain-library-visit-context` and `03-extend-library-discovery`; preserve their behavior and do not duplicate their deltas. They remain active pending separate sync/archive.
- Expected areas: Room game preferences, collection repository/domain contracts, shared derived feed, Library/detail/collection/Home presentation, account reset, backup mapping/validation/merge, and relevant screenshot baselines.
- Reuse existing membership writes; no new Steam polling, Focus behavior, wishlist tracking, collection roulette, or deadline archival. `02-improve-game-detail` builds on the preference contract; `03-archive-deadline-collections` supplies archival separately.
