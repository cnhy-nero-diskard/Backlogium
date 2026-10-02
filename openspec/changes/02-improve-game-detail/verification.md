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
