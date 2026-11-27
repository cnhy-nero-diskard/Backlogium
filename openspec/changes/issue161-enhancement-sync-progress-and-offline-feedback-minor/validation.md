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
