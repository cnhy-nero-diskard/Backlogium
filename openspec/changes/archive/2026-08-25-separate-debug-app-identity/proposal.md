## Why

Installing a debug APK currently replaces or conflicts with the release build because both variants share the same Android application identity. Developers need to keep a testable debug build beside their everyday release installation without risking release data or confusing the two apps on-device.

## What Changes

- Give debug builds a distinct Android application ID while preserving the existing release application ID and code namespace.
- Present debug builds with a distinct app label, debug-suffixed version name, and clearly badged launcher icon across supported launcher shapes and themed-icon presentation.
- Keep debug and release application data, permissions, notifications, scheduled work, and credentials isolated through Android's package sandbox; do not automatically copy data between variants.
- Update variant-sensitive tests and device-launch tooling so debug installation and launch target the debug identity.
- Preserve release packaging, signing, installation, self-update behavior, and launcher identity unchanged.

## Capabilities

### New Capabilities

- `build-variant-identity`: Defines side-by-side debug and release installation, visible debug identification, data isolation, and variant-aware development tooling.

### Modified Capabilities

None.

## Impact

- Android build configuration and debug-specific launcher resources.
- Instrumented assertions that currently hardcode the release package ID.
- Repository-maintained run-on-device instructions that currently launch `com.example.backlogium` after installing a debug APK.
- Developer workflows gain side-by-side installation; existing release users and release artifacts remain compatible.
