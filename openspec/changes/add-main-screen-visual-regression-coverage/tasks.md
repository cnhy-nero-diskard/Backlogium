## 1. Toolchain compatibility gate

- [x] 1.1 Add a pinned Roborazzi/plugin dependency candidate compatible with the current AGP, Kotlin, Compose BOM, and Robolectric versions, and verify Gradle configuration plus a minimal Compose screenshot test compile offline after dependencies are cached.
- [x] 1.2 Render, record, and compare one deterministic theme/card spike through explicit Gradle tasks, and verify a deliberate pixel change fails with expected/actual/diff outputs; if this gate cannot pass, stop and revise the design before further tasks.

## 2. Deterministic screenshot harness

- [ ] 2.1 Add shared screenshot rules for locale `en-US`, density, font scale, system time, animation clock, dark/light theme, and the 320x720dp and 412x915dp viewports; verify repeated captures are byte-stable.
- [ ] 2.2 Add deterministic image/Lottie/motion substitutes that avoid network and wall-clock input, and verify fixtures render identical output without Steam credentials, Hilt, Room, WorkManager, or a device.
- [ ] 2.3 Add clear file naming and storage conventions keyed by screen, state, theme, and viewport, and verify baseline files are tracked while generated actual/diff output is ignored.

## 3. Main-screen fixtures

- [ ] 3.1 Add stateless fixture hosts and representative populated state for Home and Library, and verify both themes and viewports render without clipping or asynchronous placeholders.
- [ ] 3.2 Add stateless fixture hosts and representative populated state for History and Analytics, and verify timeline, chart selection, and metric cards render deterministically.
- [ ] 3.3 Add a stateless fixture host and representative grouped-overview state for Settings, and verify healthy and attention summaries do not expose credentials or test-environment values.

## 4. Baseline matrix

- [ ] 4.1 Record and review the 20 core baselines covering five populated screens across two themes and two viewports, and verify every filename maps to one declared matrix cell.
- [ ] 4.2 Record and review dark-standard alternative baselines for Home now-playing/loading, Library combined-filter no-results/selection, empty History, Analytics selected-day/empty-window, and Settings healthy/attention summaries.
- [ ] 4.3 Run the verify task twice from a clean test output directory and verify the complete matrix passes without baseline rewrites or timing-dependent differences.

## 5. CI and documentation

- [ ] 5.1 Add screenshot verification to CI after unit compilation, and verify a controlled failing branch uploads expected, actual, and diff images as a named artifact.
- [ ] 5.2 Document prerequisites plus separate record and verify commands, baseline-review rules, and the requirement that CI never records; verify a fresh checkout can follow the documented verify path.
- [ ] 5.3 Run `./gradlew.bat :app:testDebugUnitTest --offline --no-daemon`, the screenshot verify task, `openspec validate add-main-screen-visual-regression-coverage --strict`, and `git diff --check`, and verify no production UI behavior changed in this test-only change.
