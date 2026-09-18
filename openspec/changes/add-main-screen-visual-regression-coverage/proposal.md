## Why

The five main screens have no screenshot or golden coverage, so hierarchy, narrow-device fit, theme drift, and representative loading/empty/content states can regress without automated evidence. A dedicated visual-regression layer will protect the redesign work without coupling screenshot infrastructure to any one production-screen change.

## What Changes

- Add deterministic host-side Compose screenshot testing compatible with the current Android/Compose toolchain, selecting and pinning the concrete library during implementation after a compatibility spike.
- Introduce reusable fixture builders that render Home, Library, History, Analytics, and Settings without network, database, or Hilt dependencies.
- Capture standard-phone and narrow-phone goldens in dark and light themes for each main screen’s representative content state.
- Add targeted goldens for high-risk alternatives: Home now-playing/loading, Library active filters/no-results/selection, empty History, Analytics selected-day/empty window, and Settings top-level summaries.
- Make image comparison deterministic by fixing locale, density, font scale, animation clocks, asynchronous image behavior, and time-dependent values.
- Add a documented record/verify workflow and CI verification task; failures must publish useful diff artifacts.
- Keep this change test-only: it must not alter production UI behavior or visual decisions owned by the four screen proposals.

## Capabilities

### New Capabilities

None. This change is test infrastructure and explicitly skips specification deltas.

### Modified Capabilities

None.

## Impact

- Affects Gradle test configuration, version catalog entries, screenshot test source sets, fixtures, baseline images, CI workflow configuration, and contributor documentation.
- May add a screenshot-testing dependency after verifying compatibility with AGP 9.3, Kotlin 2.2.10, Compose BOM 2024.09.00, and the existing Robolectric setup.
- Should be applied after the four production-screen changes so its initial baselines represent the intended final UI.
