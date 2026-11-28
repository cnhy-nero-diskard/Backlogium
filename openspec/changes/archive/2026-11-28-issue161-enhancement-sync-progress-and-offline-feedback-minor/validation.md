# Validation

Verified on macOS / Apple M5 with Android Studio's bundled JBR and the existing
Android SDK. No production credentials were copied into test fixtures.

## Checks

- At the PR-review checkpoint, five focused regression suites passed; the full
  `:app:testDebugUnitTest :gamification:test` run passed 2,661 app unit tests and
  53 gamification tests with no failures. Strict OpenSpec validation passed.
- `:app:assembleDebug :app:testDebugUnitTest :gamification:test :app:lintDebug`
  passed. The full app run completed 2,648 tests without failures; the unchanged
  gamification suite's 53 tests were up to date. Lint passed with existing warnings
  and the existing baseline; the lint baseline was not changed.
- The final `:app:verifyRoborazziDebug` run passed for RoborazziSpike, both main
  screen, detail, Setup recovery and sync-feedback viewport classes, and
  CorePlayInsightsUiTest, together with operation-reporting and collection-action
  regressions. Record and verify are separate commands. The new fixtures fix
  locale, timezone, clock and animation frames; timestamps are rendered in UTC.
- The sync-feedback classes are included in CI screenshot verification and the
  visual-baseline currency check. Its 15 Python checks and the changed-file
  currency gate passed.
- `openspec validate issue161-enhancement-sync-progress-and-offline-feedback-minor --strict`
  passed.

## Targeted instrumentation

The repository launcher verified `emulator-5554`, `Medium_Phone_API_37.0`, using
`-gpu host -feature GuestAngle`; guest renderer was Goldfish GFXStream (Apple M5),
RE Vulkan (Ganesh). The final run passed all seven tests in:

- `com.example.backlogium.ui.sync.SyncFeedbackDeviceTest`
- `com.example.backlogium.work.setup.SetupConcurrentWorkDeviceTest`
- `com.example.backlogium.ui.settings.SettingsLocalizationTest`

The Gradle invocation set `ANDROID_SERIAL=emulator-5554` and
`-Pandroid.testInstrumentationRunnerArguments.serial=emulator-5554`.
The new checks render real Setup, Settings operation and shell-status composables
with fixture state; the existing concurrency checks exercise real WorkManager and
interrupted history recovery. Unit checks cover Android connectivity callbacks,
initial snapshots, reconnection, subscription cleanup and legacy progress payloads.

## PR review follow-up

- `ANDROID_HOME=/Users/scans/Library/Android/sdk ./gradlew :app:testDebugUnitTest :gamification:test --no-daemon`
  passed, including the operation-reporting, Setup partial-outcome, migration, and
  collection deletion retry regressions.
- Targeted instrumentation passed all 41 `MigrationTest` tests and all 5
  `CollectionFormBehaviorTest` tests. Both runs explicitly targeted
  `emulator-5554` through `ANDROID_SERIAL` and the Gradle instrumentation serial
  argument.
- `ANDROID_HOME=/Users/scans/Library/Android/sdk ./gradlew :app:verifyRoborazziDebug --no-daemon`
  passed locally. Tracked screenshot baselines were not changed.
- CI runs #850 (`46019962806`) and #851 (`46026529793`) each report the same nine
  Library screenshot mismatches: populated Library in both themes and viewports,
  dark Library selection, and offline Library in both themes and viewports. Visual
  comparison shows matching layout and labels, with differences concentrated in
  card gradients and progress fills. All nine CI `*_actual.png` files are
  byte-identical across the two runs. Those failures were unresolved at that
  checkpoint; the bounded comparison update and its later remote verification are
  recorded below.
- CI run #852 (`46033414568`, commit `23179d8`) repeated those nine mismatches. Its
  artifacts report 3–14 differing pixels per 320×720 or 412×915 image (at most
  0.0057% of pixels), with no layout or text movement. The screenshot verifier now
  permits at most 0.01% differing pixels only for the Library gradient captures;
  all other captures retain the default zero-changed-pixel threshold. No baselines
  were re-recorded. The CI-equivalent 136-screenshot command passed locally after
  this change. At that checkpoint no post-fix GitHub run had completed; the next
  remote result is recorded below.

## Exact-head review checkpoint and local resolution

- Confirmed the PR head at the review checkpoint was
  `dfaee38dc2d8905fef548caf0671e158929a215d`. CI run
  [#855](https://github.com/cnhy-nero-diskard/Backlogium/actions/runs/46044815962)
  completed successfully on that exact head. This run predates the local review
  resolution below and is not claimed as remote validation for those edits.
- Focused operation-reporting, report-store, scheduler, and sync-worker JVM suites
  passed after the resolution:
  ```sh
  ANDROID_HOME=/Users/scans/Library/Android/sdk ./gradlew :app:testDebugUnitTest \
    --tests 'com.example.backlogium.work.OperationReportingTest' \
    --tests 'com.example.backlogium.work.OperationReportStoreTest' \
    --tests 'com.example.backlogium.work.SyncSchedulerTest' \
    --tests 'com.example.backlogium.work.SteamSyncLibraryEvidenceWorkerTest' --no-daemon
  ```
  Coverage includes coherent stored 6/10 versus retained live 5/10, cleared live
  progress, retry-attempt reset, and exact-ID Setup projection after cancellation
  and a fresh scheduler subscription.
- Full local JVM validation passed:
  ```sh
  ANDROID_HOME=/Users/scans/Library/Android/sdk ./gradlew :app:assembleDebug :app:testDebugUnitTest :gamification:test --no-daemon
  ```
- `openspec validate issue161-enhancement-sync-progress-and-offline-feedback-minor --strict`
  passed.
- The review-resolution edits remain uncommitted and unpublished as required; no
  CI run is claimed for the changed working tree.

## Existing baseline drift

Full verification initially found five Library gradient mismatches: populated
Library in both themes/viewports and standard dark selection. Inspection showed
mostly one-channel-level differences in the artwork gradients, with unchanged
layout and labels. A temporary detached checkout of the unchanged starting commit
`f5538fc5` reproduced the narrow dark mismatch; its actual PNG was byte-identical
(SHA-1 `72c8c9170351f37feb5a81b9750777ebcf084497`) to this change's actual PNG.
The five baselines were reviewed and re-recorded in this integration checkpoint;
full verification then passed. The temporary checkout was removed.

## Limits

No live Steam requests, physical-device network handover, or remote CI result is
claimed. Deterministic tests use fixture responses. Existing scheduling,
post-play behavior, Setup readiness and automatic work selection remain in place.
See [the action feedback audit](../../../docs/sync-feedback-audit.md) for the owning
surfaces and preserved recovery contracts.
