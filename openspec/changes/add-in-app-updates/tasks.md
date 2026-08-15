> **Blocked on `auditfix-secrets-and-packaging`.** Until version metadata is derived from the release
> tag, every published APK declares `versionCode = 1`: comparison is meaningless and the installer
> rejects every update as a downgrade. Do not start section 3 before that change has landed.

## 1. Release lookup

- [ ] 1.1 Add `GitHubReleaseDto` covering `tag_name`, `name`, `body`, `draft`, `prerelease`, and an `assets` list of `name`, `browser_download_url`, `size`
- [ ] 1.2 Add a Retrofit service for `https://api.github.com/repos/cnhy-nero-diskard/Backlogium/releases/latest`, unauthenticated, with `Accept: application/vnd.github+json`
- [ ] 1.3 Parse the tag against `^v(\d+)\.(\d+)\.(\d+)$`, returning no release rather than throwing when it does not match
- [ ] 1.4 Ignore any response still flagged `draft` or `prerelease`, defensively — the endpoint should already exclude them
- [ ] 1.5 Locate the `.apk` asset and its `.sha256` sibling; treat either being absent as "no update available"
- [ ] 1.6 Treat a rate-limit, 5xx, or unreachable host as a no-op that records only the attempt time

## 2. Version comparison

- [ ] 2.1 Encode the parsed tag into a version code with the identical encoding `auditfix-secrets-and-packaging` uses at build time, in one shared place so the two cannot diverge
- [ ] 2.2 Read the running build's `versionCode` from `PackageManager`, not from `BuildConfig`, so it is the value the installer will compare against
- [ ] 2.3 Offer an update only when the release's code is strictly greater
- [ ] 2.4 Use `versionName` for display only, never for the comparison

## 3. Check scheduling and state

- [ ] 3.1 Add `UpdateCheckWorker` and a daily `PeriodicWorkRequest` with a connected-network constraint, enqueued as unique work with `KEEP`
- [ ] 3.2 Ensure the check is never invoked from app startup or from a screen's composition
- [ ] 3.3 Add DataStore keys for last check time, last seen release tag, and declined release tag
- [ ] 3.4 Notify only when the found tag differs from the declined tag
- [ ] 3.5 Post the notification following `HltbRefreshWorker`'s pattern, skipping silently when `POST_NOTIFICATIONS` is not granted
- [ ] 3.6 Add an update notification channel at `IMPORTANCE_DEFAULT`, separate from the HLTB and now-playing channels
- [ ] 3.7 Make the notification a plain tap-to-open with no action buttons

## 4. Download

- [ ] 4.1 Download the APK asset with OkHttp into `noBackupFilesDir`, streaming to disk rather than buffering
- [ ] 4.2 Report progress as bytes-read over content-length, tolerating an absent content-length by reporting indeterminate progress
- [ ] 4.3 Support cancellation, deleting any partial file
- [ ] 4.4 Delete the artifact after a successful install, a failed verification, a failed install, and an abandoned download
- [ ] 4.5 On each check, sweep any artifact in the directory that is not the one currently offered

## 5. Verification

- [ ] 5.1 Fetch the `.sha256` asset and compare it against the downloaded file's computed SHA-256, streaming rather than reading the file into memory
- [ ] 5.2 On mismatch, delete and report a failed download
- [ ] 5.3 Read the downloaded APK's signing certificate via `PackageManager`'s archive inspection and compare it to the running package's
- [ ] 5.4 On signer mismatch, delete and report, without invoking the installer
- [ ] 5.5 Add a KDoc note that the digest establishes integrity only, and that authenticity rests on the signing key the OS enforces

## 6. Installation

- [ ] 6.1 Declare `REQUEST_INSTALL_PACKAGES` in the manifest with a comment naming the feature that requires it
- [ ] 6.2 Check `PackageManager.canRequestPackageInstalls()` before starting; if absent, explain and send the user to the "install unknown apps" settings screen
- [ ] 6.3 Install through a `PackageInstaller` session, writing the verified APK and committing it
- [ ] 6.4 Do not set `setRequireUserAction(false)`; the first update is user-confirmed by construction and later ones must behave identically
- [ ] 6.5 Handle `STATUS_PENDING_USER_ACTION` by launching the system-supplied intent
- [ ] 6.6 On `STATUS_SUCCESS`, fire a `PendingIntent` that launches the main activity
- [ ] 6.7 On every other status, including user cancellation, leave the app unchanged, delete the artifact, and report

## 7. UI

- [ ] 7.1 Add an update sheet showing running version, available version, release notes, and Update / Later actions
- [ ] 7.2 Show download and verification progress in the sheet
- [ ] 7.3 Record the declined tag when Later is chosen
- [ ] 7.4 Add the Settings Updates section: running version, last check time, availability, and a manual check control
- [ ] 7.5 Report a manual check that found nothing as an explicit "you're up to date", not as silence
- [ ] 7.6 Gate the worker, the notification, and the Settings section on `!BuildConfig.DEBUG`
- [ ] 7.7 Keep storage types out of `ui/` — the update state reaches the UI as a domain model

## 8. CI

- [ ] 8.1 Add a checksum step to `.github/workflows/release.yml` computing SHA-256 of the collected APK in the same job that uploads it
- [ ] 8.2 Write it as `<apk-name>.sha256` and attach it to the release alongside the APK
- [ ] 8.3 Fail the release if the checksum cannot be produced
- [ ] 8.4 Confirm the file's format is exactly what the client parses

## 9. Tests

- [ ] 9.1 Unit-test tag parsing: valid, non-`v`-prefixed, four-component, and non-numeric tags
- [ ] 9.2 Unit-test comparison: newer offered, equal not offered, older not offered
- [ ] 9.3 Unit-test that a draft or pre-release response yields no update
- [ ] 9.4 Unit-test that a release missing its APK asset, or missing its checksum asset, yields no update
- [ ] 9.5 Unit-test the decline rule: same tag suppressed, newer tag announced
- [ ] 9.6 Unit-test SHA-256 verification against a known-good and a corrupted fixture
- [ ] 9.7 Unit-test that a verification failure deletes the artifact and does not reach the installer
- [ ] 9.8 Unit-test that rate-limit, 5xx, and connection-failure responses record only the attempt time
- [ ] 9.9 Unit-test that the artifact sweep removes a stale file and keeps the currently offered one

## 10. Verification

- [ ] 10.1 `./gradlew :app:testDebugUnitTest :gamification:test`
- [ ] 10.2 Confirm a debug build performs no check and shows no Updates section
- [ ] 10.3 Cut a test release through the real workflow and confirm both the APK and its `.sha256` are attached
- [ ] 10.4 On device with a release build: confirm the notification appears, the sheet shows the correct version pair and notes, and the download reports progress
- [ ] 10.5 Confirm the install completes and the app relaunches on the new version
- [ ] 10.6 Cancel at the system install prompt and confirm the app is unchanged and the artifact is gone
- [ ] 10.7 Corrupt a downloaded artifact and confirm verification fails before the installer is reached
- [ ] 10.8 Airplane mode: confirm checks fail silently and every other feature is unaffected
- [ ] 10.9 Confirm "Later" suppresses re-notification for that version but not for a newer one
