## Why

Game detail needs clearer visual groups, independent achievement-state filtering, a named achievement refresh, and a replacement for placeholder artwork. Issue #160 should refine the existing detail surface while preserving its source disclosures and the Favorites heart supplied by the first umbrella change.

## What Changes

- Group identity, metadata, estimates, playtime, and achievements into readable, screenshot-friendly sections with truthful units and provenance.
- Add All, Unlocked, and Locked filters independent of date/rarity sorting; All uses one mixed list with deterministic unknown-key placement.
- Add Refresh achievements for the selected app ID through the existing coordinated repository fetch/merge path; publish pending, updated/no-change, unusable-data, and failure outcomes while retaining last-good content.
- Keep pull-to-refresh explicitly scoped to player count and coordinate achievement requests with existing sync/monitor work rather than creating another tracker.
- Offer available Steam artwork variants for placeholder covers, persist the chosen variant, apply it consistently to tracked-game cover surfaces, and provide Reset with the existing fallback chain.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `app-ui`: Detail hierarchy, independent achievement filters/mixed sorting, and artwork chooser presentation.
- `steam-achievements`: Explicit, account-safe per-game refresh and outcome semantics.
- `offline-steam-assets`: Selected cover variants resolve through existing offline storage and fallback behavior.
- `backup-restore`: Round-trip app-owned artwork selection and explicit reset without legacy imports erasing preferences.

## Impact

- Second `streamline-collections` change; covers #160 feedback F03, F04, F05, and F36 after `01-streamline-collection-access` supplies the heart and preference storage contract.
- Expected areas: detail ViewModel/screen and entry-point parity, `AchievementRepository.refreshOne`, credential/account guards, recompute integration, shared artwork resolver/Steam assets, backup mapping, and visual baselines.
- Coordinate the existing achievement funnel with #159; this proposal does not implement its active monitor, missing-game coverage investigation, or notification policy. Preserve #167's completed Library visit/discovery behavior and the adjacent legibility planning in PR #178.
- No replacement playtime tracker, new image provider, arbitrary uploaded artwork, wishlist-only collection controls, or deadline archival.
