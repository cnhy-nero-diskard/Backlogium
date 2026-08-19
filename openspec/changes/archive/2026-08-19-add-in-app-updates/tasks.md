> **Unblocked.** `auditfix-secrets-and-packaging` landed on 2026-08-16: version metadata is derived
> from the release tag, so every APK published since declares a comparable version. Comparison is
> meaningful, the installer accepts a newer build, and section 3 can start.

## 1. Release lookup

- [x] 1.1 Add `GitHubReleaseDto` covering `tag_name`, `name`, `body`, `draft`, `prerelease`, and an `assets` list of `name`, `browser_download_url`, `size`
- [x] 1.2 Add a Retrofit service for `https://api.github.com/repos/cnhy-nero-diskard/Backlogium/releases/latest`, unauthenticated, with `Accept: application/vnd.github+json`
- [x] 1.3 Parse the tag against `^v(\d+)\.(\d+)\.(\d+)$`, returning no release rather than throwing when it does not match
- [x] 1.4 Ignore any response still flagged `draft` or `prerelease`, defensively — the endpoint should already exclude them
- [x] 1.5 Locate the `.apk` asset and its `.sha256` sibling; treat either being absent as "no update available"
- [x] 1.6 Treat a rate-limit, 5xx, or unreachable host as a no-op that records only the attempt time

## 2. Version comparison

- [x] 2.1 Encode the parsed tag into a version code with the identical encoding `auditfix-secrets-and-packaging` uses at build time, in one shared place so the two cannot diverge
- [x] 2.2 Read the running build's `versionCode` from `PackageManager`, not from `BuildConfig`, so it is the value the installer will compare against
- [x] 2.3 Offer an update only when the release's code is strictly greater
- [x] 2.4 Use `versionName` for display only, never for the comparison

## 3. Check scheduling and state

- [x] 3.1 Add `UpdateCheckWorker` and a daily `PeriodicWorkRequest` with a connected-network constraint, enqueued as unique work with `KEEP`
- [x] 3.1a Guard the worker on `lastCheckTime`: return success without issuing a request when a check completed within the last 20 hours — the `PeriodicWorkRequest` fires on its own clock and does not by itself implement "about a day since the last check"
- [x] 3.1b Use 20 hours rather than 24, with a comment recording why: WorkManager places a periodic run inside its interval, so an exact 24-hour guard would skip a tick arriving slightly early and halve the effective cadence
- [x] 3.1c Have both the periodic and the manual path write `lastCheckTime` on completion, so they share one notion of when a check last happened; a manual check bypasses the guard but still updates it
- [x] 3.2 Ensure the check is never invoked from app startup or from a screen's composition
- [x] 3.3 Add DataStore keys for last check time, last seen release tag, and declined release tag
- [x] 3.4 Notify only when the found tag differs from the declined tag
- [x] 3.5 Post the notification following `HltbRefreshWorker`'s pattern, skipping silently when `POST_NOTIFICATIONS` is not granted
- [x] 3.6 Add an update notification channel at `IMPORTANCE_DEFAULT`, separate from the HLTB and now-playing channels
- [x] 3.7 Make the notification a plain tap-to-open with no action buttons

## 4. Download

- [x] 4.1 Download the APK asset with OkHttp into `noBackupFilesDir`, streaming to disk rather than buffering
- [x] 4.2 Report progress as bytes-read over content-length, tolerating an absent content-length by reporting indeterminate progress
- [x] 4.3 Support cancellation, deleting any partial file
- [x] 4.4 Delete the artifact after a successful install, a failed verification, a failed install, and an abandoned download
- [x] 4.5 On each check, sweep any artifact in the directory that is not the one currently offered

## 5. Verification

- [x] 5.1 Fetch the `.sha256` asset and compare it against the downloaded file's computed SHA-256, streaming rather than reading the file into memory
- [x] 5.2 On mismatch, delete and report a failed download
- [x] 5.3 Read the downloaded APK's signing certificate via `PackageManager`'s archive inspection and compare it to the running package's
- [x] 5.4 On signer mismatch, delete and report, without invoking the installer
- [x] 5.5 Add a KDoc note that the digest establishes integrity only, and that authenticity rests on the signing key the OS enforces

## 6. Installation

- [x] 6.1 Declare `REQUEST_INSTALL_PACKAGES` in the manifest with a comment naming the feature that requires it
- [x] 6.2 Check `PackageManager.canRequestPackageInstalls()` before starting; if absent, explain and send the user to the "install unknown apps" settings screen
- [x] 6.3 Install through a `PackageInstaller` session, writing the verified APK and committing it
- [x] 6.4 Do not set `setRequireUserAction(false)`; the first update is user-confirmed by construction and later ones must behave identically
- [x] 6.5 Handle `STATUS_PENDING_USER_ACTION` by launching the system-supplied intent only when
  the app is resumed; otherwise post a notification whose content `PendingIntent` opens it
- [x] 6.6 On `STATUS_SUCCESS`, fire a `PendingIntent` that launches the main activity
- [x] 6.7 On every other status, including user cancellation, persist a retryable failure, leave the
  update available, delete the artifact, and report

## 7. UI

- [x] 7.1 Add an update sheet showing running version, available version, release notes, and Update / Later actions
- [x] 7.2 Show download and verification progress in the sheet
- [x] 7.3 Record the declined tag when Later is chosen
- [x] 7.4 Add the Settings Updates section: running version, last check time, availability, and a manual check control
- [x] 7.5 Report a manual check that found nothing as an explicit "you're up to date", not as silence
- [x] 7.6 Gate the worker, the notification, and the Settings section on `!BuildConfig.DEBUG`
- [x] 7.7 Keep storage types out of `ui/` — the update state reaches the UI as a domain model

## 8. CI

- [x] 8.1 Add a checksum step to `.github/workflows/release.yml` computing SHA-256 of the collected APK in the same job that uploads it
- [x] 8.2 Write it as `<apk-name>.sha256` and attach it to the release alongside the APK
- [x] 8.3 Fail the release if the checksum cannot be produced
- [x] 8.4 Confirm the file's format is exactly what the client parses

## 9. Tests

- [x] 9.1 Unit-test tag parsing: valid, non-`v`-prefixed, four-component, and non-numeric tags
- [x] 9.2 Unit-test comparison: newer offered, equal not offered, older not offered
- [x] 9.3 Unit-test that a draft or pre-release response yields no update
- [x] 9.4 Unit-test that a release missing its APK asset, or missing its checksum asset, yields no update
- [x] 9.5 Unit-test the decline rule: same tag suppressed, newer tag announced
- [x] 9.6 Unit-test SHA-256 verification against a known-good and a corrupted fixture
- [x] 9.7 Unit-test that a verification failure deletes the artifact and does not reach the installer
- [x] 9.8 Unit-test that rate-limit, 5xx, and connection-failure responses record only the attempt time
- [x] 9.8a Unit-test the cadence guard: a periodic run shortly after a manual check issues no request; a periodic run more than 20 hours after the last check issues one; a manual check issues one regardless and updates the timestamp
- [x] 9.9 Unit-test that the artifact sweep removes a stale file and keeps the currently offered one
- [x] 9.10 Unit-test that a persisted PackageInstaller failure leaves the update available and
  allows a retry

## 10. Verification

- [x] 10.1 `./gradlew :app:testDebugUnitTest :gamification:test`
- [x] 10.2 Confirm a debug build performs no check and shows no Updates section

The branch reached master and three real releases (v1.7.1, v1.7.2, v1.7.3) were cut, unblocking
10.3–10.9. 10.3–10.5 and 10.9 were verified on-device against that real round trip; the relaunch
step surfaced a real bug (Android's background-activity-launch restriction blocking the
post-install relaunch), fixed and released separately as `auditfix-update-relaunch-bal`. 10.6–10.8
remain: they need scenarios (an explicit system-prompt cancel, a corrupted artifact, airplane mode)
that weren't exercised by the round trip actually run.

- [x] 10.3 Cut a test release through the real workflow and confirm both the APK and its `.sha256` are attached — v1.7.1/v1.7.2/v1.7.3 all published with both assets; checksum verified against the downloaded APK
- [x] 10.4 On device with a release build: confirm the notification appears, the sheet shows the correct version pair and notes, and the download reports progress — notification titled "Backlogium 1.7.3 is available" on the `app_updates` channel; sheet showed "1.7.2 → 1.7.3" with release notes; progress bar visible during download
- [x] 10.5 Confirm the install completes and the app relaunches on the new version — versionName/versionCode confirmed bumped after each install; relaunch confirmed via the fixed notification-tap path (see `auditfix-update-relaunch-bal`) landing on Settings running the new version
- [ ] 10.6 Cancel at the system install prompt and confirm the app is unchanged and the artifact is gone
- [ ] 10.7 Corrupt a downloaded artifact and confirm verification fails before the installer is reached
- [ ] 10.8 Airplane mode: confirm checks fail silently and every other feature is unaffected
- [x] 10.9 Confirm "Later" suppresses re-notification for that version but not for a newer one — declined v1.7.2, a subsequent manual check found the same tag again but posted no new notification while Settings still showed and offered it; the newer-version-still-announces half is covered by the existing unit test suite (9.5)
