## Context

The app already has Robolectric unit tests and Compose instrumentation tests but no screenshot dependency, baseline images, deterministic screen fixtures, or CI diff artifacts. The production-screen proposals intentionally do not own screenshot infrastructure. This change is tooling-only and declares `skip_specs: true`.

## Goals / Non-Goals

**Goals:**

- Establish repeatable host-side image verification for all five main screens.
- Cover both themes, two phone widths, and the highest-risk alternative states without requiring Steam credentials or a device.
- Make baseline updates explicit and reviewable while producing useful CI diffs.

**Non-Goals:**

- Changing production UI, navigation, or behavior.
- Snapshotting every state permutation or remote image response.
- Replacing semantic, unit, or connected-device tests.

## Decisions

### 1. Use Roborazzi on the existing Robolectric test stack

Adopt Roborazzi for host-side Compose capture and comparison, pinning the newest release proven compatible with AGP 9.3, Kotlin 2.2.10, Compose BOM 2024.09.00, and Robolectric 4.15.1. The first implementation task is a minimal compile/render/compare spike. If compatibility cannot be proven, stop and revise this design instead of silently falling back to device-only screenshots.

AndroidX preview screenshot testing and connected-device capture were considered. The former adds a second preview-specific rendering model and must still be compatibility-proven; the latter is slower and inherits emulator instability. Roborazzi best reuses the existing host-side stack.

### 2. Render stateless screen content through fixture hosts

Expose or add internal stateless content entry points where necessary, then build test-only fixture factories for Home, Library, History, Analytics, and Settings. Fixtures provide complete presentation state and callbacks without Hilt, Room, WorkManager, or network access. Production logic remains covered by its existing ViewModel tests.

### 3. Fix the rendering environment

Every capture pins locale to `en-US`, density, font scale 1.0, dark/light theme, animation clock, system time, and device dimensions. Remote art is replaced by deterministic test painters or an image-loader fake; Lottie and infinite transitions are frozen at documented frames.

Use two phone classes: narrow `320 x 720 dp` and standard `412 x 915 dp`. These are layout stress fixtures, not claims about a specific device model.

### 4. Use a bounded baseline matrix

The required matrix is:

- Each main screen: representative populated state, dark and light, narrow and standard.
- Home: now-playing and first-load state.
- Library: active combined filters/no results and multi-selection.
- History: no-history state.
- Analytics: selected-day detail and empty window.
- Settings: four-group overview with attention and healthy summaries.

Avoid cross-product explosion: alternative states use dark standard unless the state specifically exercises theme or width behavior.

### 5. Separate record and verify workflows

Provide explicit Gradle tasks for recording baselines and verifying them. CI runs verification only; it never records. Failed comparisons retain actual, expected, and diff images and upload them as artifacts. Baseline changes are committed and reviewed alongside the UI change that caused them.

### 6. Sequence after the production proposals

Create infrastructure early if useful, but record the initial canonical baselines only after the four production-screen changes land. This prevents the test-only change from canonizing the known critique defects.

## Risks / Trade-offs

- **[Risk] Library/toolchain incompatibility.** → Gate all later work on the minimal spike and pin the verified version.
- **[Risk] Font or renderer drift creates noisy diffs.** → Pin JDK/test environment in CI and document intentional baseline regeneration.
- **[Risk] Goldens become expensive to review.** → Keep the matrix bounded and name each file by screen, state, theme, and viewport.
- **[Risk] Test hooks leak into production APIs.** → Prefer internal stateless composables and test-only fixtures; do not add debug runtime switches.

## Migration Plan

Add the dependency and spike, build the shared harness, add screen fixtures, record the bounded matrix after production UI changes, then enable CI verification and diff artifacts. Removing the plugin/tasks/baselines fully rolls back the change without production impact.
