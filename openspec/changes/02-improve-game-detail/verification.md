# Verification

## Preference checkpoint

- Baseline: `a65236ec` on `feat/streamline-collections`; change 1 is complete and supplies the preference repository and heart. Completed Library changes 01-04 remain the baseline. Dependency order is change 1 before this change; change 3 is independent after change 1 and stays untouched.
- Added nullable artwork token in Room 43 -> 44. Favorite and artwork writes preserve each other; account reset clears preferences; shared-to-owned conversion retains them.
- `:app:testDebugUnitTest` filtered to GamePreferenceRepositoryTest, LibraryVisitStateTest, LibraryScrollContextTest, LibraryVisitRouteTest passed; `:app:assembleDebugAndroidTest` passed.
- `:app:connectedDebugAndroidTest` filtered to MigrationTest and LibraryDiscoveryTest passed 34 tests on emulator-5554 (API 35), including populated v13-to-current retention and v43-to-v44 favorite retention.
- Logs: `%TEMP%/backlogium-02-preferences.log`, `%TEMP%/backlogium-02-migration.log`.

## Achievement lens checkpoint

- Focused AchievementSortTest, AchievementRowMappingTest, DetailVisitStateTest and RarityStanding tests passed; `:app:assembleDebug` passed. Log: `%TEMP%/backlogium-02-filters.log`.
- Saved visit tokens retain independent sort/filter through recreation, including retained collection ViewModels, and reset both for a new presentation. Full summary totals and completion do not depend on filtered rows.
- Mixed date/current-percentage sorting uses display name and API identity ties. Snapshot-only rows sort with unknown current percentage while retaining earned tier/XP and its labeled observation. Filtered empty states distinguish no locked/unlocked rows from missing cached data.

## Explicit refresh checkpoint

- 39 focused tests passed across DetailAchievementRefreshTest, AchievementRefreshControllerTest, AchievementRepositoryTest, AccountChangeRecoveryTest and GameDetailRefreshTest. `:app:assembleDebug` passed. Log: `%TEMP%/backlogium-02-refresh.log`.
- Real Room gated-response tests cover missing credentials, pending reset before/during fetch, A-to-B-to-A reset, hide/removal, same-account key rotation, unusable/private and transport outcomes, shared in-flight requests and serialized commits.
- Canonical committed-content comparison detects equal-count schema/rate changes and ignores timestamps. Recompute uses the existing owner; first-unlock snapshots/XP are retained and no sessions are created. Retry also finishes a derived write after a raw-commit failure.
- Presentation-controller tests cover repeated tap suppression, retry, leaving, switching game/visit and a response that ignores cancellation. Player-count refresh remains independent.
- The first coalescing test run had a test-gate scheduling race; it now waits for the second caller's metadata read before releasing the shared fetch. The corrected run passed.

## Artwork backup checkpoint

- 175 focused artwork, Steam asset, preference, library join and backup tests passed; `:app:assembleDebug` passed. Log: `%TEMP%/backlogium-02-artwork.log`.
- Export/snapshot/real-Room restore tests include a selected variant, an explicit reset replacing a local override, absent-game rows, idempotence, and favorite-only/omitted records retaining artwork. Cross-account identity and session-provenance tests remain passing.
- Streaming preflight rejects unsupported tokens, arbitrary URLs, malformed/null/empty artwork objects, missing/duplicate variant fields and duplicate artwork objects before writes. A late FK failure rolls back both favorite and artwork writes.

## Cover resolution and detail refinement

- Every supported token leads a deduplicated five-candidate chain. Wide detail/list frames and portrait grid frames retain their normal defaults after Reset. Real Room library projections carry the token to both cover URLs while icon URLs remain unchanged. Library and Collection list/grid consumers use these resolved URLs and normal fallback chains; wishlist rendering is unchanged.
- Detail samples the decoded banner directly, including a successful fallback, without a separate image request. Exhaustion retains a 120dp themed frame. The overlay keeps its wash local; a full destination reports the resolved color to the shell.
- Choose/Manage/Reset use five supported Steam previews and the existing image loader. Unavailable previews are disabled; offline requests disable network reads and allow cached previews. No asset manifest, bulk-download job, image provider or Store-discovery policy changed.
- Labeled groups distinguish Steam lifetime playtime, tracked/imported history, shared tracked/manual estimates, cached metadata, completion estimates, and achievement progress. Heart, Steam link, hide confirmation and shared-game controls remain reachable. Locked achievement descriptions retain readable contrast; current percentages and earned snapshot values are separately labeled.

## Visual baseline and scope audit

- Recorded and visually reviewed five new API-35 Roborazzi baselines: owned dark/narrow and light/standard, missing-data light/narrow, shared overlay dark/standard, and achievements dark/standard. Missing-data capture waits for the actual exhausted-chain chooser. Existing main-screen/alternative/spike captures were re-recorded without pixel changes.
- The CI screenshot command includes the two detail test classes, and the baseline-currency gate now includes the detail package. `python -m unittest discover -s scripts -p 'test_*.py'` passed 32 tests.
- Change 1 remains the owner of collection entry shortcuts, membership semantics, derived collection definitions and the heart contract. This change extends its preference record additively and reuses its heart/repository.
- Change 3's deadline archival, picker exclusions and collection lifecycle are untouched. The existing achievement funnel is reused; this change adds no active achievement monitor, notification policy, or missing-game coverage investigation. The active presence-attribution change's scheduling, sessions, observation placement and XP rules are untouched.
- `gamification/src`, worker scheduling, SessionRepository, wishlist rendering and main OpenSpec specs have no diff from `a65236ec`. No archive, main-spec synchronization, release or deployment was performed.

## JVM fixture lifetime

- The first full-suite runs exposed Windows `AccessDeniedException` when the process-wide Settings DataStore delegate kept writing into a previous Robolectric test's temporary context. A distinct sandbox annotation did not fix the lifetime problem and was removed.
- SettingsDataStore now has an internal store constructor; its public Hilt/Context constructor still uses the same production settings delegate. All nine JVM fixture classes that construct it use a per-test Preferences DataStore with an owned file and IO scope, cancelled and joined before directory cleanup.
- The corrected full suite passed 1,858 app JVM tests with no failures/errors/skips; gamification's 53 tests also passed. Logs: `%TEMP%/backlogium-02-full-isolated.log` and the final picker-budget rebuild `%TEMP%/backlogium-02-final-validation.log`; XML totals: `%TEMP%/backlogium-02-unit-counts.json`.

## Final automated checks

- `./gradlew.bat :app:testDebugUnitTest :gamification:test :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --no-daemon` completed successfully after the DataStore fixture fix. App: 1,858 tests; gamification: 53 tests; no failures, errors or skips. Lint reported 54 warnings, with 2 existing errors, 34 warnings and 3 hints filtered by the repository baseline; no new lint errors.
- `:app:verifyRoborazziDebug` filtered to RoborazziSpikeTest, MainScreenScreenshotNarrowTest, MainScreenScreenshotStandardTest, GameDetailScreenshotNarrowTest and GameDetailScreenshotStandardTest passed all 37 screenshot checks. Existing baseline PNGs remain byte-identical. Logs: `%TEMP%/backlogium-02-visual-verify-final.log` and final cached verification `%TEMP%/backlogium-02-goldens-final.log`.
- Final Android-test build and lint passed again after the system-font fixture update (`%TEMP%/backlogium-02-system-font-build.log`).
- `python -m unittest discover -s scripts -p 'test_*.py'` passed 32 tests. `openspec.cmd validate 02-improve-game-detail --strict` and `git diff --check` passed.

## Emulator behavior and evidence

- API-35 `Backlogium_OpenSpec_02`, serial `emulator-5556`, passed all seven GameDetailBehaviorTest methods (`OK (7 tests)`), including production Library/Home/collection member controls entering the common production detail body, Back through the focused window, retained filter/sort after Activity recreation, and reset lenses on a new visit. The collection presentation uses a real ModalBottomSheet; its host navigation state is explicit SavedStateHandle fixture state. Logs: `%TEMP%/backlogium-02-detail-green.log` and the final system-font run `%TEMP%/backlogium-02-detail-system-font.log` (all seven passed with system fontScale 1.6; original 1.0 restored).
- The same isolated emulator passed MigrationTest (30), LibraryDiscoveryTest (4), LibraryGameSelectionSemanticsTest (4) and LibraryVisitContextTest (6): 44 retained-behavior tests. The initial combined 51-test run had one failing new navigation fixture; its 44 existing checks passed. The corrected seven-test batch above passed separately. Log: `%TEMP%/backlogium-02-device-final.log`.
- Detail coverage includes all six refresh outcomes, repeated-tap pending suppression/retry, last-good rows and independent player refresh, filtered completion totals, actual decoded fallback bitmap/accent behavior, offline network policy and controlled cached previews, real Room-backed selection/reset and favorite preservation, shared/manual provenance and missing-data labels. The final batch sets system fontScale 1.6 as well as the controlled Compose density, covering native dialog windows; cleanup restores the original setting.
- Pulled and visually reviewed representative captures from `app/build/outputs/game-detail-device-final/game-detail/`: Library/Home/collection visits and unlocked rows, owned cover/favorite selection, offline chooser, completed filtered state, shared and missing metadata, and all refresh outcomes (17 captures). The five committed Roborazzi baselines cover both themes and narrow/standard sizes; device captures remain ignored evidence.
- Earlier SwiftShader QEMU native crashes were confirmed by Windows crash events; a separate AVD with host GPU and Vulkan disabled completed the tests. New navigation-fixture failures were corrected by selecting the actual Library card, scrolling virtualized/placed rows, using the new composition's Activity, saving fixture open state, and dispatching Back to the focused dialog/window. These were test-host fixes, not product failures.
- Evidence is **DEGRADED** to one API-35 emulator and deterministic Steam/Coil fixtures. These tests exercise production components with a controlled host, not the authenticated Hilt app root. No physical-device, live Steam-account/API/CDN, or production-root visual acceptance is claimed. The local API key was not needed or read.
- The whole-library add-game picker retains its original single-header default budget; a saved cover override may fall back to that original header. Main Library/Collection cover frames keep the full shared selected-first chains; icons and wishlist behavior are unchanged.

## Shipped checkpoints

- `419dc533` feat(preferences): retain supported steam cover choices
- `a3fb5503` feat(detail): separate achievement filters and mixed rarity sorting
- `d0b1035a` feat(detail): refresh achievements with account-safe commits
- `16696291` feat(backup): preserve artwork selection and explicit resets
- `56b5dbf9` test(settings): close isolated datastore writers between tests
- `7a7e5ee8` feat(detail): add steam cover controls and labeled game information
- Final validation/evidence checkpoint follows these commits on `feat/streamline-collections`, pushing to its configured `origin/feat/streamline-collections` upstream.

All 17 tasks are complete. The pre-existing untracked `openspec/changes/03-archive-deadline-collections/` was left untouched. This change remains active for a separate archive invocation; no archive was performed.
