# Library visit verification

Verified on 2026-10-03 in `fix/navigation-context-flows`.

## Local checks

- `:app:testDebugUnitTest --tests '*ui.library.*' --tests '*GameListDensityTest' --tests '*SettingsRepositoryTest'`: 71 tests passed. One initial run encountered a Windows `AccessDeniedException` moving a Robolectric DataStore temporary file; the same suite passed on retry without source changes.
- `:app:assembleDebug` and `:app:assembleDebugAndroidTest`: passed.
- `:app:lintDebug`: passed with 51 warnings and the existing lint baseline; no new lint errors.
- `openspec validate 02-retain-library-visit-context --strict`: passed.

Clock checks cover 299,999 ms versus 300,000 ms, foreground children without an absence interval, repeated timely returns, earliest tab departure, background expiry, retained reattachment, cold defaults, and rejection of scroll captures from an expired generation. Scroll checks cover identity restoration after reordering, missing-game fallback, section changes, and incompatible pixel offsets when density changes. Existing filter, sorting, density, and settings checks also pass.

## Physical device checks

Device: Xiaomi 2406APNFAG (`degas`), Android 16 / API 36.

`LibraryVisitContextTest`: five instrumented tests passed using the production Library presentation, bottom navigation, navigation helper, and visit effects, with fixture games and an activity-retained test ViewModel. Detail and review destinations are navigation fixtures; these tests do not inspect their repository data or reviewer internals.

- Detail and review → system Back keep game 040 visible in list, grid, and compact grid after 600,000 ms of foreground time.
- Tab departure via Home and a detail opened from Home retains all four filters and the game anchor at 299,999 ms; at 300,000 ms it clears the filters and shows the search controls at the top.
- Real activity stop/resume events trigger `ProcessLifecycleOwner` background/foreground handling from Library, detail, and review in all three densities, with the same boundary results.
- Activity recreation retains the holder, query, filters, and anchor. Recreation on Home and subsequent backgrounding preserve an earlier tab-departure timestamp.
- After each retained return, the visible search input reads `Game` and the selected Puzzle, Not covered, and Family Shared chips are displayed. Explicit Clear all filters and selection disposal are verified.

The boundary checks inject elapsed time into the production effects. They exercise real navigation, system Back, recreation, and process lifecycle delivery on the phone; they do not wait five minutes of wall-clock time. The process stop delay is allowed to elapse before advancing the test clock.

The actual debug app also cold-launched successfully through `MainActivity`; the fresh debug installation showed account onboarding. No account credentials or personal library were required for the fixture checks.

## Evidence

Ignored local evidence lives under `build/`: `library-visit-validation.log`, `library-visit-final-build.log`, `library-visit-device-build.log`, `library-visit-preferences-retry.log`, and `library-visit-instrumentation.log`. The device runner reports `OK (5 tests)`. The tab-return test was rerun after making screenshot capture wait for navigation to settle (`library-visit-capture-run.log`: `OK (1 test)`). The latest twelve captures are in `library-visit-captures/library-visits/`; inspection confirms game 040 remains visible after the short compact-grid departure, while the exact-threshold return shows the empty search and first games.

Visual coverage is **DEGRADED**: screenshots cover one physical phone and its dark theme, across all three Library densities. They support the visit behavior checks, not a route-wide visual assessment.
