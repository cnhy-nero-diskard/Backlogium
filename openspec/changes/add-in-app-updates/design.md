## Context

- `.github/workflows/release.yml` gates on a `vX.Y.Z` tag reachable from `origin/master`, runs unit
  tests, decodes the release keystore from secrets, assembles a signed release APK, and publishes it
  with `generate_release_notes: true`. The publishing half needs one addition; the gating half needs
  none.
- `app/build.gradle.kts:52-53` hardcodes `versionCode = 1` / `versionName = "1.0"`. Tags reach
  `v1.6.22`. Every published APK therefore declares itself version 1.
- Release signing is conditional on `hasReleaseSigningConfig`; CI supplies the keystore through
  environment variables. A locally-built release without those is unsigned.
- `CLAUDE.md`: "The app must work with no network and no cloud."
- `HltbRefreshWorker` is the precedent for a worker that posts progress and completion
  notifications, including the `POST_NOTIFICATIONS` check that skips silently when the runtime grant
  is absent.
- `auditfix-secrets-and-packaging` moves the app's own snapshots to `noBackupFilesDir` on the
  principle that derived, re-obtainable files should not enter a second backup lifecycle.

## Goals / Non-Goals

**Goals:**

- Updating is: see a notification, tap twice, app relaunches on the new version.
- Nothing large is transferred without the user asking.
- A corrupted or substituted APK is detected before the installer sees it.
- Every failure is silent and leaves a working app on the version it already had.
- No part of the app becomes dependent on network or on GitHub.

**Non-Goals:**

- Any second distribution channel.
- Silent installation.
- Reducing download size.

## Decisions

### 1. This change is blocked, and saying so is part of the design

Version comparison rests entirely on the running build knowing its own version. Today it does not:
`versionCode = 1` in every published APK. Two independent failures follow — the updater cannot tell
whether the latest release is newer than itself, and `PackageInstaller` refuses a same-or-lower
`versionCode` as a downgrade regardless of what the app believes.

No workaround belongs here. Comparing `versionName` strings instead would leave the installer
rejecting the install anyway. Embedding the tag in a `BuildConfig` field would be a second version
number to keep in sync with the first. `auditfix-secrets-and-packaging` already specifies the right
fix — version derived from the validated tag, with a documented encoding whose ordering holds across
a major increment — and this change consumes it.

**The comparison is on `versionCode`, and the tag is parsed only to produce one.** `versionName` is
for display. This keeps the app's notion of "newer" identical to the platform's, so the updater can
never offer something the installer will refuse.

### 2. `releases/latest` is the right endpoint precisely because of what it excludes

GitHub's `/releases/latest` returns the most recent published, non-draft, non-pre-release release.
That is exactly the channel policy — enforced server-side, with no client-side filtering to get
wrong.

The tag is then validated against `^v(\d+)\.(\d+)\.(\d+)$` before use. `release.yml` already refuses
to build anything else, so a non-conforming tag on `/releases/latest` means something unexpected
happened, and the correct response is to do nothing rather than to guess.

Unauthenticated. 60 requests per hour per IP against a budget of one per day plus manual checks —
adding a token would mean shipping a credential to read a public endpoint.

**A rate-limit response, a 5xx, or an unreachable host is a no-op, not an error state.** The app has
nothing to tell the user in those cases: there may or may not be an update, and the check runs again
tomorrow.

### 3. Discovery is automatic, transfer is not

Daily `PeriodicWorkRequest` with a network constraint, plus a Settings button. The split is
deliberate: knowing a release exists costs a few kilobytes, and getting it costs tens of megabytes
and ends with the running app being killed. The first can be automatic; the second cannot be.

**The worker guards on `lastCheckTime`; the `PeriodicWorkRequest` alone does not implement the
cadence.** A periodic request fires on its own clock, indifferent to manual checks — so a manual
check at 11:58 followed by the periodic tick at 12:00 issues two requests two minutes apart, which
is not "about a day since the last check". The worker therefore reads the last check time first and
returns without a request when it is recent. Manual checks write the same timestamp, so the two
paths share one notion of when a check last happened.

**The guard is 20 hours, not 24.** WorkManager runs a periodic request somewhere inside its interval
rather than exactly on it, so a tick arriving at 23h50m after the previous check would be skipped by
an exact 24-hour guard — and the next tick would be roughly 47 hours later, halving the cadence
silently. A guard comfortably below the period absorbs that drift while still collapsing a manual
check and an immediately following tick into one request.

**A declined version stays declined.** Choosing "Later" records the tag. That tag is not notified
again — but a *newer* tag is, immediately. Without this, a daily check turns into a daily
notification for an update the user has already considered and rejected, which trains them to ignore
the channel entirely.

**The notification is a plain tap-to-open**, following `HltbRefreshWorker`: check the
`POST_NOTIFICATIONS` grant and skip silently if absent, since Settings still shows the available
update. No action buttons — starting a download from a notification shade puts the progress
somewhere the user is not looking.

### 4. Two verifications, checking different things

```
  download  ──▶  SHA-256 vs .sha256 asset   ──▶  signer cert vs running app  ──▶  installer
                 "is this the file GitHub          "was this built by the         (OS re-checks
                  says it published?"               same keystore as me?"          the signature)
```

Neither subsumes the other, and both are cheap:

- **The checksum catches a truncated or corrupted download** — the overwhelmingly likely real-world
  failure, and one the OS signature check catches only late, opaquely, and after the user has
  committed to installing.
- **The signer check catches a wrong-key APK before the installer does.** The OS enforces this
  regardless, so this is not the security boundary — it is a fast, legible failure instead of a
  system dialog that says the app could not be installed.

This is why `release.yml` gains a checksum step: without a published digest there is nothing to
compare against, and "verify if possible" collapses to "hope". The checksum is generated in CI from
the same APK that is uploaded, in the same job, and attached as `<name>.apk.sha256`.

**A missing checksum asset means the update is not offered.** Not "offered without verification" —
an update that cannot be verified is one this change has no way to reason about, and the failure is
recoverable by publishing a correct release.

**The checksum is integrity, not authenticity.** It is fetched from the same host that serves the
APK, so it proves the download matches what GitHub holds, not that GitHub holds something
trustworthy. Authenticity comes from the signing key, which is enforced by the OS and never leaves
CI. Stating this here prevents the checksum from later being mistaken for a security boundary it is
not.

### 5. Download to `noBackupFilesDir`, and delete it on every path out

The APK is a large, fully re-obtainable file with no user data in it. It goes to app-private
external-free storage under `noBackupFilesDir`, matching the reasoning
`auditfix-secrets-and-packaging` applies to snapshots: derived files should not enter Android's
backup lifecycle.

**Deleted after a successful install, after a failed verification, after a failed install, and on
the next check.** The last one matters — a process death between download and install would
otherwise leave tens of megabytes stranded forever. Each check sweeps any APK that is not the one
currently being offered.

**Download progress is shown in the update sheet, not in a notification.** The user is looking at
the sheet; they arrived there by choosing to update.

### 6. `PackageInstaller`, and the first update is necessarily interactive

`REQUEST_INSTALL_PACKAGES` is declared in the manifest. It is install-time and normal, but Android
additionally requires the user to grant "install unknown apps" to this specific app, which is a
Settings trip the first time.

A nuance worth recording, because it will otherwise look like a bug: **the installer of record for
the current build is whatever installed it** — a browser, or `adb`. A self-update is therefore
user-confirmed, showing the system install dialog. Once an update has been applied by Backlogium,
Backlogium becomes the installer of record, and `setRequireUserAction(false)` becomes available on
API 31+ for subsequent updates. This change does not use it: fully silent installation was
explicitly not wanted, and depending on installer-of-record state would make the flow behave
differently on the second run than on the first for no benefit.

**Relaunch is a `PendingIntent` fired on `STATUS_SUCCESS`, launching the main activity.** The app
process is killed by the update; the pending intent survives it. On any other status, including the
user cancelling the system dialog, nothing is relaunched, the downloaded file is deleted, and the
app is left exactly as it was.

### 7. Release builds only

The checker does not run and the Settings section is absent in debug builds, gated on
`BuildConfig.DEBUG`.

A debug APK is signed with the debug key. Installing a release APK over it fails the OS signature
check, unconditionally. Offering an action whose only possible outcome is a system error dialog is
worse than offering nothing — and the alternative, a warning explaining that the thing you are
about to do cannot work, is a dead-end path shipped in every build.

### 8. Nothing here can make the app require a network

Every element is additive and independently absent-able: the worker's failure is a no-op, the
Settings section renders the running version with "never checked" and stays usable, the notification
is skipped when the permission is absent, and no other feature reads update state.

The one thing to guard against is the update check becoming a startup dependency. It is a
`PeriodicWorkRequest` with a network constraint precisely so the system runs it when appropriate and
never during app launch.

## Risks / Trade-offs

- **Hard dependency on another unimplemented change.** Stated in the proposal; there is no partial
  version of this that works without it.
- **`REQUEST_INSTALL_PACKAGES` is a meaningful permission.** Justified by the feature's entire
  purpose, declared with a comment naming why, and paired with a user-granted "install unknown apps"
  toggle the app cannot bypass.
- **GitHub is a single point of failure for updates.** Acceptable: it is already the only
  distribution channel, and its absence leaves a working app.
- **A release published without a checksum asset silently offers no update.** Mitigated by
  generating the checksum in the same CI job as the APK, so the two cannot diverge.
- **The daily check consumes a small amount of background budget.** One constrained request per day
  against an app that already runs a sync every 15 minutes.

## Migration Plan

No schema change. New DataStore keys, all absence-tolerant: never checked, nothing seen, nothing
declined. An existing install begins checking on its next periodic window.

`release.yml`'s checksum step applies to future releases only. Releases published before it cannot
be offered as updates, since they carry no checksum — acceptable, since the only release anyone
would want is the newest one.

## Open Questions

None.
