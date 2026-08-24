## 1. Build Variant Identity

- [ ] 1.1 Configure the debug build with `.debug` application-ID and `-debug` version-name suffixes while leaving the release ID and code namespace unchanged; verify Gradle's debug and release variant metadata resolve to `com.example.backlogium.debug` and `com.example.backlogium` respectively.
- [ ] 1.2 Add the debug-only `Backlogium Debug` label through the debug resource source set; verify merged debug resources use the new label and merged release resources still use `Backlogium`.

## 2. Debug Launcher Badge

- [ ] 2.1 Add debug-only adaptive and round launcher resources that preserve the release artwork and overlay a high-contrast amber `DBG` badge with dark outline inside the safe zone; verify `:app:processDebugResources` succeeds without changing main/release launcher assets.
- [ ] 2.2 Provide badge geometry for monochrome/themed presentation and render or inspect adaptive, round, and themed debug icons at launcher scale to verify the badge remains legible and is not clipped.

## 3. Variant-Aware Tests and Tooling

- [ ] 3.1 Replace the instrumentation test's fixed release package assertion with the generated target application ID; run the focused `ExampleInstrumentedTest` against the debug variant and verify it passes with `com.example.backlogium.debug`.
- [ ] 3.2 Update the `.codex`, `.claude`, and `.cline` run-on-device skill copies to launch the debug package and document one-time cleanup of legacy debug installs; verify a repository search finds no device-launch command that targets the release package after `installDebug`.
- [ ] 3.3 Review remaining `com.example.backlogium` literals and retain source namespaces, CI test-class selectors, and private intent-extra keys intentionally; verify only installed-target assumptions are changed.

## 4. Build and Device Verification

- [ ] 4.1 Run `:app:assembleDebug`, `:app:assembleRelease`, `:app:testDebugUnitTest`, and `:gamification:test`; verify both APKs build, unit tests pass, and APK manifests report the expected distinct application IDs and provider authorities.
- [ ] 4.2 On a device that already has the signed release installed, run the debug install-and-launch workflow; verify both launcher entries exist, show the correct labels and icons, and open their corresponding package IDs.
- [ ] 4.3 Configure distinguishable state in each installed variant and exercise permissions, notifications, and scheduled sync; verify state and runtime behavior remain independent while existing debug-only update gating remains intact.
- [ ] 4.4 Uninstall the debug variant and verify the release app and its data still work, then reinstall debug and confirm it starts with independent state; record device/emulator evidence and any verification limitation.
