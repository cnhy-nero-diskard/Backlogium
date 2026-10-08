# Library discovery verification

Verified on 2026-10-03 in `fix/navigation-context-flows`.

## Local checks

- `:app:testDebugUnitTest --tests '*ui.library.*' --tests '*GameRepositoryGenreJoinTest' --tests '*BackupFileRoundTripTest' --tests '*GameVisibilityUseCaseTest'`: 88 tests passed.
- `:app:testDebugUnitTest --tests '*LibrarySortPreferencesTest'`: one test passed in a separate invocation, exercising the production DataStore repository and independent section keys/directions.
- `:app:testDebugUnitTest --tests '*WishlistEntryUiTest' --tests '*LibraryWishlistSearchTest'`: 12 tests passed after the price presentation refinement.
- `:app:assembleDebug`, `:app:assembleDebugAndroidTest`, and `:app:lintDebug`: passed. Lint reports zero unfiltered errors and 51 warnings with the existing baseline.
- `openspec validate 03-extend-library-discovery --strict`: passed.

Checks cover trimmed/case-insensitive cached title matching, relevance, app-ID deduplication in favor of owned/shared games, owned-only filters, unavailable reads with empty or nonmatching caches, retained prices, and wishlist scroll anchors in every density. Arrival checks cover Room-to-repository and both UI projections, legacy/baseline nulls, equal-time batch ordering, normalized title/app-ID ties, both directions with unknowns last, search relevance, old persisted names/defaults, and independent persisted section preferences. Backup serialization and the existing hide recomputation checks also pass.

An initial combined run including `SettingsRepositoryTest`, and a subsequent full settings-class retry, encountered Windows `AccessDeniedException` while DataStore moved a temporary preferences file. The process-wide delegate retained an earlier Robolectric test's temporary directory. The new sort preference check was separated into its own class and invocation; it passes. The existing settings class and production DataStore implementation were left unchanged. The broad settings suite is not claimed as passing in this run.

## Physical device checks

Device: Xiaomi 2406APNFAG (`degas`), Android 16 / API 36.

- `LibraryDiscoveryTest`: four tests passed. List, grid, and compact grid show a labeled result section even when the ordinary wishlist is collapsed. Wishlist-only cards send the expected Steam store URL without selection or owned-game controls; overlapping owned cards retain their detail action. Both read-failure states retain matching cache entries and dated prices, and empty/nonmatching failed caches show wishlist unavailability. Each owned-only filter suppresses wishlist results. Both sections expose the Added recently explanation and independent sort/direction controls.
- `LibraryHideConfirmationTest`: three tests passed. Library cards in every density lead through a fixture detail action into the production confirmation dialog. No-change cases retain restore guidance and Cancel without redundant progress lines. Consequential cases show XP, an explicit level drop, and Focus loss before Hide. An XP-only change omits an unchanged level line.
- `LibraryVisitContextTest`: six tests passed, including the five existing visit checks. The new integration check exercises wishlist search and Added recently in all densities: a foreground detail return after 600,000 ms retains context, a tab return at 299,999 ms retains the query and wanted-game anchor, and a return at 300,000 ms clears search and returns to the top with the ordinary collapsed Wishlist section. Added recently and density remain selected.

The visit checks inject elapsed time into production visit effects while exercising real navigation, system Back, activity recreation, and process lifecycle delivery on the phone. They do not wait five minutes of wall-clock time. Cache entries, games, detail/review destinations, hide effects, and store callbacks are fixtures; these checks do not claim live Steam reads, external browser behavior, or real account mutations.

Intermediate device attempts failed while the phone was locked. A subsequent attempt ended with a native `libhwui` RenderThread SIGSEGV. Fresh runs completed the suites above without a rendering workaround. The phone's USB stay-awake setting was temporarily enabled for verification and restored afterward.

## Evidence

Ignored evidence is under `build/`: `library-discovery-wishlist-unit.log`, `library-discovery-added-preferences.log`, `library-discovery-final-checks.log`, `library-discovery-integration-build.log`, `library-discovery-price-date-checks.log`, `library-discovery-final-apks.log`, `library-discovery-final-device.log`, `library-discovery-hide-device.log`, and `library-discovery-visits-device.log`.

Screenshots are in `build/library-discovery-final-captures/` and `build/library-discovery-visit-captures/`. Inspection covers wishlist results in all three densities, the Added recently explanation, representative concise/material Hide dialogs, and retained/expired compact-grid visits. Price inspection prompted an explicit Last seen label and wrapping observation dates; final screenshots confirm both grids retain the full year.

Visual coverage is **DEGRADED**: one physical phone and default font scale, using fixture hosts. The standalone content/dialog hosts omit the production shell background and insets; the visit host includes the production bottom navigation and Scaffold. This evidence supports the requested behavior, not a route-wide visual assessment.
