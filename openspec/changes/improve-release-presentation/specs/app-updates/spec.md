## ADDED Requirements

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
