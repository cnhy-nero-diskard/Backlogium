## 1. Baseline and Preference Extension

- [x] 1.1 Confirm change 1's preference repository/heart and the completed Library baseline are present; verify the apply dependency map and existing context/discovery checks before changing shared surfaces.
- [x] 1.2 Add the nullable supported artwork-variant preference and additive migration from the actual current schema; verify favorite values, account reset, and shared-to-owned retention remain intact.

## 2. Achievement Filters and Mixed Sorting

- [x] 2.1 Add visit-local All/Unlocked/Locked filter state independent of sort; verify recreation preserves the current visit while a new visit resets All/date and summary counts remain unfiltered.
- [x] 2.2 Replace unlocked-first comparators with date/current-rarity mixed ordering and stable ties; verify locked/unlocked interleaving, null dates/percentages last, and snapshot-only unlocked rows.
- [x] 2.3 Separate current percentage from earned snapshot/tier presentation and add filtered empty states; verify labels explain sorting without changing frozen tier/XP and distinguish no locked rows from unavailable data.

## 3. Safe Explicit Achievement Refresh

- [x] 3.1 Add the domain per-game refresh action over the common coordinated fetch/merge path; verify repeated taps and overlap with sync coalesce/serialize without a whole-library or playtime request.
- [x] 3.2 Fence the commit by account/reset generation and current game eligibility; verify pending account change, A-to-B-to-A transition, missing credentials, hide/removal during fetch, and same-account key rotation.
- [x] 3.3 Derive updated/no-change from canonical committed content and integrate owned recompute; verify equal-count content changes, freshness-only changes, first-unlock rarity preservation, and no duplicated progression.
- [x] 3.4 Wire separate pending/success/unusable/failure outcomes with retry and last-good content; verify changing games or leaving detail cannot publish another game's stale action state and player-count pull remains independent.

## 4. Artwork Selection and Backup

- [x] 4.1 Build shared selected-first cover resolution over supported Steam variants; verify default/selected/dead-asset chains, deduplication, stable frame geometry, and actual-image accent sampling in full detail and overlay.
- [x] 4.2 Add placeholder chooser and retained Manage/Reset controls with bounded cached/online previews; verify unavailable candidates, offline behavior, successful selection/reset, and no arbitrary URL or bulk job.
- [x] 4.3 Apply preference resolution to tracked cover surfaces in Library/Collections while preserving icons and wishlist behavior; verify list and both grids use the same selected token with their appropriate crop/fallback.
- [x] 4.4 Extend export/validation/import with presence-aware artwork selection/reset; verify selected and reset round-trips, legacy favorite-only imports, invalid tokens, transaction rollback, and unchanged favorite/session-provenance fields.

## 5. Detail Refinement and Integrated Verification

- [x] 5.1 Refine labeled identity, metadata, estimates, playtime, and achievements after the controls work; verify shared/imported provenance, Steam link, missing-data behavior, Favorites heart, and readable screenshot framing.
- [x] 5.2 Exercise Library/Home/collection detail entry points, refresh outcomes, filters, cover changes, Back, and recreation on a device/emulator; verify large-font controls and record representative owned/shared/missing-data evidence with affected baselines.
- [x] 5.3 Run focused comparator/filter, refresh race/recompute, asset, backup, and migration tests plus debug build/relevant lint; verify recorded results also cover unchanged Library visit/discovery and earned-rarity behavior.
- [x] 5.4 Run strict OpenSpec validation and diff checks, audit requirement ownership against changes 1/3 and active achievement planning, and record exact evidence without marking unperformed device or visual checks complete.
