# Verification: streamline collection access

Verified on 2026-10-03 in the `feat/streamline-collections` worktree.

## Functional evidence

- Full app JVM suite: **1,834 tests, zero failures/errors**, including preference/membership repositories, stale editor saves, date/zone boundaries, old derived rules, Home presentation, and **96 backup tests**.
- Favorite action controller: **3 tests passed**, covering pending duplicate suppression, committed-state feedback, failure/retry, and an account change while a write is in flight.
- Gamification suite: **53 tests, zero failures/errors**; unchanged inputs were reported up to date by Gradle.
- API 35 `Medium_Phone_API_35` emulator (`emulator-5554`): **18 UI tests passed** across `CollectionShortcutBehaviorTest` (4), `LibraryVisitContextTest` (6), `LibraryGameSelectionSemanticsTest` (4), and `LibraryDiscoveryTest` (4).
- The visit test now pushes `collection/0` through a real Navigation host in every density and checks query, filters, density, and scroll on return, including a foreground interval longer than five minutes.
- Repeated the **4 collection shortcut tests successfully with emulator Wi-Fi and mobile data disabled**. Production controls perform real Room mutations against fixture data: heart add/clear, existing membership disabled in every density, wishlist exclusion, custom removal and retained detail navigation, and read-only derived member rendering.
- Full migration suite: **29 tests passed**, including populated v13-to-current retention and v42-to-v43 preference defaults. The first full run caught the missing 42-to-43 entry in the test harness's history-chain list; that list was corrected before the successful rerun.

## Device and visual evidence

**DEGRADED: one emulator configuration.** Android controls were exercised with Room fixture data; this is not physical-device or authenticated live-Steam acceptance. The common `GameDetailScreen` implementation supplies the heart to full-screen and collection-overlay entry points; those navigation origins were also inspected in source.

Seven actual Android captures were pulled and retained under the ignored directory `app/build/verification/offline-collection-shortcuts/`:

- `favorite-large-font.png`: committed favorite feedback and read-only Favorites card at font scale 1.6; text and controls remain readable.
- `picker-LIST.png`, `picker-GRID.png`, `picker-COMPACT_GRID.png`: labeled target, already-member state, creation and dismissal controls, with retained Library query.
- `custom-LIST.png`, `custom-GRID.png`, `custom-COMPACT_GRID.png`: detail tap target plus a separate removal control; compact removal text wraps without clipping.

The fixtures mount production controls directly; their status-bar/inset framing is not a full app-shell screenshot. Offline artwork falls back to the existing placeholder. No physical phone was installed or altered.

Host Roborazzi baselines were recorded and visually reviewed at narrow (320×720) and standard (412×915) viewports. New derived-card fixtures include owned/shared artwork examples and Favorites/Played recently rules/counts. Updated PNGs cover four Home cells, four Library cells, the now-playing/no-results/selection alternatives, and two new derived-card cells. The shared card uses the same committed membership count and representative member artwork in Home and Collections. Existing hidden/empty policies and Completed disclosures are covered by the unit suite.

## Commands and reports

```powershell
.\gradlew.bat :app:testDebugUnitTest :gamification:test :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --no-daemon

$env:ANDROID_SERIAL = 'emulator-5554'
.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.example.backlogium.ui.library.CollectionShortcutBehaviorTest,com.example.backlogium.ui.library.LibraryVisitContextTest,com.example.backlogium.ui.library.LibraryGameSelectionSemanticsTest,com.example.backlogium.ui.library.LibraryDiscoveryTest' --no-daemon

# Direct instrument runs used the installed debug/test APKs and the Android SDK adb.exe.
adb -s emulator-5554 shell svc wifi disable
adb -s emulator-5554 shell svc data disable
adb -s emulator-5554 shell am instrument -w -e class com.example.backlogium.ui.library.CollectionShortcutBehaviorTest,com.example.backlogium.data.local.MigrationTest com.example.backlogium.debug.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5554 shell am instrument -w -e class com.example.backlogium.data.local.MigrationTest com.example.backlogium.debug.test/androidx.test.runner.AndroidJUnitRunner

.\gradlew.bat :app:recordRoborazziDebug --tests com.example.backlogium.ui.screenshot.MainScreenScreenshotNarrowTest --tests com.example.backlogium.ui.screenshot.MainScreenScreenshotStandardTest --no-daemon
openspec.cmd validate 01-streamline-collection-access --strict
git diff --check
```

The connected run's original selector also contained an incorrect migration package: the four UI classes passed, while that selector failed class loading. The corrected direct migration run above completed 29/29. The first offline combined run completed the four UI tests and 28 migration tests, then exposed the history-chain test harness omission described above.

Logs are in `%TEMP%/backlogium-01-{backup-final,instrument-final,offline-migrations,migrations-final,final-checks,final-checks2,lint-final}.log`. JVM XML reports are under `app/build/test-results/testDebugUnitTest/` and `gamification/build/test-results/test/`; the connected report is under `app/build/outputs/androidTest-results/connected/debug/`.

## Final checks

- `:app:assembleDebug` and `:app:assembleDebugAndroidTest` passed; the APKs were installed only on the emulator.
- `:app:lintDebug` passed with **54 reported warnings**; the existing baseline filtered 2 errors, 34 warnings, and 3 hints. The ignored worktree SDK pointer initially triggered `PropertyEscape`; it was corrected to `sdk.dir=C\:/Users/cnhyn/AppData/Local/Android/Sdk`, and stale analysis/report outputs were refreshed with the command below. No lint baseline was changed.
- `:app:verifyRoborazziDebug` passed **32 tests** (spike plus narrow/standard fixture classes) in 25 seconds, without rewriting tracked PNGs. This filtered run replaces the app JVM XML reports; the full-suite 1,834 count above was read from the preceding unfiltered run.
- `python scripts/check_visual_baseline.py check --changed-file <temporary changed-path manifest>` passed against changes from `004a508f`.
- Strict validation of this OpenSpec change and Git whitespace checks passed.

```powershell
.\gradlew.bat :app:lintAnalyzeDebug --rerun :app:lintReportDebug --rerun :app:lintDebug :gamification:test --no-daemon
.\gradlew.bat :app:verifyRoborazziDebug --tests com.example.backlogium.ui.screenshot.RoborazziSpikeTest --tests com.example.backlogium.ui.screenshot.MainScreenScreenshotNarrowTest --tests com.example.backlogium.ui.screenshot.MainScreenScreenshotStandardTest --no-daemon
```

The final lint and golden logs are `%TEMP%/backlogium-01-lint-final.log` and `%TEMP%/backlogium-01-goldens-verify.log`. Emulator network settings were restored after offline acceptance. Gradle runs were kept sequential after an earlier overlap caused a transient generated-code race. That race was resolved by a successful sequential build.

All 17 change tasks are complete. Separate planning changes `02-improve-game-detail` and `03-archive-deadline-collections` remain untouched. Spec sync and archive are outside this autoship invocation.
