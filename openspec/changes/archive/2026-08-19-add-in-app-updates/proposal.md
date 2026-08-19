## Why

Backlogium is distributed as a signed APK attached to a GitHub Release. `.github/workflows/release.yml`
already gates on a `vX.Y.Z` tag reachable from `master`, builds, signs with the release keystore, and
publishes the artifact. Everything needed to *ship* an update exists.

Nothing needed to *receive* one does. Updating means noticing a release happened, opening
github.com/cnhy-nero-diskard/Backlogium on a phone, finding the right asset among the release's
files, downloading it through the browser, clearing the "install unknown apps" prompt, and
installing — for a version bump. In practice that means updates do not get installed, and the
installed build drifts arbitrarily far behind `master`.

There was a second, sharper reason this could not simply be bolted on. Until
`auditfix-secrets-and-packaging` landed, `app/build.gradle.kts` hardcoded `versionCode = 1` and
`versionName = "1.0"` — **every release APK ever published declared itself version 1.** An updater
had no way to ask "am I older than the latest release?", and Android would reject the install as a
downgrade even if it did. That change (archived 2026-08-16) now derives version metadata from the
release tag, so the prerequisite is satisfied and this change is implementable as specified.

## What Changes

- Check `https://api.github.com/repos/cnhy-nero-diskard/Backlogium/releases/latest` once a day in
  the background, and on demand from Settings.
- Compare the release's tag against the running build's version, accepting only full releases with
  a valid `vX.Y.Z` tag — no drafts, no pre-releases.
- Notify when a newer release exists, showing the version and its release notes. Download nothing
  until the user asks.
- On the user's action: download the release's APK asset to app-private storage with visible
  progress, verify its SHA-256 against a checksum asset published alongside it, and verify its
  signing certificate matches the running app's before handing it to the installer.
- Install through `PackageInstaller`, and relaunch the app when the install succeeds.
- Publish a `.sha256` asset alongside the APK in `release.yml`, so there is something to verify
  against.
- Do all of this only in release builds, and only when the user has not already declined this
  version.

## Capabilities

### New Capabilities

- `app-updates`: Defines release discovery and its cadence, the version-comparison and channel
  rules, notification and decline semantics, download and cleanup, the two-stage verification, the
  install and relaunch flow, and the requirement that every part of it degrade silently offline.

### Modified Capabilities

- `release-packaging`: A published release additionally carries a checksum asset for its APK, so an
  installed client can verify what it downloaded.
- `app-settings`: Settings gains an updates section showing the running version, the last check, and
  a manual check control.

## Impact

- **Affected code:** A new `data/updates/` layer (release lookup, download, verification), a new
  WorkManager worker and scheduler, a notification channel, an update sheet in `ui/`, a Settings
  section, `AndroidManifest.xml` for `REQUEST_INSTALL_PACKAGES`, and
  `.github/workflows/release.yml`.
- **Storage:** The downloaded APK lives in app-private storage and is deleted after install or
  failure. A small amount of DataStore state: last check time, last seen release tag, declined tag,
  and the current install lifecycle status so an asynchronous PackageInstaller failure can be
  retried.
- **Network:** One unauthenticated GitHub API request per day plus one per manual check — well
  inside the 60-per-hour anonymous limit. An APK download only on explicit user action.
- **Permissions:** `REQUEST_INSTALL_PACKAGES`, a normal-install-time permission that still requires
  the user to grant "install unknown apps" for this app the first time.
- **Dependencies:** None new. OkHttp, Retrofit, kotlinx.serialization, WorkManager, and Hilt are all
  already present.

## Depends on

`auditfix-secrets-and-packaging` **must land first — satisfied.** That change landed on 2026-08-16:
version metadata is now derived from the release tag (`release.yml` passes `-PversionName` /
`-PversionCode` to `assembleRelease`), so every APK published since declares a comparable version
and the installer accepts a newer build. No outstanding prerequisites.

## Non-goals

- Any distribution channel other than this repository's GitHub Releases.
- Automatic installation without user action.
- Downloading an update before the user asks for it.
- Pre-release or beta channels.
- Delta or patch updates. The full APK is downloaded.
- Rollback to an earlier version.
- Making any part of the app depend on network availability or on GitHub being reachable.
