# app-updates

## Purpose

Defines how the app discovers, verifies, and installs updates to itself from the project's
GitHub Releases: discovery cadence and channel rules, notification and decline semantics,
readable release-note presentation, download visibility and cleanup, two-stage verification
before install, the install and relaunch flow, and the requirement that the whole path
degrade silently offline and stay entirely absent from builds it cannot upgrade.

## Requirements

### Requirement: Release discovery
The system SHALL discover newer releases of itself from the project's GitHub Releases, checking
automatically about once a day and additionally whenever the user requests a check. A check SHALL
consider only full published releases whose tag is a valid `vX.Y.Z` version, and SHALL ignore
drafts and pre-releases.

#### Scenario: Automatic check
- **WHEN** about a day has passed since the last check and the device has a network
- **THEN** the latest published release is retrieved and compared against the running version

#### Scenario: Manual check
- **WHEN** the user requests a check
- **THEN** a check runs immediately regardless of when the last one ran, and its outcome is
  reported

#### Scenario: Manual and automatic checks share one cadence
- **WHEN** an automatic check is due shortly after a manual check has completed
- **THEN** no request is issued, because a check has recently happened

#### Scenario: A manual check defers the next automatic one
- **WHEN** the user checks manually
- **THEN** the recorded time of the last check advances, so the next automatic check is measured
  from it

#### Scenario: Pre-release published
- **WHEN** the newest release is marked as a pre-release or a draft
- **THEN** it is not considered, and no update is offered on its account

#### Scenario: Tag is not a valid version
- **WHEN** the latest release's tag does not parse as `vX.Y.Z`
- **THEN** no update is offered and the check ends without error

#### Scenario: Check is not part of app startup
- **WHEN** the app is launched
- **THEN** no release check is performed as a condition of starting or of rendering any screen

### Requirement: Version comparison uses the installed package version
The system SHALL decide whether a release is newer by comparing the version encoded in its tag
against the running build's own package version, using the same ordering the platform uses to
decide whether an install is an upgrade. A release SHALL be offered only when the platform would
accept it as an upgrade.

#### Scenario: Newer release
- **WHEN** the latest release's version orders above the running build's
- **THEN** it is treated as an available update

#### Scenario: Same version
- **WHEN** the latest release's version equals the running build's
- **THEN** no update is offered

#### Scenario: Older release
- **WHEN** the latest release's version orders below the running build's
- **THEN** no update is offered, and no downgrade is attempted

#### Scenario: Offer implies installability
- **WHEN** an update is offered
- **THEN** the platform would accept the corresponding artifact as an upgrade rather than rejecting
  it as a downgrade

### Requirement: An available update is announced without transferring it
When a newer release is found, the system SHALL notify the user, identifying the available version
and presenting the release's notes. The system SHALL NOT download the release artifact until the
user asks for it.

#### Scenario: Update found
- **WHEN** a check finds a newer release
- **THEN** the user is notified of the available version

#### Scenario: Nothing is downloaded on discovery
- **WHEN** a check finds a newer release
- **THEN** no release artifact is transferred as part of that check

#### Scenario: Release notes presented
- **WHEN** the user opens the available update
- **THEN** the running version, the available version, and the release's notes are presented, with
  an action to update and an action to decline

#### Scenario: Notification permission absent
- **WHEN** a newer release is found and the notification permission has not been granted
- **THEN** the check completes silently and the available update remains visible in Settings

### Requirement: A declined version is not re-announced
When the user declines an available update, the system SHALL NOT announce that version again. A
release newer than the declined one SHALL be announced normally.

#### Scenario: Declining
- **WHEN** the user declines an available update
- **THEN** subsequent checks finding that same version produce no further notification

#### Scenario: A newer version after declining
- **WHEN** a release newer than a declined one is published and found
- **THEN** it is announced, regardless of the earlier decline

#### Scenario: Declined update remains reachable
- **WHEN** the user has declined an update that is still the newest release
- **THEN** it is still shown in Settings and can still be applied on request

### Requirement: The artifact is verified before installation
The system SHALL verify a downloaded release artifact before offering it to the installer, checking
both that the file matches the digest published alongside it and that it was signed by the same key
as the running build. An artifact failing either check SHALL NOT be installed and SHALL be deleted.

#### Scenario: Artifact verified
- **WHEN** a downloaded artifact matches its published digest and its signing certificate matches
  the running build's
- **THEN** it is passed to the installer

#### Scenario: Digest mismatch
- **WHEN** a downloaded artifact's digest does not match the published one
- **THEN** it is not installed, it is deleted, and the failure is reported as a failed download

#### Scenario: Signer mismatch
- **WHEN** a downloaded artifact's signing certificate does not match the running build's
- **THEN** it is not installed, it is deleted, and the failure is reported

#### Scenario: No published digest
- **WHEN** a release publishes no digest for its artifact
- **THEN** no update is offered for that release, rather than one being offered unverified

#### Scenario: Artifact absent from the release
- **WHEN** a release publishes no installable artifact
- **THEN** no update is offered for that release

### Requirement: Download is visible, cancellable, and leaves nothing behind
The system SHALL show the progress of a release download while it runs, SHALL let the user abandon
it, and SHALL retain no downloaded artifact after the update completes, fails, or is abandoned.

#### Scenario: Progress shown
- **WHEN** a release artifact is being downloaded
- **THEN** its progress is presented on the surface the user started it from

#### Scenario: Download abandoned
- **WHEN** the user abandons a download in progress
- **THEN** the transfer stops and no partial artifact is retained

#### Scenario: Download fails
- **WHEN** a download fails
- **THEN** the failure is reported, no partial artifact is retained, and the app is unchanged

#### Scenario: Artifact removed after installation
- **WHEN** an update is installed
- **THEN** the downloaded artifact is removed

#### Scenario: Orphaned artifact swept
- **WHEN** a check runs and a previously downloaded artifact remains that is not the one currently
  being offered
- **THEN** it is removed

### Requirement: Installation and relaunch
On the user's request and after verification, the system SHALL install the update and, on success,
relaunch the app. Any other outcome SHALL leave the installed app unchanged.

#### Scenario: Update applied
- **WHEN** a verified artifact is installed successfully
- **THEN** the app is relaunched on the new version

#### Scenario: Install succeeds while the app is backgrounded
- **WHEN** a verified artifact is installed successfully and the app has no visible activity at that
  moment
- **THEN** the app is not relaunched automatically, and a tap-to-open notification announcing the new
  version is posted instead, so the platform's background-activity-launch restriction is never
  attempted against

#### Scenario: Install declined at the system prompt
- **WHEN** the user cancels the system installation prompt
- **THEN** the app remains on its current version, is not relaunched, and the artifact is removed

#### Scenario: Install fails
- **WHEN** installation fails for any reason
- **THEN** the app remains on its current version and the failure is reported

#### Scenario: Permission to install not granted
- **WHEN** the user has not permitted this app to install applications
- **THEN** the requirement is explained and the user is directed to grant it, rather than the
  attempt failing without explanation

### Requirement: Updating never becomes a dependency
No part of the app's behaviour SHALL depend on a release check having run, having succeeded, or on
the update service being reachable. Every failure of the update path SHALL be silent and leave the
app fully usable.

#### Scenario: Device offline
- **WHEN** the device has no network
- **THEN** checks do not run, nothing is reported as broken, and every other feature behaves as it
  does today

#### Scenario: Service unreachable or rate-limited
- **WHEN** a check cannot reach the release service or is refused by it
- **THEN** the check ends without altering any state other than the time of the last attempt

#### Scenario: Never checked
- **WHEN** no check has ever completed
- **THEN** the app presents its running version and reports that no check has completed, rather
  than presenting an error

#### Scenario: No feature reads update state
- **WHEN** any screen other than the update surfaces is shown
- **THEN** its behaviour is unaffected by whether an update is available

### Requirement: Structured release notes enhance but do not gate updates
The system SHALL prefer a supported structured-notes asset belonging to the available release and SHALL preserve update availability when that presentation metadata cannot be used. Structured content SHALL be accepted only when it is bounded, valid, and names the same tag as the release.

#### Scenario: Supported structured notes are available
- **WHEN** an otherwise valid newer release carries a supported structured-notes asset whose tag matches the release
- **THEN** the update offer retains its categorized user-facing notes for notification and review presentation

#### Scenario: Structured notes are absent
- **WHEN** an otherwise valid newer release has no structured-notes asset
- **THEN** the update is still offered using a sanitized bounded presentation derived from the legacy release body

#### Scenario: Structured notes cannot be retrieved
- **WHEN** the structured-notes request fails, is refused, or exceeds its allowed size
- **THEN** the update is still offered using the legacy fallback and the failure is not reported as an update failure

#### Scenario: Structured notes are malformed or mismatched
- **WHEN** the structured document is malformed, uses an unsupported schema, or names another tag
- **THEN** it is ignored and cannot change version, artifact, digest, signer, or installation decisions

#### Scenario: Discovered notes are reviewed offline
- **WHEN** an update with structured notes has already been discovered and the device later becomes offline
- **THEN** its readable notes remain available from persisted update state

### Requirement: Update review presents readable product changes
The update review surface SHALL present the installed and available versions with categorized plain-text release items and SHALL NOT expose Markdown decoration, raw URLs, contributor suffixes, or conventional commit prefixes as release-note content. Existing update, decline, cancel, progress, verification, permission, and failure controls SHALL remain available in their applicable states.

#### Scenario: Update has user-facing sections
- **WHEN** the user reviews an update with one or more structured feature, fix, or performance sections
- **THEN** the sheet shows readable section headings and bullet items beneath the version transition

#### Scenario: Update is maintenance-only
- **WHEN** the release contains no user-visible change entries
- **THEN** the sheet presents an honest maintenance message rather than an empty region or a raw full-changelog URL

#### Scenario: Legacy body contains generated Markdown
- **WHEN** fallback notes contain GitHub headings, emphasis markers, author suffixes, and a standalone full-changelog URL
- **THEN** the sheet presents a bounded readable summary without those formatting artifacts

#### Scenario: Download is in progress
- **WHEN** the user starts the update from the redesigned review surface
- **THEN** the existing progress and cancellation behavior remains visible and usable alongside the release summary

#### Scenario: Full changelog is opened
- **WHEN** the user invokes a full-changelog action and the release supplies a validated comparison URL for this repository over HTTPS
- **THEN** the system opens that URL externally without interpreting arbitrary release text as a destination

### Requirement: Update notifications use a concise release summary
An available-update notification SHALL identify the available Backlogium version and use bounded plain-text user-facing content rather than embedding the complete GitHub release body.

#### Scenario: User-facing release item exists
- **WHEN** a newer release is announced and has at least one user-facing item
- **THEN** the notification identifies the version and summarizes the first item without Markdown syntax, contributor handles, or raw URLs

#### Scenario: Maintenance release is announced
- **WHEN** a newer release is announced without user-facing items
- **THEN** the notification identifies it as a maintenance update and remains actionable

#### Scenario: Notification is opened
- **WHEN** the user selects the concise update notification
- **THEN** the app opens the full native update review surface for that release

### Requirement: Updating is offered only where it can work
The update capability SHALL be present only in builds that a published release could upgrade, and
SHALL be entirely absent otherwise.

#### Scenario: Release build
- **WHEN** the app is a release build
- **THEN** checks run, and the update surfaces are present

#### Scenario: Development build
- **WHEN** the app is a development build, which a published release cannot upgrade
- **THEN** no check runs and the update surfaces are absent, rather than offering an action that
  cannot succeed
