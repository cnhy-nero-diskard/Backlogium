## Context

See `proposal.md` for motivation. The app module currently gives debug and release variants the same `com.example.backlogium` application ID and the same launcher resources. The Kotlin/Java namespace is also `com.example.backlogium`, but it does not need to change to create a distinct installed application. Android already scopes Room, DataStore, files, permissions, notifications, and WorkManager state by application ID.

The manifest's AndroidX Startup provider authority is derived from `${applicationId}`, which is compatible with side-by-side installation. There are no Firebase, OAuth callback, or deep-link registrations tied to the current package ID. One instrumentation test and the repository's three synchronized run-on-device skill copies assume that an installed debug APK uses the release package ID.

## Goals / Non-Goals

**Goals:**

- Use Android's normal build-variant mechanisms to create a separate debug installation without renaming source packages.
- Make the debug application obvious at launcher scale and under themed-icon rendering.
- Preserve realistic debug behavior while preventing the identity change from affecting release compatibility.
- Keep variant-sensitive tests and developer launch instructions aligned with the generated debug identity.

**Non-Goals:**

- Renaming the production application ID or code namespace.
- Sharing application-private state automatically between variants.
- Changing release signing, publishing, or self-update behavior.
- Disabling normal debug syncing or background work solely because release may also be installed.

## Decisions

### Use a debug application ID suffix and retain the namespace

Configure the debug build type with the `.debug` application ID suffix, yielding `com.example.backlogium.debug`, while leaving `namespace = "com.example.backlogium"` and the release application ID unchanged. Add a `-debug` version-name suffix.

This is the smallest Android-native separation and preserves source imports, generated `R` and `BuildConfig` packages, release upgrade continuity, and CI test class names. A full package rename was rejected because it creates broad churn without improving side-by-side installation.

### Overlay identity resources from the debug source set

Place debug-only resource overrides under `app/src/debug/res`. Override the app label with `Backlogium Debug` and point debug adaptive and round launcher icons at a debug foreground that layers a high-contrast amber `DBG` badge inside the lower-right safe zone of the existing artwork.

The badge will include a dark outline and explicit `DBG` geometry, not merely a color shift. Its monochrome representation will preserve the badge silhouette and lettering so themed launchers remain distinguishable. Keeping all overrides in the debug source set prevents release resource drift. Replacing the main icon or relying only on a different label was rejected because launcher labels may be truncated or hidden.

### Rely on Android package sandboxing for isolation

Do not add cross-package providers, shared user IDs, automatic cloning, or migration code. The new application ID naturally produces independent app-private storage, keystore entries, permissions, notification channels, WorkManager databases, and backup namespaces. Existing explicit backup export/import remains the supported way to seed debug with chosen data.

This avoids coupling development state to a user's release installation and makes uninstall behavior predictable. Automatic copying was rejected because it would require privileged or user-mediated access and could expose credentials or corrupt the release test baseline.

### Keep debug runtime behavior representative

Identity separation will not add new debug-only gates. Existing `BuildConfig.DEBUG` behavior remains authoritative: local development credential seeding and diagnostics stay available, while release self-update scheduling and prompts remain disabled. If both variants are configured, each may run its own sync and background work; this is an accepted consequence of independent installations and provides realistic testing.

Disabling debug background work by default was rejected because it would make device testing diverge from production behavior and conceal lifecycle defects.

### Make tests and launch tooling variant-aware

Replace the instrumentation test's fixed release-ID expectation with the generated application ID for the target variant. Keep test class package names unchanged so focused CI runner arguments continue to resolve.

Update the `.codex`, `.claude`, and `.cline` run-on-device skill copies together so `installDebug` launches `com.example.backlogium.debug`. These files are synchronized repository interfaces and allowing them to disagree would make tool behavior nondeterministic. Other `com.example.backlogium` strings used as source packages or private intent-extra keys do not identify the installed target and remain unchanged.

## Risks / Trade-offs

- [Both configured variants can perform duplicate Steam polling and background work] -> Preserve realistic behavior, keep state independently configurable, and make notification/app identity obvious to the user.
- [An existing pre-change debug install occupies the release package ID] -> Document the one-time need to export anything valuable and uninstall that debug-signed package before restoring or installing the signed release at the same ID.
- [Badge detail can be clipped or illegible under launcher masks] -> Keep it inside the adaptive-icon safe zone and verify adaptive, round, and monochrome rendering on a device or emulator.
- [A hardcoded release package ID may survive in development automation] -> Search repository tooling separately from source namespace declarations and validate the run-on-device launch target.
- [Removing the suffix later would strand debug-only state] -> Treat debug state as disposable or export it explicitly before rollback; never merge it automatically into release state.

## Migration Plan

1. Add debug build and resource overlays without changing release configuration.
2. Update the instrumentation assertion and synchronized device-launch instructions.
3. Build both variants and inspect their merged application IDs, labels, provider authorities, and launcher resources.
4. On a device with release installed, install and launch debug; confirm independent state, notifications, and uninstall behavior.
5. Roll back by removing the debug overlays and suffixes. Any data under the debug package remains separate and should be exported or uninstalled explicitly.
