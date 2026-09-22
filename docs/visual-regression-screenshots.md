# Visual regression screenshots

The screenshot suite is host-side Compose testing through Robolectric and Roborazzi. It uses the
debug-only `ScreenshotTestActivity`, deterministic test painters, fixed fixture values, and no
Steam credentials, network, device, Hilt graph, Room database, or WorkManager runtime.

## Prerequisites

- JDK 17 and the Android SDK used by the project, including API 35.
- A configured `local.properties` for local Gradle work.
- Dependencies resolved once before using `--offline`; a fresh checkout should omit `--offline`
  on its first Gradle invocation.
- Baselines present under `app/src/test/snapshots/`.
- Host-side Robolectric tests are pinned to API 35 by `app/src/test/resources/robolectric.properties`,
  matching the supported SDK range of the pinned Robolectric release.

Roborazzi is pinned in `gradle/libs.versions.toml`. Captures use `en-US`, UTC, `mdpi`, font scale
1.0, a paused Compose clock, a fixed test clock, native Robolectric graphics, and these viewports:

| Name | Qualifiers | Size |
| --- | --- | --- |
| narrow | `en-rUS-w320dp-h720dp-mdpi` | 320 x 720 dp |
| standard | `en-rUS-w412dp-h915dp-mdpi` | 412 x 915 dp |

## Record baselines locally

Recording is an explicit developer action. Run it only after reviewing the UI change that should
alter the goldens:

```powershell
.\gradlew.bat :app:compileDebugUnitTestKotlin --no-daemon
.\gradlew.bat :app:recordRoborazziDebug `
  --tests com.example.backlogium.ui.screenshot.RoborazziSpikeTest `
  --tests com.example.backlogium.ui.screenshot.MainScreenScreenshotNarrowTest `
  --tests com.example.backlogium.ui.screenshot.MainScreenScreenshotStandardTest `
  --no-daemon
```

Review the changed PNGs, then commit them with the test changes. Baseline paths are keyed by
screen, state, theme, and viewport:

- `main/<screen>/<state>/<theme>/<viewport>.png` contains the 20 core cells.
- `alternatives/<screen>/<state>/dark/standard.png` contains the bounded edge-state cells.
- `spike/dark-standard/theme-card.png` is the toolchain compatibility spike.

Generated actual, compare/diff, JSON, and HTML report files live below `app/build/` and are
ignored. They must not be committed as baselines.

## Verify baselines locally

Verification never records or rewrites tracked PNGs. Run it with the same three screenshot test
classes used by CI:

```powershell
.\gradlew.bat :app:compileDebugUnitTestKotlin --offline --no-daemon
.\gradlew.bat :app:verifyRoborazziDebug `
  --tests com.example.backlogium.ui.screenshot.RoborazziSpikeTest `
  --tests com.example.backlogium.ui.screenshot.MainScreenScreenshotNarrowTest `
  --tests com.example.backlogium.ui.screenshot.MainScreenScreenshotStandardTest `
  --offline --no-daemon
```

For a clean repeat, remove only the generated Roborazzi directories under `app/build/`, run the
command twice, and confirm `git status` shows no baseline rewrites. A mismatch leaves expected
baselines in `app/src/test/snapshots/` and actual/compare images plus reports under
`app/build/outputs/roborazzi/compare/`, `app/build/test-results/roborazzi/`, and
`app/build/reports/roborazzi/`.

CI runs compilation and verification only. It never runs a record task. On failure, the named
`roborazzi-visual-regression-*` artifact contains the tracked expected snapshots and generated
comparison outputs for review.
