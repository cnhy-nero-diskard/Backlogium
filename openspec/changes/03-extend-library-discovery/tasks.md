## 1. Wishlist Search

- [x] 1.1 Derive cached wishlist title matches for a Library query while preserving the owned/shared search pipeline; verify name matching, app-ID deduplication, and owned-only filter behavior with focused checks.
- [x] 1.2 Render labeled wishlist search results in list and both grids with store actions and no owned-game controls; verify an owned/shared overlap opens its normal detail and a wishlist-only result opens the appropriate store action.
- [x] 1.3 Carry wishlist availability and stale-price state into search results; verify failed reads with and without cached entries never appear as confirmed no-match results.

## 2. Added Recently Sort

- [x] 2.1 Add an `ADDED_RECENTLY` key and pass recorded `firstSeenAt` through the Library sort projection; verify old persisted sort values and defaults still load unchanged.
- [x] 2.2 Sort dated games newest or oldest as selected, keep unknown times last, and use stable title/app-ID ties; verify baseline, legacy null, equal-time batch, and reversed-direction cases without fabricated dates.
- [x] 2.3 Expose Added recently independently for both Library sections and explain Backlogium observation versus Steam purchase date; verify search relevance still leads when a query is active and both sections retain chosen preferences.

## 3. Hide Confirmation

- [x] 3.1 Condense the Hide game dialog while retaining restore guidance, Hide/Cancel choices, and material XP, level, or Focus effects; verify representative no-change and consequential-change presentations.

## 4. Integrated Review

- [ ] 4.1 Exercise wishlist search, Added recently, and Hide confirmation in list and both grids on a device or emulator; verify they coexist with the five-minute Library visit behavior from `02-retain-library-visit-context` when both changes are applied.
